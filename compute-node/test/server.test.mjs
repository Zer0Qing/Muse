import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createMuseNodeServer } from '../server.mjs';

const TOKEN = 'test-token-placeholder';

async function createHarness(t) {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'muse-node-test-'));
  const app = createMuseNodeServer({
    homeDir,
    token: TOKEN,
    packs: ['core'],
    logger: { info() {}, warn() {}, error() {} },
    browserIdleMs: 60000,
  });
  const address = await app.start(0);
  t.after(async () => {
    await app.stop();
    await fs.rm(homeDir, { recursive: true, force: true });
  });
  return { app, homeDir, port: address.port, base: 'http://127.0.0.1:' + address.port };
}

async function request(base, method, pathName, options = {}) {
  const headers = {};
  if (options.token !== null) headers.authorization = 'Bearer ' + (options.token || TOKEN);
  let body = options.body;
  if (body !== undefined && !(body instanceof Uint8Array) && typeof body !== 'string') {
    headers['content-type'] = 'application/json';
    body = JSON.stringify(body);
  }
  Object.assign(headers, options.headers || {});
  const response = await fetch(base + pathName, { method, headers, body });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status, json, text, headers: response.headers };
}

async function waitFor(check, timeoutMs = 5000) {
  const started = Date.now();
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() - started > timeoutMs) throw new Error('waitFor timeout');
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

function encodeClientFrame(text) {
  const data = Buffer.from(text, 'utf8');
  const mask = crypto.randomBytes(4);
  let header;
  if (data.length < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x81;
    header[1] = 0x80 | data.length;
  } else if (data.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(data.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(data.length), 2);
  }
  const masked = Buffer.from(data);
  for (let index = 0; index < masked.length; index += 1) masked[index] ^= mask[index % 4];
  return Buffer.concat([header, mask, masked]);
}

function createClientFrameParser(onMessage) {
  let buffer = Buffer.alloc(0);
  return (chunk) => {
    buffer = buffer.length === 0 ? Buffer.from(chunk) : Buffer.concat([buffer, chunk]);
    for (;;) {
      if (buffer.length < 2) return;
      const first = buffer[0];
      const second = buffer[1];
      const opcode = first & 0x0f;
      let length = second & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (buffer.length < 4) return;
        length = buffer.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (buffer.length < 10) return;
        length = buffer.readUInt32BE(2) * 2 ** 32 + buffer.readUInt32BE(6);
        offset = 10;
      }
      if ((second & 0x80) !== 0) {
        if (buffer.length < offset + 4) return;
        offset += 4;
      }
      if (buffer.length < offset + length) return;
      const data = Buffer.from(buffer.subarray(offset, offset + length));
      buffer = buffer.subarray(offset + length);
      if (opcode === 0x1) onMessage(data.toString('utf8'));
      if (opcode === 0x8) {
        onMessage(JSON.stringify({ type: 'ws-close' }));
        return;
      }
    }
  };
}

function connectWebSocket(url, token) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const key = crypto.randomBytes(16).toString('base64');
    const req = http.request({
      hostname: target.hostname,
      port: target.port,
      path: target.pathname + target.search,
      method: 'GET',
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': key,
        Authorization: 'Bearer ' + token,
      },
    });
    req.on('upgrade', (response, socket, head) => {
      const messages = [];
      const waiters = [];
      const deliver = (message) => {
        for (let index = 0; index < waiters.length; index += 1) {
          if (waiters[index].predicate(message)) {
            const waiter = waiters.splice(index, 1)[0];
            clearTimeout(waiter.timer);
            waiter.resolve(message);
            return;
          }
        }
        messages.push(message);
      };
      const parser = createClientFrameParser((text) => {
        let message;
        try {
          message = JSON.parse(text);
        } catch {
          message = { type: 'raw', text };
        }
        deliver(message);
      });
      socket.on('data', parser);
      if (head && head.length > 0) parser(head);
      resolve({
        socket,
        messages,
        waitFor(predicate, timeoutMs = 8000) {
          const existing = messages.find(predicate);
          if (existing) return Promise.resolve(existing);
          return new Promise((resolveWait, rejectWait) => {
            const timer = setTimeout(() => rejectWait(new Error('websocket wait timeout')), timeoutMs);
            waiters.push({ predicate, resolve: resolveWait, timer });
          });
        },
        send(payload) {
          socket.write(encodeClientFrame(JSON.stringify(payload)));
        },
        close() {
          socket.destroy();
        },
      });
    });
    req.on('response', (response) => {
      response.resume();
      reject(new Error('websocket upgrade rejected with ' + response.statusCode));
    });
    req.on('error', reject);
    req.end();
  });
}

