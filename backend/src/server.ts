import Fastify from 'fastify';
import pg from 'pg';
import { readFile } from 'node:fs/promises';
import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { unzipSync } from 'fflate';
import { account, checkHostedConfig, checkPassword, deliver, hashPassword, hosted, origin, supportsDestination } from './hosted.js';

const app = Fastify({ bodyLimit: 36_000_000, logger: false });
const db = new pg.Pool({ connectionString: process.env.DATABASE_URL });
const secret = Buffer.from(process.env.LOCAL_WRAP_KEY ?? '', 'hex');
const otpSecret = Buffer.from(process.env.OTP_SECRET ?? '', 'hex');
const senderToken = process.env.DEV_SENDER_TOKEN ?? '';
const inboxToken = process.env.DEV_INBOX_TOKEN ?? '';
if (!process.env.DATABASE_URL || secret.length !== 32 || otpSecret.length !== 32 ||
    secret.equals(otpSecret) || (!hosted && (senderToken.length < 24 || inboxToken.length < 24))) throw new Error('Configure separate server credentials');
checkHostedConfig();
const inbox = new Map<string, { code: string; expiry: number }>();
const token = () => randomBytes(32).toString('base64url');
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const validId = (value: string) => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
const aad = (id: string) => Buffer.from(id + ':1');
function seal(key: Buffer, value: Buffer, context: Buffer) {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(context);
  return Buffer.concat([nonce, cipher.update(value), cipher.final(), cipher.getAuthTag()]);
}
function open(key: Buffer, record: Buffer, context: Buffer) {
  if (record.length < 28) throw new Error('Incomplete encrypted record');
  const cipher = createDecipheriv('aes-256-gcm', key, record.subarray(0, 12));
  cipher.setAAD(context);
  cipher.setAuthTag(record.subarray(-16));
  return Buffer.concat([cipher.update(record.subarray(12, -16)), cipher.final()]);
}
const limit = 25_000_000;
const media: Record<string, string> = {
  pdf: 'application/pdf', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  mp3: 'audio/mpeg', wav: 'audio/wav',
};
function validFile(format: string, bytes: Buffer): boolean {
  if (format === 'pdf') return bytes.subarray(0, 5).toString() === '%PDF-' && bytes.subarray(-1024).includes('%%EOF');
  if (format === 'wav') return bytes.length >= 44 && bytes.subarray(0, 4).toString() === 'RIFF' && bytes.subarray(8, 12).toString() === 'WAVE';
  if (format === 'mp3') return bytes.length >= 32 && (bytes.subarray(0, 3).toString() === 'ID3' || (bytes[0] === 255 && (bytes[1] & 224) === 224));
  if (format !== 'docx') return false;
  const zip = unzipSync(bytes, { filter: file => {
    if (!['[Content_Types].xml', 'word/document.xml'].includes(file.name)) return false;
    if (file.originalSize > 4_000_000) throw new Error('Oversized document XML');
    return true;
  } });
  return Boolean(zip['[Content_Types].xml'] && zip['word/document.xml']);
}
const digest = (id: string, receiver: string, nonce: string, code: string) =>
  createHmac('sha256', otpSecret).update(JSON.stringify([id, receiver, nonce, code])).digest();

