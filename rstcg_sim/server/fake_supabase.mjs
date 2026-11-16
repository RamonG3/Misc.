// A stand-in for Supabase, for playing online in a browser with no real server: node server/fake_supabase.mjs [port]
//
// It answers what the page uses, the way Supabase does:
//   /auth/v1/signup, /auth/v1/token?grant_type=refresh_token   anonymous sign-ins (tokens expire after an hour)
//   /rest/v1/profiles                                          names: read them, pick or change yours (the same rules)
//   /functions/v1/duel                                         the real function (supabase/functions/duel/index.js),
//                                                              against the pretend database in fake_db.mjs
//   /realtime/v1/websocket                                     duel rows as they change (Phoenix channels, JSON)
// and, for tests:
//   POST /__test/clock { add: ms }      move the server's clock on (decisions run out of time)
//   POST /__test/realtime { on: bool }  drop the live connections and refuse new ones (the page must poll)
//   GET  /__test/db                     the tables
// Prints "listening <port>" when ready. The browser tests (web/test/ui/online.js) run it as a child process.
import http from 'http';
import crypto from 'crypto';
import path from 'path';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';
import { makeDb } from './fake_db.mjs';

process.removeAllListeners('warning');
process.on('warning', () => {});
const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = +(process.argv.find((a) => /^\d+$/.test(a)) || 54321);
const KEY = 'sb_publishable_fake';
const DB = 'http://db.fake';
const J = JSON.stringify;
if (!process.argv.includes('--no-build')) execFileSync('node', [path.join(HERE, 'build.js')], { stdio: 'ignore' });

// ---------- the clock (tests move it on) ----------
const RealDate = Date;
let shift = 0;
class FakeDate extends RealDate {
  constructor(...a) { if (a.length) super(...a); else super(RealDate.now() + shift); }
  static now() { return RealDate.now() + shift; }
}
globalThis.Date = FakeDate;

// ---------- the database, and Realtime hearing it change ----------
const live = new Set();
let realtimeOn = true;
const { db, RPC } = makeDb({ now: () => Date.now(), onChange: (row) => { const copy = JSON.parse(J(row)); setTimeout(() => broadcast(copy), 25); } });
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init = {}) => {
  const u = new URL(typeof url === 'string' ? url : url.url);
  if (u.origin !== DB) return realFetch(url, init);
  const send = (status, body) => new Response(body === undefined ? '' : J(body), { status, headers: { 'Content-Type': 'application/json' } });
  if ((init.headers || {}).apikey !== 'sb_secret_fake') return send(401, { message: 'no secret key' });
  if (u.pathname.startsWith('/rest/v1/rpc/')) {
    const fn = RPC[u.pathname.slice('/rest/v1/rpc/'.length)];
    if (!fn) return send(404, { message: 'no such function' });
    try { return send(200, fn(JSON.parse(init.body))); } catch (e) { return new Response(e.text || String(e), { status: e.status || 500 }); }
  }
  if (u.pathname === '/rest/v1/profiles') {
    const id = (u.searchParams.get('id') || '').replace(/^eq\./, '');
    return send(200, db.profiles.has(id) ? [db.profiles.get(id)] : []);
  }
  return send(404, { message: 'no such route' });
};
let duel = null;
globalThis.Deno = { env: { get: (k) => ({ SUPABASE_URL: DB, SUPABASE_SECRET_KEYS: '{"default":"sb_secret_fake"}' })[k] }, serve: (h) => { duel = h; } };
await import(path.join(HERE, 'supabase/functions/duel/index.js'));

// ---------- sign-ins ----------
const b64 = (o) => Buffer.from(J(o)).toString('base64url');
const sessions = new Map();          // refresh token -> user id
function session(uid) {
  const exp = Math.floor(Date.now() / 1000) + 3600;
  const refresh = crypto.randomBytes(12).toString('hex');
  sessions.set(refresh, uid);
  return { access_token: `${b64({ alg: 'ES256', typ: 'JWT' })}.${b64({ sub: uid, role: 'authenticated', exp, is_anonymous: true })}.fake`, token_type: 'bearer',
    expires_in: 3600, expires_at: exp, refresh_token: refresh, user: { id: uid, is_anonymous: true } };
}
function userOf(token) {
  try {
    const c = JSON.parse(Buffer.from(String(token).split('.')[1], 'base64url').toString());
    return c.role === 'authenticated' && c.exp > Date.now() / 1000 ? c.sub : null;
  } catch { return null; }
}
const bearer = (req) => userOf(String(req.headers.authorization || '').replace(/^Bearer /, ''));