test('GET /health requires the Bearer token and reports node facts', async (t) => {
  const { base } = await createHarness(t);

  const anonymous = await request(base, 'GET', '/health', { token: null });
  assert.equal(anonymous.status, 401);
  assert.equal(anonymous.json.ok, false);
  assert.equal(anonymous.json.code, 'AUTH_REQUIRED');

  const wrong = await request(base, 'GET', '/health', { token: 'wrong-token' });
  assert.equal(wrong.status, 401);

  const ping = await request(base, 'GET', '/ping', { token: null });
  assert.equal(ping.status, 200);
  assert.equal(ping.json.ok, true);

  const health = await request(base, 'GET', '/health');
  assert.equal(health.status, 200);
  assert.equal(health.json.ok, true);
  assert.equal(health.json.nodeVersion, process.version);
  assert.equal(typeof health.json.pythonVersion, 'string');
  assert.equal(typeof health.json.cpuPercent, 'number');
  assert.equal(typeof health.json.memUsedMb, 'number');
  assert.equal(typeof health.json.memTotalMb, 'number');
  assert.equal(typeof health.json.diskFreeMb, 'number');
  assert.deepEqual(health.json.packs, ['core']);
  assert.equal(health.json.runningJobs, 0);
  assert.equal(health.json.outboxCount, 0);
});

test('POST /exec streams stdout, stderr and exit frames over WebSocket', async (t) => {
  const { base, port } = await createHarness(t);
  const runId = 'run-stream-' + Date.now();
  const started = await request(base, 'POST', '/exec', {
    body: {
      runId,
      kind: 'node',
      code: "console.log('stdout-line'); console.error('stderr-line');",
      cwd: '~/muse-workspace',
      timeoutSec: 30,
    },
  });
  assert.equal(started.status, 200);
  assert.equal(started.json.ok, true);
  assert.equal(started.json.runId, runId);
  assert.equal(started.json.status, 'running');

  const ws = await connectWebSocket('ws://127.0.0.1:' + port + '/exec/' + encodeURIComponent(runId), TOKEN);
  t.after(() => ws.close());
  const stdoutFrame = await ws.waitFor((message) => message.type === 'stdout' && String(message.text).includes('stdout-line'));
  assert.match(stdoutFrame.text, /stdout-line/);
  const stderrFrame = await ws.waitFor((message) => message.type === 'stderr' && String(message.text).includes('stderr-line'));
  assert.match(stderrFrame.text, /stderr-line/);
  const exitFrame = await ws.waitFor((message) => message.type === 'exit');
  assert.equal(exitFrame.exitCode, 0);
});

test('WS /exec accepts a cancel message for a running job', async (t) => {
  const { base, port } = await createHarness(t);
  const runId = 'run-cancel-' + Date.now();
  const started = await request(base, 'POST', '/exec', {
    body: {
      runId,
      kind: 'node',
      code: 'setInterval(() => {}, 1000);',
      cwd: '~/muse-workspace',
      timeoutSec: 60,
    },
  });
  assert.equal(started.status, 200);
  assert.equal(started.json.ok, true);

  const ws = await connectWebSocket('ws://127.0.0.1:' + port + '/exec/' + encodeURIComponent(runId), TOKEN);
  t.after(() => ws.close());
  await new Promise((resolve) => setTimeout(resolve, 100));
  ws.send({ type: 'cancel' });
  const cancelled = await ws.waitFor((message) => message.type === 'stderr' && String(message.text).includes('cancelled'));
  assert.match(cancelled.text, /cancelled/);
  const exitFrame = await ws.waitFor((message) => message.type === 'exit');
  assert.notEqual(exitFrame.exitCode, 0);
});

test('POST /env/ensure returns APPROVAL_REQUIRED for apt without allowSystem', async (t) => {
  const { base } = await createHarness(t);
  const response = await request(base, 'POST', '/env/ensure', {
    body: { ecosystem: 'apt', packages: ['fonts-noto-cjk'], allowSystem: false },
  });
  assert.equal(response.status, 200);
  assert.equal(response.json.ok, false);
  assert.equal(response.json.code, 'APPROVAL_REQUIRED');
  assert.equal(response.json.message, '需要用户审批系统级安装');
});

