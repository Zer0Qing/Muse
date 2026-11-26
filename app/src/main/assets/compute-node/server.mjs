#!/usr/bin/env node
/**
 * Muse compute node control plane.
 *
 * Runs on a Linux host under the current SSH user's HOME. The process owns the
 * HTTP/WebSocket control surface described by the Wave 1 protocol contract,
 * keeps all state under ~/.muse-node and confines file operations to
 * ~/muse-workspace.
 *
 * Node >= 20 is required. The implementation intentionally depends only on
 * Node built-ins so that bootstrap can push a single self-contained file.
 */

import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const SERVER_VERSION = '1.0.0';
const DEFAULT_PORT = 18765;
const DEFAULT_TIMEOUT_SEC = 120;
const MAX_TIMEOUT_SEC = 3600;
const MAX_FILE_BYTES = 10 * 1024 * 1024;
const MAX_BODY_BYTES = 12 * 1024 * 1024;
const MAX_JOB_BUFFER_BYTES = 2 * 1024 * 1024;
const MAX_OUTBOX_TAIL_CHARS = 16 * 1024;
const DEFAULT_BROWSER_IDLE_MS = 5 * 60 * 1000;
const MAX_BROWSER_FRAME_BYTES = 80 * 1024;
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const CONTENT_TYPES = new Map([
  ['.json', 'application/json; charset=utf-8'],
  ['.txt', 'text/plain; charset=utf-8'],
  ['.md', 'text/markdown; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.mjs', 'text/javascript; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.png', 'image/png'],
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.pdf', 'application/pdf'],
]);

function sha256Hex(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function fingerprintOf(value) {
  return 'sha256:' + sha256Hex(value);
}

function round1(value) {
  return Math.round(value * 10) / 10;
}

function toInt(value, fallback, min, max) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function httpError(status, code, message) {
  const error = new Error(message);
  error.status = status;
  error.code = code;
  return error;
}

function isInside(root, target) {
  const relative = path.relative(root, target);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function expandHome(value, homeDir) {
  if (value === '~') return homeDir;
  if (value.startsWith('~/')) return path.join(homeDir, value.slice(2));
  return value;
}

function silentLogger() {
  return { info() {}, warn() {}, error() {} };
}

function createRotatingLogger(logFile, maxBytes = 10 * 1024 * 1024) {
  const write = (level, message) => {
    const line = '[' + new Date().toISOString() + '] [' + level + '] ' + message + '\n';
    try {
      fs.mkdirSync(path.dirname(logFile), { recursive: true });
      let size = 0;
      try {
        size = fs.statSync(logFile).size;
      } catch {
        size = 0;
      }
      if (size > maxBytes) {
        try {
          fs.renameSync(logFile, logFile + '.1');
        } catch {
          // A concurrent rotate already moved the file; keep writing to a fresh one.
        }
      }
      fs.appendFileSync(logFile, line, { encoding: 'utf8', mode: 0o600 });
    } catch {
      // Logging failure must never take the daemon down.
    }
  };
  return {
    info(message) { write('info', message); },
    warn(message) { write('warn', message); },
    error(message) { write('error', message); },
  };
}

function sendJson(res, status, payload) {
  if (res.writableEnded) return;
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.length),
    'cache-control': 'no-store',
  });
  res.end(body);
}

function sendError(res, status, code, message) {
  sendJson(res, status, { ok: false, code, message });
}

function readRawBody(req, limit = MAX_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let settled = false;
    req.on('data', (chunk) => {
      if (settled) return;
      total += chunk.length;
      if (total > limit) {
        settled = true;
        req.resume();
        reject(httpError(413, 'BODY_TOO_LARGE', '请求体超过大小限制'));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    });
  });
}

async function readJsonBody(req, limit = MAX_BODY_BYTES) {
  const raw = await readRawBody(req, limit);
  if (raw.length === 0) return {};
  try {
    const parsed = JSON.parse(raw.toString('utf8'));
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw httpError(400, 'INVALID_JSON', '请求体必须是 JSON 对象');
    }
    return parsed;
  } catch (error) {
    if (error && error.status) throw error;
    throw httpError(400, 'INVALID_JSON', '请求体不是合法 JSON');
  }
}

function wsAccept(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

function encodeWsFrame(payload, opcode = 0x1) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8');
  const length = data.length;
  let header;
  if (length < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x80 | opcode;
    header[1] = length;
  } else if (length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  return Buffer.concat([header, data]);
}

function createWsFrameParser(onFrame) {
  let buffer = Buffer.alloc(0);
  return (chunk) => {
    buffer = buffer.length === 0 ? Buffer.from(chunk) : Buffer.concat([buffer, chunk]);
    for (;;) {
      if (buffer.length < 2) return;
      const first = buffer[0];
      const second = buffer[1];
      const fin = (first & 0x80) !== 0;
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (buffer.length < 4) return;
        length = buffer.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (buffer.length < 10) return;
        const high = buffer.readUInt32BE(2);
        const low = buffer.readUInt32BE(6);
        length = high * 2 ** 32 + low;
        offset = 10;
      }
      if (length > MAX_BODY_BYTES) {
        onFrame({ fin: true, opcode: 0x8, data: Buffer.alloc(0), oversized: true });
        buffer = Buffer.alloc(0);
        return;
      }
      let mask = null;
      if (masked) {
        if (buffer.length < offset + 4) return;
        mask = buffer.subarray(offset, offset + 4);
        offset += 4;
      }
      if (buffer.length < offset + length) return;
      const data = Buffer.from(buffer.subarray(offset, offset + length));
      if (mask) {
        for (let index = 0; index < data.length; index += 1) {
          data[index] ^= mask[index % 4];
        }
      }
      buffer = buffer.subarray(offset + length);
      onFrame({ fin, opcode, data, oversized: false });
    }
  };
}

function sendWsText(socket, payload) {
  if (!socket || socket.destroyed || !socket.writable) return false;
  try {
    socket.write(encodeWsFrame(JSON.stringify(payload), 0x1));
    return true;
  } catch {
    return false;
  }
}

function sendWsClose(socket, code = 1000) {
  if (!socket || socket.destroyed || !socket.writable) return;
  try {
    const body = Buffer.alloc(2);
    body.writeUInt16BE(code, 0);
    socket.write(encodeWsFrame(body, 0x8));
  } catch {
    // Socket already gone.
  }
}