// ---------- HTTP ----------
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, prefer', 'Access-Control-Allow-Methods': 'GET, POST, PATCH, OPTIONS' };
const body = (req) => new Promise((ok) => { let t = ''; req.on('data', (d) => { t += d; }); req.on('end', () => ok(t)); });
const NAME_SHAPE = /^[A-Za-z0-9][A-Za-z0-9 _-]{1,10}[A-Za-z0-9]$/;
const server = http.createServer(async (req, res) => {
  const out = (status, x, headers = {}) => { res.writeHead(status, { ...CORS, 'Content-Type': 'application/json', ...headers }); res.end(x === undefined ? '' : J(x)); };
  try {
    const u = new URL(req.url, `http://127.0.0.1:${PORT}`);
    if (req.method === 'OPTIONS') { res.writeHead(204, CORS); res.end(); return; }
    if (u.pathname.startsWith('/__test/')) {
      const b = req.method === 'POST' ? JSON.parse((await body(req)) || '{}') : {};
      if (u.pathname === '/__test/clock') { shift += b.add || 0; out(200, { shift }); return; }
      if (u.pathname === '/__test/realtime') { realtimeOn = !!b.on; if (!realtimeOn) for (const c of live) c.socket.destroy(); out(200, { on: realtimeOn }); return; }
      if (u.pathname === '/__test/db') { out(200, { profiles: [...db.profiles.values()], matches: [...db.matches.values()], moves: Object.fromEntries([...db.moves].map(([k, v]) => [k, v.length])) }); return; }
      out(404, {}); return;
    }
    if (req.headers.apikey !== KEY) { out(401, { message: 'Invalid API key' }); return; }
    if (u.pathname === '/auth/v1/signup' && req.method === 'POST') { out(200, session(crypto.randomUUID())); return; }
    if (u.pathname === '/auth/v1/token' && u.searchParams.get('grant_type') === 'refresh_token') {
      const b = JSON.parse((await body(req)) || '{}');
      const uid = sessions.get(b.refresh_token);
      if (!uid) { out(400, { error_code: 'refresh_token_not_found', msg: 'Invalid Refresh Token' }); return; }
      sessions.delete(b.refresh_token);
      out(200, session(uid)); return;
    }
    if (u.pathname === '/rest/v1/profiles') {
      const uid = bearer(req);
      if (!uid) { out(401, { code: 'PGRST301', message: 'JWT expired' }); return; }
      if (req.method === 'GET') {
        const id = (u.searchParams.get('id') || '').replace(/^eq\./, '');
        out(200, [...db.profiles.values()].filter((p) => !id || p.id === id)); return;
      }
      if (req.method === 'POST') {
        const b = JSON.parse((await body(req)) || '{}');
        if (b.id !== uid) { out(403, { code: '42501', message: 'new row violates row-level security policy' }); return; }
        const name = String(b.name || '');
        if (!NAME_SHAPE.test(name) || /  /.test(name)) { out(400, { code: '23514', message: 'violates check constraint "profiles_name_shape"' }); return; }
        if (['you', 'bot'].includes(name.toLowerCase())) { out(400, { code: '23514', message: 'violates check constraint "profiles_name_reserved"' }); return; }
        for (const p of db.profiles.values()) if (p.id !== uid && p.name.toLowerCase() === name.toLowerCase()) { out(409, { code: '23505', message: 'duplicate key value violates unique constraint "profiles_name_unique"' }); return; }
        db.profiles.set(uid, { id: uid, name, created_at: (db.profiles.get(uid) || {}).created_at || new Date().toISOString() });
        out(201); return;
      }
    }
    if (u.pathname === '/functions/v1/duel') {
      if (!bearer(req)) { out(401, { code: 401, message: 'Invalid JWT' }); return; }       // the platform's check (verify_jwt)
      const r = await duel(new Request(`http://fn.fake/duel`, { method: req.method, headers: { Authorization: req.headers.authorization, 'Content-Type': 'application/json' }, body: req.method === 'POST' ? await body(req) : undefined }));
      res.writeHead(r.status, Object.fromEntries(r.headers)); res.end(await r.text()); return;
    }
    out(404, { message: 'no such route' });
  } catch (e) { console.error(e); out(500, { message: String(e) }); }
});