test('GET /files blocks ../ traversal outside the workspace', async (t) => {
  const { base } = await createHarness(t);
  const traversal = await request(base, 'GET', '/files?path=' + encodeURIComponent('../../etc/passwd'));
  assert.equal(traversal.status, 403);
  assert.equal(traversal.json.ok, false);
  assert.equal(traversal.json.code, 'PATH_OUT_OF_WORKSPACE');

  const encodedTraversal = await request(base, 'GET', '/files?path=' + encodeURIComponent('..\\..\\Windows\\win.ini'));
  assert.equal(encodedTraversal.status, 403);
  assert.equal(encodedTraversal.json.code, 'PATH_OUT_OF_WORKSPACE');

  const absoluteOutside = await request(base, 'GET', '/files?path=' + encodeURIComponent(path.resolve(os.tmpdir(), 'muse-outside.txt')));
  assert.equal(absoluteOutside.status, 403);
  assert.equal(absoluteOutside.json.code, 'PATH_OUT_OF_WORKSPACE');
});

test('PUT and GET /files round-trip inside the workspace', async (t) => {
  const { base } = await createHarness(t);
  const put = await request(base, 'PUT', '/files?path=' + encodeURIComponent('notes/hello.txt'), {
    body: 'hello muse',
    headers: { 'content-type': 'text/plain' },
  });
  assert.equal(put.status, 200);
  assert.equal(put.json.ok, true);
  assert.equal(put.json.bytes, 10);

  const get = await request(base, 'GET', '/files?path=' + encodeURIComponent('notes/hello.txt'));
  assert.equal(get.status, 200);
  assert.equal(get.text, 'hello muse');
});

test('outbox records an exec result and ack consumes it', async (t) => {
  const { base } = await createHarness(t);
  const runId = 'run-outbox-' + Date.now();
  const started = await request(base, 'POST', '/exec', {
    body: {
      runId,
      kind: 'node',
      code: "console.log('outbox-event-line');",
      cwd: '~/muse-workspace',
      timeoutSec: 30,
    },
  });
  assert.equal(started.status, 200);

  const event = await waitFor(async () => {
    const list = await request(base, 'GET', '/outbox?since=0');
    assert.equal(list.status, 200);
    assert.equal(list.json.ok, true);
    return list.json.events.find((item) => item.type === 'exec' && item.runId === runId) || null;
  });
  assert.equal(event.exitCode, 0);
  assert.equal(event.status, 'completed');
  assert.match(event.fingerprint, /^sha256:[0-9a-f]{64}$/);
  assert.match(event.outputTail, /outbox-event-line/);

  const ack = await request(base, 'POST', '/outbox/ack', { body: { eventIds: [event.eventId] } });
  assert.equal(ack.status, 200);
  assert.equal(ack.json.ok, true);
  assert.equal(ack.json.acked, 1);

  const empty = await request(base, 'GET', '/outbox?since=0');
  assert.equal(empty.json.count, 0);
});

test('watcher upsert runs the script and only emits an outbox event when the fingerprint changes', async (t) => {
  const { app, base } = await createHarness(t);
  const upsert = await request(base, 'POST', '/watchers/upsert', {
    body: {
      watcherId: 'watcher-test',
      intervalMinutes: 60,
      script: "console.log('watcher-value');",
      kind: 'node',
      webhookUrl: '',
    },
  });
  assert.equal(upsert.status, 200);
  assert.equal(upsert.json.ok, true);

  const first = await app.runWatcher('watcher-test');
  assert.ok(first);
  const firstEvent = await waitFor(async () => {
    const list = await request(base, 'GET', '/outbox?since=0');
    return list.json.events.find((item) => item.type === 'watcher' && item.watcherId === 'watcher-test') || null;
  });
  assert.match(firstEvent.fingerprint, /^sha256:[0-9a-f]{64}$/);
  assert.match(firstEvent.output, /watcher-value/);

  const ack = await request(base, 'POST', '/outbox/ack', { body: { eventIds: [firstEvent.eventId] } });
  assert.equal(ack.json.acked, 1);

  await app.runWatcher('watcher-test');
  const secondList = await request(base, 'GET', '/outbox?since=0');
  assert.equal(secondList.json.count, 0);

  const deleted = await request(base, 'DELETE', '/watchers/watcher-test');
  assert.equal(deleted.status, 200);
  assert.equal(deleted.json.ok, true);
});
