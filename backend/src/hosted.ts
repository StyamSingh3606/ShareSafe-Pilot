import { randomBytes, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const derive = promisify(scrypt);
export const hosted = process.env.NODE_ENV === 'production';
export const origin = process.env.PUBLIC_ORIGIN ?? process.env.RENDER_EXTERNAL_URL ?? '';
export const account = process.env.SENDER_EMAIL?.trim().toLowerCase() ?? '';
const operatorPasswordHash = process.env.SENDER_PASSWORD_HASH ?? '';
const [salt, digest] = operatorPasswordHash.split(':');

export function checkHostedConfig() {
  if (!hosted) return;
  const url = new URL(origin);
  if (url.protocol !== 'https:' || url.origin !== origin || !account ||
      !/^[\da-f]{32}$/i.test(salt ?? '') || !/^[\da-f]{128}$/i.test(digest ?? '') ||
      !process.env.RESEND_API_KEY || !process.env.MAIL_FROM ||
      !/^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/i.test(process.env.ANDROID_CERT_SHA256 ?? '') ||
      !process.env.ANDROID_INSTALL_URL?.startsWith('https://')) throw new Error('Incomplete hosted configuration');
}

export async function hashPassword(password: string) {
  const salt = randomBytes(16);
  const digest = await derive(password, salt, 64) as Buffer;
  return salt.toString('hex') + ':' + digest.toString('hex');
}

export async function checkPassword(password: string, storedHash?: string) {
  const [salt, digest] = (storedHash ?? operatorPasswordHash).split(':');
  const candidate = await derive(password, Buffer.from(salt, 'hex'), 64) as Buffer;
  return timingSafeEqual(candidate, Buffer.from(digest, 'hex'));
}

export function supportsDestination(destination: string) {
  return !hosted || !destination.startsWith('+') || Boolean(
    /^AC[\da-f]{32}$/i.test(process.env.TWILIO_ACCOUNT_SID ?? '') &&
    process.env.TWILIO_AUTH_TOKEN && process.env.TWILIO_FROM
  );
}

export async function deliver(to: string, subject: string, message: string, idempotencyKey: string) {
  const signal = AbortSignal.timeout(10_000);
  let response: Response;
  if (to.startsWith('+')) {
    const sid = process.env.TWILIO_ACCOUNT_SID;
    response = await fetch('https://api.twilio.com/2010-04-01/Accounts/' + sid + '/Messages.json', {
      method: 'POST', signal,
      headers: { Authorization: 'Basic ' + Buffer.from(sid + ':' + process.env.TWILIO_AUTH_TOKEN).toString('base64') },
      body: new URLSearchParams({ To: to, From: process.env.TWILIO_FROM!, Body: message }),
    });
  } else {
    response = await fetch('https://api.resend.com/emails', {
      method: 'POST', signal,
      headers: {
        Authorization: 'Bearer ' + process.env.RESEND_API_KEY,
        'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ from: process.env.MAIL_FROM, to: [to], subject, text: message }),
    });
  }
  await response.body?.cancel();
  if (!response.ok) throw new Error('Message provider rejected the request');
}