app.setErrorHandler((_error, _req, reply) => reply.code(400).send({ error: 'Request could not be completed' }));
app.addHook('onRequest', async (_req, reply) => { reply.header('Cache-Control', 'no-store'); });
app.get('/health', async () => ({ ok: true }));
async function allowRequest(name: string, limit: number) {
  const result = await db.query(`INSERT INTO request_windows(name,attempts) VALUES($1,1)
    ON CONFLICT(name) DO UPDATE SET
    attempts=CASE WHEN request_windows.opened_at <= now()-interval '15 minutes' THEN 1 ELSE request_windows.attempts+1 END,
    opened_at=CASE WHEN request_windows.opened_at <= now()-interval '15 minutes' THEN now() ELSE request_windows.opened_at END
    WHERE request_windows.attempts < $2 OR request_windows.opened_at <= now()-interval '15 minutes' RETURNING name`, [name, limit]);
  return Boolean(result.rowCount);
}
async function sender(authorization: string | undefined): Promise<string | undefined> {
  if (!hosted) return authorization === 'Bearer ' + senderToken ? 'local' : undefined;
  const bearer = authorization?.match(/^Bearer ([\w-]{43})$/)?.[1];
  if (!bearer) return;
  return (await db.query('SELECT owner FROM sender_sessions WHERE token=$1 AND expires_at>now()', [hash(bearer)])).rows[0]?.owner;
}
if (hosted) {
  const registrationDigest = (challenge: string, email: string, code: string) =>
    createHmac('sha256', otpSecret).update(JSON.stringify(['sender-registration', challenge, email, code])).digest();

  app.post('/sender/register/code', { bodyLimit: 4096 }, async (req, reply) => {
    const { email } = (req.body ?? {}) as Record<string, unknown>;
    if (typeof email !== 'string' || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()))
      return reply.code(400).send({ error: 'Enter a valid email address' });
    const owner = email.trim().toLowerCase();
    if (!(await allowRequest('registration:request:ip:' + hash(req.ip), 20)) ||
        !(await allowRequest('registration:request:email:' + hash(owner), 3)))
      return reply.code(429).send({ error: 'Too many code requests. Try again in 15 minutes.' });
    const exists = await db.query('SELECT 1 FROM sender_accounts WHERE email=$1', [owner]);
    if (owner === account || exists.rowCount)
      return reply.code(409).send({ error: 'This email already has an account. Sign in instead.' });

    const challenge = token();
    const id = hash(challenge);
    const code = String(randomInt(1_000_000)).padStart(6, '0');
    await db.query("INSERT INTO sender_registration_challenges(id,email,verifier,expires_at) VALUES($1,$2,$3,now()+interval '5 minutes')",
      [id, owner, registrationDigest(challenge, owner, code)]);
    try {
      await deliver(owner, 'Verify your ShareSafe email', 'Your ShareSafe registration verification code is ' + code + '. It expires in five minutes. Enter it only in ShareSafe.', 'registration-' + id);
    } catch {
      await db.query('DELETE FROM sender_registration_challenges WHERE id=$1', [id]);
      return reply.code(503).send({ error: 'Could not send a verification code to this email. Check the address and try again.' });
    }
    return { challenge };
  });

  app.post('/sender/register', { bodyLimit: 4096 }, async (req, reply) => {
    const { challenge, code, password } = (req.body ?? {}) as Record<string, unknown>;
    if (typeof challenge !== 'string' || !/^[\w-]{43}$/.test(challenge) ||
        typeof code !== 'string' || !/^\d{6}$/.test(code) ||
        typeof password !== 'string' || password.length < 12 || password.length > 256)
      return reply.code(400).send({ error: 'Enter a six-digit code and a password of at least 12 characters' });
    if (!(await allowRequest('registration:verify:ip:' + hash(req.ip), 60)))
      return reply.code(429).send({ error: 'Too many verification attempts. Try again in 15 minutes.' });

    const id = hash(challenge);
    const client = await db.connect();
    try {
      await client.query('BEGIN');
      const result = await client.query("SELECT email,verifier FROM sender_registration_challenges WHERE id=$1 AND attempts<5 AND expires_at>now() FOR UPDATE", [id]);
      const row = result.rows[0];
      if (!row || !timingSafeEqual(row.verifier, registrationDigest(challenge, row.email, code))) {
        if (row) await client.query('UPDATE sender_registration_challenges SET attempts=attempts+1 WHERE id=$1', [id]);
        await client.query('COMMIT');
        return reply.code(403).send({ error: 'Code is incorrect or has expired.' });
      }
      const inserted = row.email === account ? null : await client.query(
        'INSERT INTO sender_accounts(email,password_hash) VALUES($1,$2) ON CONFLICT DO NOTHING',
        [row.email, await hashPassword(password)],
      );
      await client.query('DELETE FROM sender_registration_challenges WHERE id=$1', [id]);
      await client.query('COMMIT');
      if (!inserted?.rowCount) return reply.code(409).send({ error: 'This email already has an account. Sign in instead.' });
      return reply.code(201).send({ created: true });
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  });
  app.post('/sender/login', async (req, reply) => {
    const { email, password } = (req.body ?? {}) as Record<string, unknown>;
    if (typeof email !== 'string' || email.length > 254 || typeof password !== 'string' || password.length > 256)
      return reply.code(400).send({ error: 'Enter your email and password' });
    if (!(await allowRequest('login:' + hash(req.ip), 10)) || !(await allowRequest('login:account', 60)))
      return reply.code(429).send({ error: 'Too many sign-in attempts. Try again later.' });
    const owner = email.trim().toLowerCase();
    const stored = owner === account ? undefined :
      (await db.query('SELECT password_hash FROM sender_accounts WHERE email=$1', [owner])).rows[0]?.password_hash as string | undefined;
    const matches = await checkPassword(password, stored);
    if (!matches || (owner !== account && !stored)) return reply.code(401).send({ error: 'Sign-in failed' });
    const session = token();
    await db.query("INSERT INTO sender_sessions VALUES($1,$2,now()+interval '1 hour')", [hash(session), owner]);
    return { session };
  });
  app.get('/.well-known/assetlinks.json', async () => [{
    relation: ['delegate_permission/common.handle_all_urls'],
    target: { namespace: 'android_app', package_name: 'com.sharesafe.app', sha256_cert_fingerprints: [process.env.ANDROID_CERT_SHA256] },
  }]);
  app.get('/i/:invite', async (_req, reply) => {
    reply.header('Referrer-Policy', 'no-referrer');
    reply.header('Content-Security-Policy', "default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    const url = new URL(process.env.ANDROID_INSTALL_URL!).href.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
    return reply.type('text/html').send('<!doctype html><meta name="viewport" content="width=device-width"><title>ShareSafe invitation</title><h1>Open your ShareSafe invitation</h1><p><a href="' + url + '">Install ShareSafe</a>, then reopen the original invitation.</p>');
  });
}
app.post('/shares', async (req, reply) => {
  const owner = await sender(req.headers.authorization);
  if (!owner) return reply.code(401).send({ error: 'Sign in to send' });
  const { id, name, recipient, duration, key, encrypted } = req.body as Record<string, unknown>;
  if (typeof id !== 'string' || !validId(id) || typeof name !== 'string' || name.length > 180 ||
      /[\x00-\x1f\\/]/.test(name) || typeof recipient !== 'string' || recipient.length > 254 ||
      !/^(\+[1-9]\d{7,14}|[^\s@]+@[^\s@]+\.[^\s@]+)$/.test(recipient) ||
      !(duration === null || (typeof duration === 'number' && Number.isInteger(duration) && duration >= 60 && duration <= 604800)) ||
      typeof key !== 'string' || typeof encrypted !== 'string') return reply.code(400).send({ error: 'Invalid share details' });
  if (!supportsDestination(recipient)) return reply.code(400).send({ error: 'SMS delivery is unavailable. Use an email address.' });
  const format = name.split('.').pop()?.toLowerCase() ?? '';
  const fileKey = Buffer.from(key, 'base64');
  const record = Buffer.from(encrypted, 'base64');
  if (!Object.hasOwn(media, format) || fileKey.length !== 32 || record.length > limit + 28) return reply.code(400).send({ error: 'Invalid file or size' });
  try {
    const plain = open(fileKey, record, aad(id));
    if (!validFile(format, plain)) return reply.code(400).send({ error: 'Unsupported or invalid file' });
    const invite = token();
    const result = await db.query(
      `INSERT INTO shares(id,owner,recipient,name,format,duration,invite,wrapped,ciphertext,published_at,expires_at,invitation_delivered)
       VALUES($1,$9,$2,$3,$4,$5,$6,$7,$8,now(),now()+$5::integer*interval '1 second',$10)
       ON CONFLICT(id) DO NOTHING RETURNING invite`,
      [id, recipient, name, format, duration, invite, seal(secret, fileKey, aad(id)), record, owner, !hosted]);
    if (!hosted && result.rowCount) return { invitation: invite };
    const existing = (await db.query('SELECT invite,recipient,invitation_delivered FROM shares WHERE id=$1 AND owner=$2', [id, owner])).rows[0];
    if (!existing) return reply.code(409).send({ error: 'Share identifier unavailable' });
    if (!hosted) return { invitation: existing.invite };
    const invitation = origin + '/i/' + existing.invite;
    if (result.rowCount) {
      try {
        await deliver(existing.recipient, 'ShareSafe invitation', 'Open this ShareSafe invitation: ' + invitation, 'invite-' + id);
        await db.query('UPDATE shares SET invitation_delivered=true WHERE id=$1', [id]);
        return { invitation, deliveryAccepted: true };
      } catch { return { invitation, deliveryAccepted: false }; }
    }
    return { invitation, deliveryAccepted: existing.invitation_delivered };
  } finally { fileKey.fill(0); }
});
app.get('/shares', async (req, reply) => {
  const owner = await sender(req.headers.authorization);
  if (!owner) return reply.code(401).send({ error: 'Sign in first' });
  return { shares: (await db.query('SELECT name,invite,expires_at FROM shares WHERE owner=$1 ORDER BY created_at DESC', [owner])).rows };
});
app.get<{Params:{invite:string}}>('/receive/:invite', async (req, reply) => {
  const { rows } = await db.query('SELECT name,format,recipient,expires_at FROM shares WHERE invite=$1 AND published_at IS NOT NULL AND (expires_at IS NULL OR expires_at>now())', [req.params.invite]);
  const share = rows[0];
  if (!share) return reply.code(404).send({ error: 'Share unavailable or expired' });
  return { ...(hosted ? {} : { name: share.name, format: share.format }), destination: '••••' + share.recipient.slice(-4), timed: share.expires_at !== null };
});
app.post<{Params:{invite:string}}>('/receive/:invite/code', async (req, reply) => {
  const { receiver } = req.body as { receiver?: string };
  if (typeof receiver !== 'string' || !validId(receiver)) return reply.code(400).send({ error: 'Invalid session' });
  const { rows } = await db.query('SELECT id,recipient FROM shares WHERE invite=$1 AND published_at IS NOT NULL AND (expires_at IS NULL OR expires_at>now())', [req.params.invite]);
  if (!rows.length) return reply.code(404).send({ error: 'Share unavailable or expired' });
  const { id, recipient } = rows[0];
  if (hosted && (!(await allowRequest('otp:ip:' + hash(req.ip), 12)) || !(await allowRequest('otp:recipient:' + hash(recipient.toLowerCase()), 5))))
    return reply.code(429).send({ error: 'Too many code requests. Try again later.' });
  const code = String(randomInt(1_000_000)).padStart(6, '0');
  const nonce = token();
  const result = await db.query(`INSERT INTO challenges(share_id,receiver,nonce,verifier) VALUES($1,$2,$3,$4)
    ON CONFLICT(share_id) DO UPDATE SET receiver=$2,nonce=$3,verifier=$4,issued_at=now(),used=false
    WHERE challenges.issued_at <= now()-interval '30 seconds' AND challenges.failures<5 RETURNING nonce`,
    [id, receiver, nonce, digest(id, receiver, nonce, code)]);
  if (!result.rowCount) return reply.code(429).send({ error: 'Wait before retrying or request a new share' });
  if (hosted) {
    try {
      await deliver(recipient, 'Your ShareSafe code', 'Your ShareSafe code is ' + code + '. It expires in five minutes. Enter it only in ShareSafe.', 'otp-' + nonce);
    } catch {
      await db.query('UPDATE challenges SET used=true WHERE share_id=$1 AND nonce=$2', [id, nonce]);
      return reply.code(503).send({ error: 'Could not send the code. Wait briefly and try again.' });
    }
  } else inbox.set(req.params.invite, { code, expiry: Date.now() + 300_000 });
  return { ok: true };
});
if (!hosted) app.get<{Params:{invite:string}}>('/dev/inbox/:invite', async (req, reply) => {
  if (req.headers.authorization !== 'Bearer ' + inboxToken) return reply.code(404).send();
  const entry = inbox.get(req.params.invite);
  return entry && entry.expiry > Date.now() ? { code: entry.code } : reply.code(404).send();
});
app.post<{Params:{invite:string}}>('/receive/:invite/verify', async (req, reply) => {
  const { receiver, code } = req.body as { receiver?: string; code?: string };
  if (typeof receiver !== 'string' || !validId(receiver) || typeof code !== 'string' || !/^\d{6}$/.test(code)) return reply.code(400).send({ error: 'Enter a six-digit code' });
  const client = await db.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query(`SELECT c.*,s.expires_at,s.format FROM shares s JOIN challenges c ON c.share_id=s.id
      WHERE s.invite=$1 AND c.receiver=$2 AND c.issued_at>now()-interval '5 minutes'
      AND NOT c.used AND c.failures<5 AND (s.expires_at IS NULL OR s.expires_at>now()) FOR UPDATE OF c`, [req.params.invite, receiver]);
    const c = rows[0];
    if (!c || !timingSafeEqual(c.verifier, digest(c.share_id, receiver, c.nonce, code))) {
      if (c) await client.query('UPDATE challenges SET failures=failures+1 WHERE share_id=$1', [c.share_id]);
      await client.query('COMMIT');
      return reply.code(403).send({ error: 'Code is invalid, expired, or locked' });
    }
    const session = token();
    await client.query('UPDATE challenges SET used=true WHERE share_id=$1', [c.share_id]);
    const grant = await client.query(`INSERT INTO sessions(token,share_id,expires_at)
      VALUES($1,$2,LEAST(now()+interval '15 minutes',$3::timestamptz))
      RETURNING EXTRACT(EPOCH FROM (expires_at-now()))*1000 AS remaining`, [hash(session), c.share_id, c.expires_at]);
    await client.query('COMMIT');
    inbox.delete(req.params.invite);
    return { session, remainingMs: Number(grant.rows[0].remaining), ...(hosted ? { format: c.format } : {}) };
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally { client.release(); }
});
app.get<{Params:{invite:string; action:string}}>('/receive/:invite/content/:action', async (req, reply) => {
  const bearer = req.headers.authorization?.match(/^Bearer ([\w-]{43})$/)?.[1];
  if (!bearer) return reply.code(401).send({ error: 'Verify recipient first' });
  const { rows } = await db.query(`SELECT s.*, LEAST(s.expires_at,t.expires_at) AS deadline FROM shares s JOIN sessions t ON t.share_id=s.id
    WHERE s.invite=$1 AND t.token=$2 AND t.expires_at>now() AND (s.expires_at IS NULL OR s.expires_at>now())`, [req.params.invite, hash(bearer)]);
  const s = rows[0];
  if (!s) return reply.code(403).send({ error: 'Session unavailable or expired' });
  if (req.params.action !== 'preview' && (req.params.action !== 'download' || s.expires_at)) return reply.code(403).send({ error: 'Download unavailable' });
  const key = open(secret, s.wrapped, aad(s.id));
  try {
    const plain = open(key, s.ciphertext, aad(s.id));
    const remaining = s.deadline.getTime() - Date.now();
    if (remaining <= 0) return reply.code(403).send({ error: 'Access expired' });
    const timer = setTimeout(() => reply.raw.destroy(), remaining);
    reply.raw.once('close', () => clearTimeout(timer));
    return reply.type(media[s.format]).send(plain);
  } finally { key.fill(0); }
});
await db.query(await readFile(new URL('../migrations/001.sql', import.meta.url), 'utf8'));
await db.query(await readFile(new URL('../migrations/002.sql', import.meta.url), 'utf8'));
await db.query(await readFile(new URL('../migrations/003.sql', import.meta.url), 'utf8'));
await db.query(await readFile(new URL('../migrations/004.sql', import.meta.url), 'utf8'));
setInterval(() => {
  void db.query("DELETE FROM sender_registration_challenges WHERE expires_at <= now()").catch(() => {});
  void db.query("DELETE FROM sender_sessions WHERE expires_at <= now()").catch(() => {});
  void db.query("DELETE FROM request_windows WHERE opened_at < now()-interval '1 hour'").catch(() => {});
  for (const [invite, entry] of inbox) if (entry.expiry <= Date.now()) inbox.delete(invite);
  void db.query("DELETE FROM sessions WHERE expires_at <= now()").catch(() => {});
  void db.query("DELETE FROM shares WHERE expires_at < now()-interval '1 day'").catch(() => {});
}, 60_000).unref();
await app.listen({ host: hosted ? process.env.BIND_HOST ?? '127.0.0.1' : '127.0.0.1', port: Number(process.env.PORT ?? '8787') });
