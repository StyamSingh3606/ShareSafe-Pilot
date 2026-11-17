import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createCipheriv, randomBytes, randomUUID, scryptSync } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import test from 'node:test';
import pg from 'pg';

test('Hosted sender login, email invitation, OTP and file download', async () => {
  const provider = process.env.TEST_EMAIL_PROVIDER ?? 'resend';
  assert.ok(process.env.DATABASE_URL, 'Run with the local test database');
  const directory = await mkdtemp(join(tmpdir(), 'sharesafe-hosted-'));
  const receipts = join(directory, 'receipts.jsonl');
  const preload = join(directory, 'delivery.mjs');
  await writeFile(preload, `
    import {appendFile} from 'node:fs/promises';
    globalThis.fetch = async (url, options) => {
      if (process.env.EMAIL_PROVIDER === 'brevo') {
        if (url !== 'https://api.brevo.com/v3/smtp/email' || options.headers['api-key'] !== 'test-brevo')
          throw Error('Unexpected Brevo request');
        const payload = JSON.parse(options.body);
        if (payload.sender.email !== 'sender@example.test') throw Error('Unexpected sender');
        options.body = JSON.stringify({to: payload.to.map(item => item.email), subject: payload.subject, text: payload.textContent});
      } else if (url !== 'https://api.resend.com/emails') throw Error('Unexpected provider');
      await appendFile(process.env.TEST_RECEIPTS, options.body + '\\n');
      return new Response(null, {status: 200});
    };
  `);
  const email = 'sender-' + randomUUID() + '@example.test';
  const password = randomBytes(32).toString('hex');
  const newEmail = 'registrant-' + randomUUID() + '@example.test';
  const salt = randomBytes(16);
  const api = 'http://127.0.0.1:18787';
  const publicOrigin = 'https://sharesafe.example.test';
  const child = spawn(process.execPath, ['--import', pathToFileURL(preload).href, '--import', 'tsx', 'src/server.ts'], {
    stdio: ['ignore', 'ignore', 'pipe'],
    env: {
      ...process.env, NODE_ENV: 'production', PORT: '18787', PUBLIC_ORIGIN: publicOrigin,
      TEST_RECEIPTS: receipts, SENDER_EMAIL: email,
      SENDER_PASSWORD_HASH: salt.toString('hex') + ':' + scryptSync(password, salt, 64).toString('hex'),
      EMAIL_PROVIDER: provider,
      RESEND_API_KEY: provider === 'resend' ? 'test-provider' : '', MAIL_FROM: 'sender@example.test',
      BREVO_API_KEY: 'test-brevo', BREVO_SENDER_EMAIL: 'sender@example.test',
      ANDROID_INSTALL_URL: publicOrigin + '/install', ANDROID_CERT_SHA256: 'AB:'.repeat(31) + 'AB',
    },
  });
  const stopped = once(child, 'exit');
  let diagnostics = '';
  child.stderr?.on('data', chunk => { diagnostics += chunk.toString(); });
  const database = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  const call = (path: string, body?: unknown, bearer?: string) => fetch(api + path, {
    method: body === undefined ? 'GET' : 'POST', signal: AbortSignal.timeout(4000),
    headers: { ...(body === undefined ? {} : {'Content-Type':'application/json'}), ...(bearer ? {Authorization:'Bearer ' + bearer} : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const messages = async () => (await readFile(receipts, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
  try {
    let healthy = false;
    for (let attempt = 0; attempt < 50 && child.exitCode === null; attempt++) {
      if (await call('/health').then(response => response.ok).catch(() => false)) {
        healthy = true;
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    assert.ok(healthy, diagnostics);
    assert.equal((await call('/sender/login', {email, password:'wrong'})).status, 401);
    assert.equal((await call('/shares', undefined, process.env.DEV_SENDER_TOKEN)).status, 401);
    const login = await call('/sender/login', {email, password});
    assert.equal(login.status, 200);
    const {session: sender} = await login.json() as {session: string};
    assert.equal((await call('/sender/register/code', {email})).status, 409);
    const issue = await call('/sender/register/code', {email: newEmail});
    assert.equal(issue.status, 200);
    const { challenge } = await issue.json() as {challenge: string};
    const [signupMessage] = await messages();
    assert.deepEqual(signupMessage.to, [newEmail]);
    assert.equal(signupMessage.subject, 'Verify your ShareSafe email');
    const signupCode = signupMessage.text.match(/\b\d{6}\b/)[0];
    const registration = { challenge, password, code: signupCode };
    assert.equal((await call('/sender/login', {email: newEmail, password})).status, 401);
    assert.equal((await call('/sender/register', { ...registration, code: signupCode === '000000' ? '000001' : '000000' })).status, 403);
    assert.equal((await call('/sender/register', { ...registration, password: 'short' })).status, 400);
    const attempts = await Promise.all([call('/sender/register', registration), call('/sender/register', registration)]);
    assert.deepEqual(attempts.map(response => response.status).sort(), [201, 403]);
    assert.equal((await call('/sender/register/code', {email: newEmail.toUpperCase()})).status, 409);
    assert.equal((await call('/sender/login', { email: newEmail, password: 'wrong' })).status, 401);
    const registeredLogin = await call('/sender/login', { email: newEmail, password });
    assert.equal(registeredLogin.status, 200);
    const { session: registeredSender } = await registeredLogin.json() as { session: string };
    const assets = await call('/.well-known/assetlinks.json');
    assert.equal((await assets.json() as {target: {package_name: string}}[])[0].target.package_name, 'com.sharesafe.app');
    const id = randomUUID();
    const key = randomBytes(32);
    const nonce = randomBytes(12);
    const file = Buffer.from('%PDF-1.4\n%%EOF\n');
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(Buffer.from(id + ':1'));
    const encrypted = Buffer.concat([nonce,cipher.update(file),cipher.final(),cipher.getAuthTag()]).toString('base64');
    const recipient = 'hosted-' + randomUUID() + '@example.test';
    const payload = {id,key:key.toString('base64'),encrypted,name:'test.pdf',recipient,duration:null};
    const publication = await call('/shares', payload, sender);
    assert.equal(publication.status, 200);
    const {invitation, deliveryAccepted} = await publication.json() as {invitation: string; deliveryAccepted: boolean};
    assert.equal(deliveryAccepted, true);
    assert.ok(invitation.startsWith(publicOrigin + '/i/'));
    await call('/shares', payload, sender);
    assert.deepEqual(await (await call('/shares', undefined, registeredSender)).json(), { shares: [] });
    const sent = (await messages()).slice(1);
    assert.equal(sent.length, 1);
    assert.deepEqual(sent[0].to, [recipient]);
    assert.ok(sent[0].text.includes(invitation));
    const invite = invitation.split('/').at(-1)!;
    const receive = '/receive/' + invite;
    const landing = await call('/i/' + invite);
    assert.equal(landing.status, 200);
    assert.ok(!(await landing.text()).includes('test.pdf'));
    assert.ok(!Object.hasOwn(await (await call(receive)).json() as object, 'name'));
    assert.equal((await call(receive + '/content/preview')).status, 401);
    const receiver = randomUUID();
    assert.equal((await call(receive + '/code', {receiver})).status, 200);
    assert.equal((await call('/dev/inbox/' + invite, undefined, process.env.DEV_INBOX_TOKEN)).status, 404);
    const delivered = (await messages()).slice(1);
    assert.deepEqual(delivered[1].to, [recipient]);
    const code = delivered[1].text.match(/\b\d{6}\b/)[0];
    const grant = await call(receive + '/verify', {receiver, code});
    assert.equal(grant.status, 200);
    const result = await grant.json() as {session: string; format: string};
    assert.equal(result.format, 'pdf');
    const download = await call(receive + '/content/download', undefined, result.session);
    assert.equal(download.status, 200);
    assert.deepEqual(Buffer.from(await download.arrayBuffer()), file);
  } finally {
    child.kill();
    await stopped;
    await database.query('DELETE FROM shares WHERE owner=$1', [email]);
    await database.query('DELETE FROM sender_sessions WHERE owner IN ($1,$2)', [email, newEmail]);
    await database.query('DELETE FROM sender_registration_challenges WHERE email=$1', [newEmail]);
    await database.query('DELETE FROM sender_accounts WHERE email=$1', [newEmail]);
    await database.end();
    await rm(directory, {recursive:true,force:true});
  }
});
