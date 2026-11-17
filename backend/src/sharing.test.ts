import assert from 'node:assert/strict';
import { createCipheriv, randomBytes, randomUUID } from 'node:crypto';
import test from 'node:test';
import pg from 'pg';

const base = 'http://127.0.0.1:8787';
const sender = process.env.DEV_SENDER_TOKEN;
const inbox = process.env.DEV_INBOX_TOKEN;

async function call(path: string, body?: unknown, token?: string) {
  return fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

for (const duration of [null, 60]) {
  test(duration === null ? 'OTP permits original download' : 'Timed access permits preview only', async () => {
    assert.ok(sender && inbox, 'Run against a local backend with development credentials');
    const file = Buffer.from('%PDF-1.4\n%%EOF\n');
    const id = randomUUID();
    const key = randomBytes(32);
    const nonce = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(Buffer.from(id + ':1'));
    const encrypted = Buffer.concat([nonce, cipher.update(file), cipher.final(), cipher.getAuthTag()]);
    const payload = { id, name: 'fixture.pdf', recipient: 'alice@example.test', duration, key: key.toString('base64'), encrypted: encrypted.toString('base64') };
    const tampered = Buffer.from(encrypted);
    tampered[tampered.length - 1] ^= 1;
    assert.equal((await call('/shares', { ...payload, encrypted: tampered.toString('base64') }, sender)).status, 400);
    const published = await call('/shares', payload, sender);
    assert.equal(published.status, 200);
    const { invitation } = await published.json() as { invitation: string };
    const retry = await call('/shares', payload, sender);
    assert.deepEqual(await retry.json(), { invitation });
    const receive = '/receive/' + invitation;
    assert.equal((await call(receive + '/content/preview')).status, 401);
    const receiver = randomUUID();
    assert.equal((await call(receive + '/code', { receiver })).status, 200);
    assert.equal((await call(receive + '/code', { receiver })).status, 429);
    const { code } = await (await call('/dev/inbox/' + invitation, undefined, inbox)).json() as { code: string };
    assert.equal((await call(receive + '/verify', { receiver: randomUUID(), code })).status, 403);
    const responses = await Promise.all([call(receive + '/verify', { receiver, code }), call(receive + '/verify', { receiver, code })]);
    assert.deepEqual(responses.map(value => value.status).sort(), [200, 403]);
    const { session } = await responses.find(response => response.ok)!.json() as { session: string };
    const preview = await call(receive + '/content/preview', undefined, session);
    assert.equal(preview.status, 200);
    assert.deepEqual(Buffer.from(await preview.arrayBuffer()), file);
    const download = await call(receive + '/content/download', undefined, session);
    assert.equal(download.status, duration === null ? 200 : 403);
    if (download.ok) assert.deepEqual(Buffer.from(await download.arrayBuffer()), file);
    if (duration !== null) {
      const database = new pg.Pool({ connectionString: process.env.DATABASE_URL });
      try {
        const expired = await database.query(
          "UPDATE shares SET expires_at = now() - interval '1 millisecond' WHERE id = $1 AND invite = $2",
          [id, invitation],
        );
        assert.equal(expired.rowCount, 1);
        assert.equal((await call(receive)).status, 404);
        assert.equal((await call(receive + '/code', { receiver })).status, 404);
        assert.equal((await call(receive + '/content/preview', undefined, session)).status, 403);
      } finally {
        await database.end();
      }
    }
  });
}