function parseBearerToken(header) {
  if (typeof header !== 'string') return null;
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

async function pathExists(target) {
  try {
    await fsp.access(target);
    return true;
  } catch {
    return false;
  }
}

function normalizeOutput(text) {
  return String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim();
}

function truncateTail(text, maxChars) {
  if (text.length <= maxChars) return text;
  return text.slice(text.length - maxChars);
}

class MuseNodeServer {
  constructor(options = {}) {
    this.homeDir = path.resolve(options.homeDir || process.env.MUSE_NODE_HOME || os.homedir());
    this.dataDir = path.resolve(options.dataDir || path.join(this.homeDir, '.muse-node'));
    this.workspaceDir = path.resolve(options.workspaceDir || path.join(this.homeDir, 'muse-workspace'));
    this.outboxDir = path.join(this.dataDir, 'outbox');
    this.watchersDir = path.join(this.dataDir, 'watchers');
    this.toolsDir = path.join(this.dataDir, 'tools');
    this.venvDir = path.join(this.dataDir, 'venv');
    this.port = options.port ?? DEFAULT_PORT;
    this.bind = options.bind || '127.0.0.1';
    this.tlsEnabled = options.tls === true;
    this.certFile = options.certFile || path.join(this.dataDir, 'certs', 'cert.pem');
    this.keyFile = options.keyFile || path.join(this.dataDir, 'certs', 'key.pem');
    this.token = typeof options.token === 'string' && options.token.length > 0 ? options.token : null;
    this.packs = Array.isArray(options.packs) ? [...options.packs] : [];
    this.logger = options.logger || silentLogger();
    this.browserIdleMs = options.browserIdleMs ?? DEFAULT_BROWSER_IDLE_MS;
    this.jobRetentionMs = options.jobRetentionMs ?? 5 * 60 * 1000;
    this.maxBodyBytes = options.maxBodyBytes ?? MAX_BODY_BYTES;

    this.httpServer = null;
    this.workspaceReal = this.workspaceDir;
    this.jobs = new Map();
    this.watcherTimers = new Map();
    this.watcherRunning = new Set();
    this.watcherConfigs = new Map();
    this.browserSessions = new Map();
    this.browser = null;
    this.playwrightModule = undefined;
    this.browserSweepTimer = null;
    this.stopped = false;
    this.cpuSample = null;
    this.pythonVersionCache = { value: '', at: 0 };
  }

  async init() {
    for (const dir of [this.dataDir, this.outboxDir, this.watchersDir, this.toolsDir, path.join(this.dataDir, 'bin'), path.join(this.dataDir, 'logs'), path.join(this.dataDir, 'certs'), this.workspaceDir]) {
      await fsp.mkdir(dir, { recursive: true, mode: 0o700 });
    }
    this.workspaceReal = await fsp.realpath(this.workspaceDir);
    if (!this.token) {
      const tokenFile = path.join(this.dataDir, 'auth.token');
      try {
        const saved = (await fsp.readFile(tokenFile, 'utf8')).trim();
        if (saved.length > 0) this.token = saved;
      } catch {
        // Generated below.
      }
      if (!this.token) {
        this.token = crypto.randomBytes(32).toString('hex');
        await fsp.writeFile(tokenFile, this.token, { mode: 0o600 });
      }
    }
    if (this.packs.length === 0) {
      try {
        const config = JSON.parse(await fsp.readFile(path.join(this.dataDir, 'node.json'), 'utf8'));
        if (Array.isArray(config.packs)) this.packs = config.packs.filter((item) => typeof item === 'string');
      } catch {
        // No node.json yet; core is the documented default.
      }
      if (this.packs.length === 0) this.packs = ['core'];
    }
    await this.loadWatchers();
    this.browserSweepTimer = setInterval(() => {
      this.sweepIdleBrowserSessions().catch((error) => this.logger.warn('browser sweep failed: ' + error.message));
    }, Math.min(30000, Math.max(1000, Math.floor(this.browserIdleMs / 2))));
    if (typeof this.browserSweepTimer.unref === 'function') this.browserSweepTimer.unref();
  }

  isAuthorized(req) {
    const supplied = parseBearerToken(req.headers.authorization);
    if (!supplied || !this.token) return false;
    const left = Buffer.from(supplied);
    const right = Buffer.from(this.token);
    return left.length === right.length && crypto.timingSafeEqual(left, right);
  }

  async start(portOverride) {
    await this.init();
    if (portOverride && typeof portOverride === 'object') portOverride = portOverride.port ?? 0;
    if (this.bind !== '127.0.0.1' && !this.tlsEnabled) {
      throw new Error('non-loopback bind requires TLS (DIRECT_TLS mode)');
    }
    const handler = (req, res) => {
      this.handleRequest(req, res).catch((error) => {
        this.logger.error('request failed: ' + error.message);
        if (!res.headersSent) sendError(res, 500, 'INTERNAL_ERROR', '服务内部错误');
        else if (!res.writableEnded) res.end();
      });
    };

    if (this.tlsEnabled) {
      const options = {
        cert: await fsp.readFile(this.certFile),
        key: await fsp.readFile(this.keyFile),
        minVersion: 'TLSv1.2',
      };
      this.httpServer = https.createServer(options, handler);
    } else {
      this.httpServer = http.createServer(handler);
    }

    this.httpServer.on('upgrade', (req, socket, head) => {
      this.handleUpgrade(req, socket, head).catch((error) => {
        this.logger.warn('websocket upgrade failed: ' + error.message);
        try {
          socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
        } catch {
          // Socket is already gone.
        }
        try {
          socket.destroy();
        } catch {
          // Socket is already gone.
        }
      });
    });

    const listenPort = portOverride ?? this.port;
    await new Promise((resolve, reject) => {
      const onError = (error) => {
        this.httpServer.off('listening', onListening);
        reject(error);
      };
      const onListening = () => {
        this.httpServer.off('error', onError);
        resolve();
      };
      this.httpServer.once('error', onError);
      this.httpServer.once('listening', onListening);
      this.httpServer.listen(listenPort, this.bind);
    });
    return this.address();
  }

  address() {
    const address = this.httpServer?.address();
    if (!address || typeof address === 'string') return { port: this.port, bind: this.bind };
    return { port: address.port, bind: address.address };
  }

  async stop() {
    this.stopped = true;
    if (this.browserSweepTimer) clearInterval(this.browserSweepTimer);
    for (const timer of this.watcherTimers.values()) clearInterval(timer);
    this.watcherTimers.clear();
    for (const job of this.jobs.values()) {
      if (job.timer) clearTimeout(job.timer);
      if (job.retentionTimer) clearTimeout(job.retentionTimer);
      if (job.child && job.status === 'running') {
        try { job.child.kill('SIGKILL'); } catch { /* already gone */ }
      }
      for (const socket of job.subscribers) {
        sendWsClose(socket);
        socket.destroy();
      }
    }
    this.jobs.clear();
    await this.closeBrowser();
    for (const session of [...this.browserSessions.values()]) {
      await this.closeBrowserSession(session, 'server_stop').catch(() => {});
    }
    if (this.httpServer) {
      await new Promise((resolve) => {
        try {
          this.httpServer.close(() => resolve());
        } catch {
          resolve();
        }
        this.httpServer = null;
      });
    }
  }

  async handleRequest(req, res) {
    const method = req.method || 'GET';
    let url;
    try {
      url = new URL(req.url || '/', 'http://localhost');
    } catch {
      sendError(res, 400, 'BAD_REQUEST', '请求地址不合法');
      return;
    }

    if (method === 'GET' && url.pathname === '/ping') {
      sendJson(res, 200, { ok: true, service: 'muse-node', version: SERVER_VERSION, time: Date.now() });
      return;
    }
    if (!this.isAuthorized(req)) {
      sendError(res, 401, 'AUTH_REQUIRED', '缺少或错误的 Bearer Token');
      return;
    }

    try {
      if (method === 'GET' && url.pathname === '/health') {
        await this.handleHealth(req, res);
        return;
      }
      if (method === 'POST' && url.pathname === '/exec') {
        await this.handleExecStart(req, res);
        return;
      }
      if (method === 'POST' && url.pathname === '/env/ensure') {
        await this.handleEnvEnsure(req, res);
        return;
      }
      if (url.pathname === '/files' && (method === 'GET' || method === 'PUT')) {
        await this.handleFiles(req, res, method);
        return;
      }
      if (method === 'POST' && url.pathname === '/watchers/upsert') {
        await this.handleWatcherUpsert(req, res);
        return;
      }
      const watcherDelete = /^\/watchers\/([^/]+)$/.exec(url.pathname);
      if (method === 'DELETE' && watcherDelete) {
        await this.handleWatcherDelete(res, decodeURIComponent(watcherDelete[1]));
        return;
      }
      if (method === 'GET' && url.pathname === '/outbox') {
        await this.handleOutboxList(req, res, url);
        return;
      }
      if (method === 'POST' && url.pathname === '/outbox/ack') {
        await this.handleOutboxAck(req, res);
        return;
      }
      if (method === 'POST' && url.pathname === '/browser/start') {
        await this.handleBrowserStart(req, res);
        return;
      }
      sendError(res, 404, 'NOT_FOUND', '接口不存在');
    } catch (error) {
      if (error && error.status) {
        sendError(res, error.status, error.code || 'BAD_REQUEST', error.message);
        return;
      }
      this.logger.error('handler failed: ' + (error?.stack || error?.message || String(error)));
      sendError(res, 500, 'INTERNAL_ERROR', '服务内部错误');
    }
  }

  async handleHealth(req, res) {
    const [pythonVersion, diskFreeMb, outboxCount, packs] = await Promise.all([
      this.resolvePythonVersion(),
      this.diskFreeMb(),
      this.countOutbox(),
      this.currentPacks(),
    ]);
    let runningJobs = 0;
    for (const job of this.jobs.values()) {
      if (job.status === 'running') runningJobs += 1;
    }
    sendJson(res, 200, {
      ok: true,
      nodeVersion: process.version,
      pythonVersion,
      cpuPercent: this.cpuPercent(),
      memUsedMb: round1((os.totalmem() - os.freemem()) / 1048576),
      memTotalMb: Math.round(os.totalmem() / 1048576),
      diskFreeMb,
      packs,
      runningJobs,
      outboxCount,
    });
  }

  async currentPacks() {
    try {
      const config = JSON.parse(await fsp.readFile(path.join(this.dataDir, 'node.json'), 'utf8'));
      if (Array.isArray(config.packs)) {
        return config.packs.filter((item) => typeof item === 'string');
      }
    } catch {
      // Fall back to the in-memory pack list when node.json is absent or unreadable.
    }
    return this.packs;
  }

  cpuPercent() {
    const cpus = os.cpus();
    let idle = 0;
    let total = 0;
    for (const cpu of cpus) {
      const times = cpu.times;
      for (const value of Object.values(times)) total += value;
      idle += times.idle;
    }
    const previous = this.cpuSample;
    this.cpuSample = { idle, total };
    if (!previous) return 0;
    const totalDelta = total - previous.total;
    const idleDelta = idle - previous.idle;
    if (totalDelta <= 0) return 0;
    return round1(Math.max(0, Math.min(100, (1 - idleDelta / totalDelta) * 100)));
  }

  async diskFreeMb() {
    try {
      const stats = await fsp.statfs(this.workspaceReal);
      return Math.floor((Number(stats.bavail) * Number(stats.bsize)) / 1048576);
    } catch {
      return 0;
    }
  }

  async countOutbox() {
    try {
      const entries = await fsp.readdir(this.outboxDir);
      return entries.filter((name) => name.endsWith('.json')).length;
    } catch {
      return 0;
    }
  }

  pythonCandidates() {
    const candidates = [];
    if (process.platform === 'win32') {
      candidates.push(path.join(this.venvDir, 'Scripts', 'python.exe'));
      candidates.push('python');
      candidates.push('python3');
    } else {
      candidates.push(path.join(this.venvDir, 'bin', 'python'));
      candidates.push('python3');
      candidates.push('python');
    }
    return candidates;
  }

  async resolvePythonVersion() {
    const now = Date.now();
    if (now - this.pythonVersionCache.at < 60000) return this.pythonVersionCache.value;
    let value = '';
    for (const candidate of this.pythonCandidates()) {
      if (candidate.includes(path.sep) && !(await pathExists(candidate))) continue;
      const result = await new Promise((resolve) => {
        let child;
        try {
          child = spawn(candidate, ['--version'], { windowsHide: true });
        } catch {
          resolve(null);
          return;
        }
        let output = '';
        child.stdout?.on('data', (chunk) => { output += chunk.toString('utf8'); });
        child.stderr?.on('data', (chunk) => { output += chunk.toString('utf8'); });
        child.on('error', () => resolve(null));
        child.on('close', (code) => resolve(code === 0 ? output.trim() : null));
      });
      if (result) {
        const match = /(\d+\.\d+(?:\.\d+)?)/.exec(result);
        value = match ? match[1] : result;
        break;
      }
    }
    this.pythonVersionCache = { value, at: now };
    return value;
  }

  buildChildEnvironment() {
    const env = { ...process.env, HOME: this.homeDir, MUSE_NODE: '1', MUSE_WORKSPACE: this.workspaceReal };
    env.PATH = [path.join(this.dataDir, 'runtime', 'current', 'bin'), path.join(this.venvDir, 'bin'), path.join(this.toolsDir, 'node_modules', '.bin'), env.PATH || ''].filter(Boolean).join(path.delimiter);
    return env;
  }

  async resolveWorkspacePath(requested, options = {}) {
    if (typeof requested !== 'string' || requested.length === 0) {
      throw httpError(400, 'INVALID_PATH', 'path 参数不能为空');
    }
    if (requested.includes('\0')) {
      throw httpError(400, 'INVALID_PATH', 'path 参数包含非法字符');
    }
    const expanded = expandHome(requested, this.homeDir);
    let candidate;
    if (path.isAbsolute(expanded)) candidate = path.resolve(expanded);
    else candidate = path.resolve(this.workspaceReal, expanded);
    if (!isInside(this.workspaceReal, candidate)) {
      throw httpError(403, 'PATH_OUT_OF_WORKSPACE', '路径超出工作区范围');
    }

    let probe = candidate;
    const missingParts = [];
    while (!(await pathExists(probe))) {
      const parent = path.dirname(probe);
      if (parent === probe) break;
      missingParts.unshift(path.basename(probe));
      probe = parent;
    }
    let realProbe;
    try {
      realProbe = await fsp.realpath(probe);
    } catch {
      throw httpError(400, 'INVALID_PATH', '路径无法解析');
    }
    if (!isInside(this.workspaceReal, realProbe)) {
      throw httpError(403, 'PATH_OUT_OF_WORKSPACE', '路径超出工作区范围');
    }
    const resolved = path.join(realProbe, ...missingParts);
    if (!isInside(this.workspaceReal, resolved)) {
      throw httpError(403, 'PATH_OUT_OF_WORKSPACE', '路径超出工作区范围');
    }
    if (options.mustExist) {
      let stats;
      try {
        stats = await fsp.stat(resolved);
      } catch {
        throw httpError(404, 'FILE_NOT_FOUND', '文件不存在');
      }
      if (options.expectFile && !stats.isFile()) throw httpError(400, 'NOT_A_FILE', '目标不是文件');
      if (options.expectDirectory && !stats.isDirectory()) throw httpError(400, 'NOT_A_DIRECTORY', '目标不是目录');
    }
    return resolved;
  }

  async handleFiles(req, res, method) {
    const url = new URL(req.url || '/', 'http://localhost');
    const requested = url.searchParams.get('path');
    if (method === 'GET') {
      const target = await this.resolveWorkspacePath(requested, { mustExist: true, expectFile: true });
      const stats = await fsp.stat(target);
      if (stats.size > MAX_FILE_BYTES) throw httpError(413, 'FILE_TOO_LARGE', '文件超过 10MB 限制');
      const data = await fsp.readFile(target);
      const extension = path.extname(target).toLowerCase();
      res.writeHead(200, {
        'content-type': CONTENT_TYPES.get(extension) || 'application/octet-stream',
        'content-length': String(data.length),
        'cache-control': 'no-store',
        'x-muse-path': encodeURIComponent(requested),
      });
      res.end(data);
      return;
    }

    const body = await readRawBody(req, MAX_FILE_BYTES);
    const target = await this.resolveWorkspacePath(requested);
    await fsp.mkdir(path.dirname(target), { recursive: true, mode: 0o700 });
    const temporary = target + '.muse-tmp-' + process.pid + '-' + crypto.randomBytes(4).toString('hex');
    await fsp.writeFile(temporary, body, { mode: 0o600 });
    await fsp.rename(temporary, target);
    sendJson(res, 200, { ok: true, path: requested, bytes: body.length });
  }

  async handleExecStart(req, res) {
    const payload = await readJsonBody(req);
    const runId = payload.runId;
    if (typeof runId !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(runId)) {
      throw httpError(400, 'INVALID_RUN_ID', 'runId 缺失或格式不合法');
    }
    const kind = payload.kind;
    if (!['shell', 'python', 'node'].includes(kind)) {
      throw httpError(400, 'INVALID_KIND', 'kind 必须是 shell、python 或 node');
    }
    const command = typeof payload.command === 'string' ? payload.command : '';
    const code = typeof payload.code === 'string' ? payload.code : '';
    if (kind === 'shell' && !command.trim()) throw httpError(400, 'INVALID_COMMAND', 'shell 任务必须提供 command');
    if (kind !== 'shell' && !(code.trim() || command.trim())) throw httpError(400, 'INVALID_CODE', 'python/node 任务必须提供 code');

    const existing = this.jobs.get(runId);
    if (existing && existing.status === 'running') throw httpError(409, 'RUN_EXISTS', 'runId 正在执行');

    const cwdRequested = typeof payload.cwd === 'string' && payload.cwd.trim() ? payload.cwd : '~/muse-workspace';
    const cwd = await this.resolveWorkspacePath(cwdRequested, { mustExist: true, expectDirectory: true });
    const timeoutSec = toInt(payload.timeoutSec, DEFAULT_TIMEOUT_SEC, 1, MAX_TIMEOUT_SEC);

    const job = {
      runId,
      kind,
      command,
      code,
      cwd,
      timeoutSec,
      status: 'running',
      createdAt: Date.now(),
      startedAt: Date.now(),
      finishedAt: 0,
      exitCode: null,
      signal: null,
      timedOut: false,
      cancelled: false,
      chunks: [],
      bufferedBytes: 0,
      subscribers: new Set(),
      child: null,
      timer: null,
      retentionTimer: null,
    };
    this.jobs.set(runId, job);
    this.runJob(job).catch((error) => {
      this.logger.error('job ' + runId + ' crashed: ' + error.message);
      this.finishJob(job, null, null).catch(() => {});
    });
    sendJson(res, 200, { ok: true, runId, status: 'running' });
  }

  buildJobCommand(job) {
    if (job.kind === 'shell') {
      if (process.platform === 'win32') {
        return { command: process.env.ComSpec || 'cmd.exe', args: ['/d', '/s', '/c', job.command], shell: false };
      }
      return { command: '/bin/sh', args: ['-c', job.command], shell: false };
    }
    if (job.kind === 'node') {
      return { command: process.execPath, args: ['-e', job.code || job.command], shell: false };
    }
    return { command: this.pythonCandidates()[0].includes(path.sep) && fs.existsSync(this.pythonCandidates()[0]) ? this.pythonCandidates()[0] : (process.platform === 'win32' ? 'python' : 'python3'), args: ['-c', job.code || job.command], shell: false };
  }

  async runJob(job) {
    let spec;
    try {
      spec = this.buildJobCommand(job);
    } catch (error) {
      this.pushChunk(job, 'stderr', Buffer.from('无法启动命令: ' + error.message + '\n', 'utf8'));
      await this.finishJob(job, 127, null);
      return;
    }
    let child;
    try {
      child = spawn(spec.command, spec.args, {
        cwd: job.cwd,
        env: this.buildChildEnvironment(),
        shell: spec.shell,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (error) {
      this.pushChunk(job, 'stderr', Buffer.from('无法启动命令: ' + error.message + '\n', 'utf8'));
      await this.finishJob(job, 127, null);
      return;
    }
    job.child = child;
    child.stdout.on('data', (chunk) => this.pushChunk(job, 'stdout', chunk));
    child.stderr.on('data', (chunk) => this.pushChunk(job, 'stderr', chunk));
    child.on('error', (error) => this.pushChunk(job, 'stderr', Buffer.from(String(error.message) + '\n', 'utf8')));
    child.on('close', (code, signal) => {
      this.finishJob(job, code, signal).catch((error) => this.logger.warn('finish job failed: ' + error.message));
    });
    job.timer = setTimeout(() => {
      if (job.status !== 'running') return;
      job.timedOut = true;
      this.pushChunk(job, 'stderr', Buffer.from('\n[muse-node] timeout after ' + job.timeoutSec + 's\n', 'utf8'));
      try { job.child.kill('SIGTERM'); } catch { /* already gone */ }
      const killTimer = setTimeout(() => {
        try { job.child.kill('SIGKILL'); } catch { /* already gone */ }
      }, 2000);
      if (typeof killTimer.unref === 'function') killTimer.unref();
    }, job.timeoutSec * 1000);
    if (typeof job.timer.unref === 'function') job.timer.unref();
  }

  pushChunk(job, type, buffer) {
    if (job.status !== 'running') return;
    const text = buffer.toString('utf8');
    if (!text) return;
    const frame = { type, text, ts: Date.now() };
    job.chunks.push(frame);
    job.bufferedBytes += Buffer.byteLength(text);
    while (job.bufferedBytes > MAX_JOB_BUFFER_BYTES && job.chunks.length > 1) {
      const removed = job.chunks.shift();
      job.bufferedBytes -= Buffer.byteLength(removed.text);
    }
    for (const socket of job.subscribers) sendWsText(socket, frame);
  }

  async finishJob(job, code, signal) {
    if (job.status !== 'running') return;
    job.status = 'completed';
    job.exitCode = typeof code === 'number' ? code : (signal ? 128 + (os.constants.signals[signal] || 0) : 1);
    job.signal = signal || null;
    job.finishedAt = Date.now();
    if (job.timer) clearTimeout(job.timer);
    const exitFrame = { type: 'exit', text: '', exitCode: job.exitCode, ts: Date.now() };
    job.chunks.push(exitFrame);
    job.bufferedBytes += 0;
    for (const socket of job.subscribers) {
      sendWsText(socket, exitFrame);
      sendWsClose(socket);
      try { socket.end(); } catch { /* already gone */ }
    }
    job.subscribers.clear();
    try {
      const outputTail = truncateTail(job.chunks.filter((frame) => frame.type === 'stdout' || frame.type === 'stderr').map((frame) => frame.text).join(''), MAX_OUTBOX_TAIL_CHARS);
      await this.writeOutboxEvent({
        type: 'exec',
        runId: job.runId,
        status: job.exitCode === 0 ? 'completed' : 'failed',
        exitCode: job.exitCode,
        timedOut: job.timedOut,
        cancelled: job.cancelled,
        startedAt: job.startedAt,
        finishedAt: job.finishedAt,
        outputTail,
        fingerprint: fingerprintOf(outputTail),
      });
    } catch (error) {
      this.logger.warn('outbox write failed for ' + job.runId + ': ' + error.message);
    }
    job.retentionTimer = setTimeout(() => {
      if (this.jobs.get(job.runId) === job) this.jobs.delete(job.runId);
    }, this.jobRetentionMs);
    if (typeof job.retentionTimer.unref === 'function') job.retentionTimer.unref();
  }

  cancelJob(job) {
    if (!job || job.status !== 'running') return;
    job.cancelled = true;
    this.pushChunk(job, 'stderr', Buffer.from('\n[muse-node] cancelled by client\n', 'utf8'));
    try { job.child?.kill('SIGTERM'); } catch { /* already gone */ }
    const killTimer = setTimeout(() => {
      try { job.child?.kill('SIGKILL'); } catch { /* already gone */ }
    }, 1500);
    if (typeof killTimer.unref === 'function') killTimer.unref();
  }

  async writeOutboxEvent(event) {
    const eventId = typeof event.eventId === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(event.eventId) ? event.eventId : crypto.randomUUID();
    const record = { eventId, createdAt: event.createdAt || Date.now(), ...event };
    record.eventId = eventId;
    const target = path.join(this.outboxDir, eventId + '.json');
    const temporary = target + '.tmp';
    await fsp.writeFile(temporary, JSON.stringify(record, null, 0), { mode: 0o600 });
    await fsp.rename(temporary, target);
    return record;
  }

  async handleEnvEnsure(req, res) {
    const payload = await readJsonBody(req);
    const ecosystem = payload.ecosystem;
    if (!['pip', 'npm', 'apt'].includes(ecosystem)) {
      throw httpError(400, 'INVALID_ECOSYSTEM', 'ecosystem 必须是 pip、npm 或 apt');
    }
    const packages = Array.isArray(payload.packages) ? payload.packages : [];
    if (packages.length === 0 || packages.some((item) => typeof item !== 'string' || item.trim().length === 0)) {
      throw httpError(400, 'INVALID_PACKAGES', 'packages 必须是非空字符串数组');
    }
    for (const item of packages) {
      if (!/^[A-Za-z0-9@._/+^~:<>=*!\[\]-]{1,128}$/.test(item)) throw httpError(400, 'INVALID_PACKAGE_NAME', '包名包含非法字符');
    }
    const allowSystem = payload.allowSystem === true;
    if (ecosystem === 'apt' && !allowSystem) {
      sendJson(res, 200, { ok: false, code: 'APPROVAL_REQUIRED', message: '需要用户审批系统级安装' });
      return;
    }

    let command;
    let args;
    if (ecosystem === 'pip') {
      const python = this.pythonCandidates()[0].includes(path.sep) && fs.existsSync(this.pythonCandidates()[0]) ? this.pythonCandidates()[0] : 'python3';
      command = python;
      args = ['-m', 'pip', 'install', '--', ...packages];
    } else if (ecosystem === 'npm') {
      command = process.platform === 'win32' ? 'npm.cmd' : 'npm';
      args = ['install', '--prefix', this.toolsDir, '--', ...packages];
    } else {
      command = 'sudo';
      args = ['-n', 'apt-get', 'install', '-y', '--', ...packages];
    }
    const result = await this.runCommandCapture(command, args, { timeoutMs: 5 * 60 * 1000 });
    sendJson(res, 200, {
      ok: result.code === 0,
      ecosystem,
      packages,
      exitCode: result.code,
      outputTail: truncateTail(result.output, 8192),
      ...(result.code === 0 ? {} : { code: 'INSTALL_FAILED', message: '依赖安装失败' }),
    });
  }

  runCommandCapture(command, args, options = {}) {
    return new Promise((resolve) => {
      let output = '';
      let child;
      try {
        child = spawn(command, args, { cwd: options.cwd || undefined, env: this.buildChildEnvironment(), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      } catch (error) {
        resolve({ code: 127, output: String(error.message) });
        return;
      }
      const timer = setTimeout(() => {
        try { child.kill('SIGKILL'); } catch { /* already gone */ }
      }, options.timeoutMs || 120000);
      if (typeof timer.unref === 'function') timer.unref();
      child.stdout.on('data', (chunk) => { output += chunk.toString('utf8'); });
      child.stderr.on('data', (chunk) => { output += chunk.toString('utf8'); });
      child.on('error', (error) => {
        clearTimeout(timer);
        resolve({ code: 127, output: String(error.message) });
      });
      child.on('close', (code) => {
        clearTimeout(timer);
        resolve({ code: typeof code === 'number' ? code : 1, output });
      });
    });
  }

  async loadWatchers() {
    let entries = [];
    try {
      entries = await fsp.readdir(this.watchersDir);
    } catch {
      return;
    }
    for (const name of entries) {
      if (!name.endsWith('.json') || name.startsWith('.')) continue;
      try {
        const config = JSON.parse(await fsp.readFile(path.join(this.watchersDir, name), 'utf8'));
        if (config && typeof config.watcherId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(config.watcherId)) {
          this.watcherConfigs.set(config.watcherId, config);
          this.scheduleWatcher(config.watcherId, config.intervalMinutes);
        }
      } catch (error) {
        this.logger.warn('skipping invalid watcher file ' + name + ': ' + error.message);
      }
    }
  }

  scheduleWatcher(watcherId, intervalMinutes) {
    const existing = this.watcherTimers.get(watcherId);
    if (existing) clearInterval(existing);
    const intervalMs = Math.max(60000, toInt(intervalMinutes, 30, 1, 10080) * 60000);
    const timer = setInterval(() => {
      this.runWatcher(watcherId).catch((error) => this.logger.warn('watcher ' + watcherId + ' failed: ' + error.message));
    }, intervalMs);
    if (typeof timer.unref === 'function') timer.unref();
    this.watcherTimers.set(watcherId, timer);
  }

  async handleWatcherUpsert(req, res) {
    const payload = await readJsonBody(req);
    const watcherId = payload.watcherId;
    if (typeof watcherId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(watcherId)) {
      throw httpError(400, 'INVALID_WATCHER_ID', 'watcherId 缺失或格式不合法');
    }
    const intervalMinutes = toInt(payload.intervalMinutes, 30, 1, 10080);
    const script = typeof payload.script === 'string' ? payload.script : '';
    if (!script.trim()) throw httpError(400, 'INVALID_SCRIPT', 'script 不能为空');
    const kind = payload.kind === 'node' ? 'node' : payload.kind === 'python' ? 'python' : null;
    if (!kind) throw httpError(400, 'INVALID_KIND', 'kind 必须是 python 或 node');
    const webhookUrl = typeof payload.webhookUrl === 'string' ? payload.webhookUrl : '';
    if (webhookUrl && !/^https?:\/\//i.test(webhookUrl)) throw httpError(400, 'INVALID_WEBHOOK', 'webhookUrl 必须是 http/https 地址');

    let previous = {};
    try {
      previous = JSON.parse(await fsp.readFile(path.join(this.watchersDir, watcherId + '.json'), 'utf8'));
    } catch {
      previous = {};
    }
    const config = {
      watcherId,
      intervalMinutes,
      script,
      kind,
      webhookUrl,
      createdAt: previous.createdAt || Date.now(),
      updatedAt: Date.now(),
      lastFingerprint: typeof previous.lastFingerprint === 'string' ? previous.lastFingerprint : '',
      lastCheckedAt: typeof previous.lastCheckedAt === 'number' ? previous.lastCheckedAt : 0,
    };
    await fsp.writeFile(path.join(this.watchersDir, watcherId + '.json'), JSON.stringify(config), { mode: 0o600 });
    this.watcherConfigs.set(watcherId, config);
    this.scheduleWatcher(watcherId, intervalMinutes);
    sendJson(res, 200, { ok: true, watcherId, intervalMinutes });
  }

  async handleWatcherDelete(res, watcherId) {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(watcherId)) throw httpError(400, 'INVALID_WATCHER_ID', 'watcherId 格式不合法');
    const timer = this.watcherTimers.get(watcherId);
    if (timer) clearInterval(timer);
    this.watcherTimers.delete(watcherId);
    this.watcherConfigs.delete(watcherId);
    try {
      await fsp.unlink(path.join(this.watchersDir, watcherId + '.json'));
    } catch {
      // Deleting a missing watcher is idempotent.
    }
    sendJson(res, 200, { ok: true, watcherId, deleted: true });
  }

  async runWatcher(watcherId) {
    if (this.watcherRunning.has(watcherId)) return null;
    let config = this.watcherConfigs.get(watcherId);
    if (!config) {
      try {
        config = JSON.parse(await fsp.readFile(path.join(this.watchersDir, watcherId + '.json'), 'utf8'));
        this.watcherConfigs.set(watcherId, config);
      } catch {
        return null;
      }
    }
    this.watcherRunning.add(watcherId);
    const temporaryScript = path.join(this.watchersDir, '.run-' + watcherId + '.' + (config.kind === 'node' ? 'mjs' : 'py'));
    try {
      await fsp.writeFile(temporaryScript, config.script, { mode: 0o600 });
      const command = config.kind === 'node' ? process.execPath : (this.pythonCandidates()[0].includes(path.sep) && fs.existsSync(this.pythonCandidates()[0]) ? this.pythonCandidates()[0] : 'python3');
      const result = await this.runCommandCapture(command, [temporaryScript], { timeoutMs: 120000, cwd: this.workspaceReal });
      const normalized = normalizeOutput(result.output);
      const fingerprint = fingerprintOf(normalized);
      config.lastCheckedAt = Date.now();
      if (fingerprint !== config.lastFingerprint) {
        config.lastFingerprint = fingerprint;
        const record = await this.writeOutboxEvent({
          type: 'watcher',
          watcherId,
          status: 'changed',
          exitCode: result.code,
          output: truncateTail(normalized, MAX_OUTBOX_TAIL_CHARS),
          fingerprint,
        });
        if (config.webhookUrl) this.postWebhook(config.webhookUrl, record).catch((error) => this.logger.warn('webhook failed: ' + error.message));
      }
      await fsp.writeFile(path.join(this.watchersDir, watcherId + '.json'), JSON.stringify(config), { mode: 0o600 });
      return config;
    } finally {
      this.watcherRunning.delete(watcherId);
      try { await fsp.unlink(temporaryScript); } catch { /* already removed */ }
    }
  }

  async postWebhook(url, payload) {
    const body = Buffer.from(JSON.stringify(payload), 'utf8');
    const target = new URL(url);
    const client = target.protocol === 'https:' ? https : http;
    await new Promise((resolve, reject) => {
      const request = client.request({
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || undefined,
        path: target.pathname + target.search,
        method: 'POST',
        headers: { 'content-type': 'application/json', 'content-length': String(body.length) },
        timeout: 10000,
      }, (response) => {
        response.resume();
        response.on('end', resolve);
      });
      request.on('error', reject);
      request.on('timeout', () => { request.destroy(new Error('webhook timeout')); });
      request.end(body);
    });
  }

  async handleOutboxList(req, res, url) {
    const since = Number.parseInt(url.searchParams.get('since') || '0', 10) || 0;
    let entries = [];
    try {
      entries = await fsp.readdir(this.outboxDir);
    } catch {
      entries = [];
    }
    const events = [];
    for (const name of entries) {
      if (!name.endsWith('.json')) continue;
      try {
        const record = JSON.parse(await fsp.readFile(path.join(this.outboxDir, name), 'utf8'));
        if (typeof record.createdAt === 'number' && record.createdAt > since) events.push(record);
      } catch {
        // A partially written file is skipped until a later poll.
      }
    }
    events.sort((left, right) => left.createdAt - right.createdAt);
    sendJson(res, 200, { ok: true, events, count: events.length, serverTime: Date.now() });
  }

  async handleOutboxAck(req, res) {
    const payload = await readJsonBody(req);
    let ids = [];
    if (Array.isArray(payload.eventIds)) ids = payload.eventIds;
    else if (Array.isArray(payload.ids)) ids = payload.ids;
    else if (typeof payload.eventId === 'string') ids = [payload.eventId];
    let acked = 0;
    for (const id of ids) {
      if (typeof id !== 'string' || !/^[A-Za-z0-9-]{1,64}$/.test(id)) continue;
      try {
        await fsp.unlink(path.join(this.outboxDir, id + '.json'));
        acked += 1;
      } catch {
        // Acking an already consumed event stays idempotent.
      }
    }
    sendJson(res, 200, { ok: true, acked });
  }

  async loadPlaywright() {
    if (this.playwrightModule !== undefined) return this.playwrightModule;
    try {
      this.playwrightModule = await import('playwright-core');
      return this.playwrightModule;
    } catch {
      try {
        const requireFromTools = createRequire(path.join(this.toolsDir, 'package.json'));
        const resolved = requireFromTools.resolve('playwright-core');
        this.playwrightModule = await import(pathToFileURL(resolved).href);
        return this.playwrightModule;
      } catch {
        this.playwrightModule = null;
        return null;
      }
    }
  }

  async ensureBrowser() {
    const playwright = await this.loadPlaywright();
    if (!playwright) throw httpError(501, 'BROWSER_NOT_INSTALLED', '浏览器能力包未安装');
    if (!this.browser || !this.browser.isConnected()) {
      this.browser = await playwright.chromium.launch({
        headless: true,
        args: ['--no-sandbox', '--disable-dev-shm-usage'],
        env: { ...process.env, PLAYWRIGHT_BROWSERS_PATH: path.join(this.toolsDir, 'ms-playwright') },
      });
    }
    return this.browser;
  }

  async handleBrowserStart(req, res) {
    const payload = await readJsonBody(req);
    const url = typeof payload.url === 'string' && payload.url ? payload.url : 'about:blank';
    if (url !== 'about:blank' && !/^https?:\/\//i.test(url)) throw httpError(400, 'INVALID_URL', 'url 必须是 http/https 地址');
    const width = toInt(payload.width, 720, 240, 4096);
    const height = toInt(payload.height, 1280, 240, 4096);
    const browser = await this.ensureBrowser();
    const sid = crypto.randomUUID();
    const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
    const page = await context.newPage();
    try {
      if (url !== 'about:blank') await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    } catch (error) {
      await context.close().catch(() => {});
      throw httpError(502, 'BROWSER_NAVIGATION_FAILED', '打开页面失败: ' + error.message);
    }
    const session = {
      sid,
      context,
      page,
      width,
      height,
      createdAt: Date.now(),
      lastActivity: Date.now(),
      subscribers: new Set(),
      frameTimer: null,
      capturing: false,
    };
    this.browserSessions.set(sid, session);
    sendJson(res, 200, { ok: true, sid, url: page.url(), title: await page.title().catch(() => ''), width, height });
  }

  startFramePump(session) {
    if (session.frameTimer) return;
    session.frameTimer = setInterval(() => {
      this.pushBrowserFrame(session).catch((error) => this.logger.warn('browser frame failed: ' + error.message));
    }, 700);
    if (typeof session.frameTimer.unref === 'function') session.frameTimer.unref();
  }

  async pushBrowserFrame(session) {
    if (session.capturing || session.subscribers.size === 0) return;
    if (session.page.isClosed()) return;
    session.capturing = true;
    try {
      let quality = 55;
      let buffer = await session.page.screenshot({ type: 'jpeg', quality });
      let encoded = buffer.toString('base64');
      while (encoded.length > MAX_BROWSER_FRAME_BYTES && quality > 15) {
        quality -= 10;
        buffer = await session.page.screenshot({ type: 'jpeg', quality });
        encoded = buffer.toString('base64');
      }
      if (encoded.length > MAX_BROWSER_FRAME_BYTES) return;
      const title = await session.page.title().catch(() => '');
      const frame = {
        type: 'frame',
        url: session.page.url(),
        title,
        jpegBase64: encoded,
        width: session.width,
        height: session.height,
      };
      for (const socket of session.subscribers) sendWsText(socket, frame);
    } finally {
      session.capturing = false;
    }
  }

  async attachBrowserSocket(session, socket, head, parserFactory) {
    session.subscribers.add(socket);
    session.lastActivity = Date.now();
    this.startFramePump(session);
    const parser = parserFactory((frame) => {
      if (frame.opcode === 0x8) {
        session.subscribers.delete(socket);
        socket.destroy();
        return;
      }
      if (frame.opcode === 0x9) {
        try { socket.write(encodeWsFrame(frame.data, 0xA)); } catch { /* gone */ }
        return;
      }
      if (frame.opcode !== 0x1) return;
      let message;
      try {
        message = JSON.parse(frame.data.toString('utf8'));
      } catch {
        sendWsText(socket, { type: 'error', code: 'INVALID_JSON', message: '消息不是合法 JSON' });
        return;
      }
      this.handleBrowserMessage(session, message).catch((error) => {
        sendWsText(socket, { type: 'error', code: 'BROWSER_ACTION_FAILED', message: error.message });
      });
    });
    socket.on('data', parser);
    socket.on('close', () => {
      session.subscribers.delete(socket);
    });
    if (head && head.length) parser(head);
    await this.pushBrowserFrame(session).catch(() => {});
  }

  async handleBrowserMessage(session, message) {
    session.lastActivity = Date.now();
    if (!message || typeof message.type !== 'string') return;
    if (message.type === 'tap') {
      const x = Number(message.x);
      const y = Number(message.y);
      if (!Number.isFinite(x) || !Number.isFinite(y)) throw new Error('tap 坐标不合法');
      await session.page.mouse.click(x, y);
      return;
    }
    if (message.type === 'text') {
      await session.page.keyboard.type(String(message.value ?? ''));
      return;
    }
    if (message.type === 'navigate') {
      const url = String(message.url || '');
      if (!/^https?:\/\//i.test(url) && url !== 'about:blank') throw new Error('navigate 地址不合法');
      await session.page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
      return;
    }
    if (message.type === 'handoff_done') {
      for (const socket of session.subscribers) sendWsText(socket, { type: 'ack', action: 'handoff_done', ts: Date.now() });
    }
  }

  async sweepIdleBrowserSessions() {
    const now = Date.now();
    for (const session of [...this.browserSessions.values()]) {
      if (now - session.lastActivity > this.browserIdleMs) {
        await this.closeBrowserSession(session, 'idle_timeout').catch((error) => this.logger.warn('close browser failed: ' + error.message));
      }
    }
  }

  async closeBrowserSession(session, reason) {
    if (!this.browserSessions.has(session.sid)) return;
    this.browserSessions.delete(session.sid);
    if (session.frameTimer) clearInterval(session.frameTimer);
    for (const socket of session.subscribers) {
      sendWsText(socket, { type: 'closed', reason, ts: Date.now() });
      sendWsClose(socket);
      try { socket.destroy(); } catch { /* already gone */ }
    }
    session.subscribers.clear();
    await session.context.close().catch(() => {});
  }

  async closeBrowser() {
    if (this.browser) {
      await this.browser.close().catch(() => {});
      this.browser = null;
    }
  }

  async handleUpgrade(req, socket, head) {
    let url;
    try {
      url = new URL(req.url || '/', 'http://localhost');
    } catch {
      socket.destroy();
      return;
    }
    if (!this.isAuthorized(req)) {
      try {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
      } catch { /* gone */ }
      socket.destroy();
      return;
    }
    const key = req.headers['sec-websocket-key'];
    if (typeof key !== 'string' || key.length === 0) {
      socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }

    let execMatch = /^\/exec\/([^/]+)$/.exec(url.pathname);
    let browserMatch = /^\/browser\/([^/]+)$/.exec(url.pathname);
    if (!execMatch && !browserMatch) {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }

    let job = null;
    let session = null;
    if (execMatch) {
      const runId = decodeURIComponent(execMatch[1]);
      job = this.jobs.get(runId);
      if (!job) {
        socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
      }
    } else {
      const sid = decodeURIComponent(browserMatch[1]);
      session = this.browserSessions.get(sid);
      if (!session) {
        socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
        socket.destroy();
        return;
      }
    }

    const headers = [
      'HTTP/1.1 101 Switching Protocols',
      'Upgrade: websocket',
      'Connection: Upgrade',
      'Sec-WebSocket-Accept: ' + wsAccept(key),
      '\r\n',
    ];
    socket.write(headers.join('\r\n'));
    socket.setNoDelay?.(true);

    if (job) {
      for (const frame of job.chunks) sendWsText(socket, frame);
      if (job.status === 'running') {
        job.subscribers.add(socket);
        const parser = createWsFrameParser((frame) => {
          if (frame.opcode === 0x8) {
            job.subscribers.delete(socket);
            return;
          }
          if (frame.opcode === 0x9) {
            try { socket.write(encodeWsFrame(frame.data, 0xA)); } catch { /* gone */ }
            return;
          }
          if (frame.opcode !== 0x1) return;
          try {
            const message = JSON.parse(frame.data.toString('utf8'));
            if (message && message.type === 'cancel') this.cancelJob(job);
          } catch {
            // Ignore malformed client frames.
          }
        });
        socket.on('data', parser);
        socket.on('close', () => job.subscribers.delete(socket));
        if (head && head.length) parser(head);
      } else {
        sendWsClose(socket);
        socket.end();
      }
    } else {
      await this.attachBrowserSocket(session, socket, head, createWsFrameParser);
    }
  }
}

export function createMuseNodeServer(options = {}) {
  return new MuseNodeServer(options);
}

export default createMuseNodeServer;
export { MuseNodeServer, fingerprintOf, normalizeOutput, MAX_FILE_BYTES };

function parseMainArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (item === '--config') parsed.config = argv[++index];
    else if (item === '--port') parsed.port = Number.parseInt(argv[++index], 10);
    else if (item === '--bind') parsed.bind = argv[++index];
    else if (item === '--tls') parsed.tls = true;
    else if (item === '--home') parsed.home = argv[++index];
  }
  return parsed;
}

async function main() {
  const args = parseMainArgs(process.argv.slice(2));
  const homeDir = args.home || process.env.MUSE_NODE_HOME || os.homedir();
  const configPath = args.config || path.join(homeDir, '.muse-node', 'node.json');
  let config = {};
  try {
    config = JSON.parse(await fsp.readFile(configPath, 'utf8'));
  } catch {
    config = {};
  }
  const logger = createRotatingLogger(path.join(homeDir, '.muse-node', 'logs', 'muse-node.log'));
  const app = createMuseNodeServer({
    homeDir,
    port: Number.isFinite(args.port) ? args.port : (Number.isFinite(config.port) ? config.port : DEFAULT_PORT),
    bind: args.bind || config.bind || '127.0.0.1',
    packs: Array.isArray(config.packs) ? config.packs : undefined,
    tls: args.tls === true || config.tls === true,
    logger,
  });
  await app.start();
  logger.info('muse-node ' + SERVER_VERSION + ' listening on ' + app.bind + ':' + app.address().port);
  const shutdown = async (signal) => {
    logger.info('muse-node stopping on ' + signal);
    await app.stop();
    process.exit(0);
  };
  process.on('SIGTERM', () => { shutdown('SIGTERM').catch(() => process.exit(1)); });
  process.on('SIGINT', () => { shutdown('SIGINT').catch(() => process.exit(1)); });
}

const invokedPath = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : '';
if (invokedPath === import.meta.url) {
  main().catch((error) => {
    process.stderr.write('muse-node failed: ' + (error?.stack || error?.message || String(error)) + '\n');
    process.exit(1);
  });
}