// ---------- Realtime (a small WebSocket server: text frames, ping, close) ----------
server.on('upgrade', (req, socket) => {
  const u = new URL(req.url, 'http://x');
  if (!realtimeOn || u.pathname !== '/realtime/v1/websocket' || u.searchParams.get('apikey') !== KEY) { socket.destroy(); return; }
  const accept = crypto.createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  const c = { socket, topics: new Map(), buf: Buffer.alloc(0) };
  live.add(c);
  socket.on('data', (d) => { c.buf = Buffer.concat([c.buf, d]); frames(c); });
  socket.on('close', () => live.delete(c));
  socket.on('error', () => live.delete(c));
});
function frames(c) {
  for (;;) {
    const b = c.buf;
    if (b.length < 2) return;
    const op = b[0] & 0x0f, masked = b[1] & 0x80;
    let len = b[1] & 0x7f, off = 2;
    if (len === 126) { if (b.length < 4) return; len = b.readUInt16BE(2); off = 4; } else if (len === 127) { if (b.length < 10) return; len = Number(b.readBigUInt64BE(2)); off = 10; }
    const m = masked ? 4 : 0;
    if (b.length < off + m + len) return;
    const data = Buffer.from(b.subarray(off + m, off + m + len));
    if (masked) for (let k = 0; k < data.length; k++) data[k] ^= b[off + (k & 3)];
    c.buf = b.subarray(off + m + len);
    if (op === 0x8) { c.socket.end(); return; }
    if (op === 0x9) { frame(c, data, 0xa); continue; }
    if (op === 0x1) heard(c, data.toString('utf8'));
  }
}
function frame(c, data, op = 0x1) {
  const p = Buffer.isBuffer(data) ? data : Buffer.from(data);
  let head;
  if (p.length < 126) head = Buffer.from([0x80 | op, p.length]);
  else if (p.length < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | op; head[1] = 126; head.writeUInt16BE(p.length, 2); } else { head = Buffer.alloc(10); head[0] = 0x80 | op; head[1] = 127; head.writeBigUInt64BE(BigInt(p.length), 2); }
  try { c.socket.write(Buffer.concat([head, p])); } catch { /* gone */ }
}
const say = (c, m) => frame(c, J(m));
function heard(c, text) {
  let m;
  try { m = JSON.parse(text); } catch { return; }
  const reply = (status, response = {}) => say(c, { topic: m.topic, event: 'phx_reply', payload: { status, response }, ref: m.ref, join_ref: m.join_ref || null });
  if (m.topic === 'phoenix' && m.event === 'heartbeat') { reply('ok'); return; }
  if (m.event === 'phx_join') {
    const p = m.payload || {}, uid = userOf(p.access_token);
    const pc = p.config && p.config.postgres_changes && p.config.postgres_changes[0];
    const id = pc && pc.table === 'matches' && /^id=eq\.([0-9a-f-]{36})$/.exec(pc.filter || '');
    if (!uid || !id) { reply('error', { reason: 'Invalid join' }); return; }
    c.topics.set(m.topic, { id: id[1], uid });
    reply('ok', { postgres_changes: [{ id: 7, event: 'UPDATE', schema: 'public', table: 'matches', filter: pc.filter }] });
    say(c, { topic: m.topic, event: 'system', payload: { channel: m.topic.replace(/^realtime:/, ''), extension: 'postgres_changes', message: 'Subscribed to PostgreSQL', status: 'ok' }, ref: null });
    return;
  }
  if (m.event === 'access_token') { const t = c.topics.get(m.topic); const uid = userOf(m.payload && m.payload.access_token); if (t && uid) t.uid = uid; return; }
  if (m.event === 'phx_leave') { c.topics.delete(m.topic); reply('ok'); }
}
function broadcast(row) {
  for (const c of live) for (const [topic, t] of c.topics) {
    if (t.id !== row.id || (row.host !== t.uid && row.guest !== t.uid)) continue;      // the select policy: players see their own duels
    say(c, { topic, event: 'postgres_changes', ref: null, payload: { ids: [7], data: { type: 'UPDATE', schema: 'public', table: 'matches', commit_timestamp: new Date().toISOString(), errors: null, columns: [], record: row, old_record: { id: row.id } } } });
  }
}

server.listen(PORT, '127.0.0.1', () => console.log(`listening ${PORT}`));
