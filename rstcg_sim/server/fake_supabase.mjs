// A stand-in for Supabase, for playing online in a browser with no real server: node server/fake_supabase.mjs [port]
//
// It answers what the page uses, the way Supabase does:
//   /auth/v1/signup, /auth/v1/token?grant_type=refresh_token   anonymous sign-ins (tokens expire after an hour)
//   /auth/v1/settings                                          which ways to sign in are switched on
//   /auth/v1/authorize, /auth/v1/user/identities/authorize     off to "Google" (a pretend page here, /__fake/google),
//   /auth/v1/callback, /auth/v1/token?grant_type=pkce          and back with a code that only the page can cash in
//   /auth/v1/user (GET, PUT { email }), /auth/v1/otp,          emailed codes: for a guest, for a new player and for
//   /auth/v1/verify                                            one coming back. The "emails" wait in /__test/mail
//   /auth/v1/logout?scope=local                                signing out
//   /rest/v1/profiles                                          names: read them, pick or change yours (the same rules)
//   /rest/v1/progress, /rest/v1/rpc/progress_save              the cloud save, with the real one's rules (fake_db.mjs)
//   /functions/v1/duel                                         the real function (supabase/functions/duel/index.js),
//                                                              against the pretend database in fake_db.mjs
//   /realtime/v1/websocket                                     duel rows as they change (Phoenix channels, JSON)
// The sign-in answers and errors are copied from Supabase's own server (github.com/supabase/auth, read 2026-11-16).
// and, for tests:
//   POST /__test/clock { add: ms }      move the server's clock on (decisions run out of time)
//   POST /__test/realtime { on: bool }  drop the live connections and refuse new ones (the page must poll)
//   POST /__test/auth { google, email, linking, team, confirm }   the dashboard's switches: the Google and Email
//                                       providers, "manual linking", who the built-in mailer will write to (a list
//                                       of addresses, or null for anyone), and "Confirm email"
//   POST /__test/revoke { uid }         the server forgets that player's sign-ins (their devices are signed out)
//   GET  /__test/mail?to=address        the emails sent to that address, newest last: [{ to, kind, code }]
//   GET  /__test/db                     the tables
// Prints "listening <port>" when ready. The browser tests (web/test/ui/online.js, accounts.js) run it as a child process.
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
const { db, RPC, progress } = makeDb({ now: () => Date.now(), onChange: (row) => { const copy = JSON.parse(J(row)); setTimeout(() => broadcast(copy), 25); } });
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
const users = new Map();             // user id -> { id, is_anonymous, email, identities: [{ provider, sub, email }], pending }
const sessions = new Map();          // refresh token -> { uid, sid }
const flows = new Map();             // a trip to Google: state -> { back, challenge, link: user id or null }
const codes = new Map();             // the code Google's trip ends with -> { uid, challenge }
const mail = [];                     // the "emails": { to, kind, code, at }
const flags = { google: true, email: true, linking: true, team: null, confirm: true };
const OTP_LIFE = 3600e3, MAIL_GAP = 60e3;
function newUser(more) {
  const u = Object.assign({ id: crypto.randomUUID(), is_anonymous: true, email: null, identities: [], pending: {} }, more);
  users.set(u.id, u);
  return u;
}
function userJson(u) {
  const providers = u.identities.map((i) => i.provider);
  return { id: u.id, aud: 'authenticated', role: 'authenticated', email: u.email || '', is_anonymous: u.is_anonymous,
    app_metadata: providers.length ? { provider: providers[0], providers } : {}, user_metadata: {},
    identities: u.identities.map((i) => ({ provider: i.provider, id: i.sub, identity_data: { email: i.email, sub: i.sub } })),
    ...(u.pending.change ? { new_email: u.pending.change.email } : {}) };
}
function session(uid, sid = crypto.randomUUID()) {
  const u = users.get(uid);
  const exp = Math.floor(Date.now() / 1000) + 3600;
  const refresh = crypto.randomBytes(12).toString('hex');
  sessions.set(refresh, { uid, sid });
  return { access_token: `${b64({ alg: 'ES256', typ: 'JWT' })}.${b64({ sub: uid, role: 'authenticated', exp, is_anonymous: u.is_anonymous, session_id: sid, email: u.email || '' })}.fake`,
    token_type: 'bearer', expires_in: 3600, expires_at: exp, refresh_token: refresh, user: userJson(u) };
}
// who a token says it is: { id, guest, sid }, or null. The token goes on saying "guest" until a new one is issued.
function claims(token) {
  try {
    const c = JSON.parse(Buffer.from(String(token).split('.')[1], 'base64url').toString());
    return c.role === 'authenticated' && c.exp > Date.now() / 1000 && users.has(c.sub) ? { id: c.sub, guest: c.is_anonymous !== false, sid: c.session_id } : null;
  } catch { return null; }
}
const userOf = (token) => { const c = claims(token); return c ? c.id : null; };
const tokenOf = (req) => String(req.headers.authorization || '').replace(/^Bearer /, '');
const bearer = (req) => userOf(tokenOf(req));
const byEmail = (email) => [...users.values()].find((u) => u.email && u.email.toLowerCase() === String(email).toLowerCase());
const otp = () => String(crypto.randomInt(0, 1e6)).padStart(6, '0');
// the mailer: Supabase's own only writes to the project's team, and to anyone at most once a minute
function send(to, kind, code) {
  if (flags.team && !flags.team.map((x) => x.toLowerCase()).includes(to.toLowerCase())) return { status: 400, error_code: 'email_address_not_authorized', msg: `Email address "${to}" cannot be used as it is not authorized` };
  const last = [...mail].reverse().find((m) => m.to.toLowerCase() === to.toLowerCase());
  if (last && Date.now() - last.at < MAIL_GAP) return { status: 429, error_code: 'over_email_send_rate_limit', msg: `For security purposes, you can only request this after ${Math.ceil((MAIL_GAP - (Date.now() - last.at)) / 1000)} seconds.` };
  mail.push({ to, kind, code, at: Date.now() });
  return null;
}
const fresh = (p, token) => p && p.code === String(token) && Date.now() - p.at < OTP_LIFE;
const GOOGLE_PEOPLE = ['ann@gmail.test', 'bob@gmail.test', 'cat@gmail.test'];
const page = (title, inner) => `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title><style>body{font:20px system-ui;background:#fff;color:#222;max-width:520px;margin:60px auto}a{display:block;padding:14px 18px;margin:10px 0;border:1px solid #ccc;border-radius:8px;color:#1a73e8;text-decoration:none}</style></head><body>${inner}</body></html>`;

// ---------- HTTP ----------
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type, prefer', 'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, OPTIONS' };
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
      if (u.pathname === '/__test/db') {
        out(200, { profiles: [...db.profiles.values()], matches: [...db.matches.values()], moves: Object.fromEntries([...db.moves].map(([k, v]) => [k, v.length])),
          users: [...users.values()].map(userJson), progress: [...db.progress.values()] });
        return;
      }
      if (u.pathname === '/__test/auth') { Object.assign(flags, b); out(200, flags); return; }
      if (u.pathname === '/__test/mail') { const to = (u.searchParams.get('to') || '').toLowerCase(); out(200, mail.filter((m) => !to || m.to.toLowerCase() === to)); return; }
      if (u.pathname === '/__test/revoke') { for (const [k, v] of sessions) if (v.uid === b.uid) sessions.delete(k); out(200, {}); return; }
      out(404, {}); return;
    }

    // ---------- the trip to Google and back (the browser goes to these itself, so they carry no API key) ----------
    const html = (status, text) => { res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' }); res.end(text); };
    const hop = (to) => { res.writeHead(302, { Location: to }); res.end(); };
    // where a sign-in may send the player back to: here, this stand-in allows any page on this machine
    const backTo = (x) => (/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//.test(String(x)) ? String(x) : `http://127.0.0.1:${PORT}/__fake/site`);
    const leave = (link) => {
      if (!flags.google) return { status: 400, error_code: 'validation_failed', msg: 'Unsupported provider: provider is not enabled' };
      if (u.searchParams.get('provider') !== 'google') return { status: 400, error_code: 'validation_failed', msg: 'Unsupported provider' };
      const challenge = u.searchParams.get('code_challenge') || '';
      if (!/^[a-zA-Z._~0-9-]{43,128}$/.test(challenge) || (u.searchParams.get('code_challenge_method') || '').toLowerCase() !== 's256') return { status: 400, error_code: 'validation_failed', msg: 'PKCE flow requires code_challenge_method and code_challenge' };
      const state = crypto.randomBytes(9).toString('hex');
      flows.set(state, { back: backTo(u.searchParams.get('redirect_to')), challenge, link });
      return { url: `http://127.0.0.1:${PORT}/__fake/google?state=${state}` };
    };
    if (u.pathname === '/auth/v1/authorize' && req.method === 'GET') {
      const r = leave(null);
      if (r.url) hop(r.url); else out(r.status, { code: r.status, error_code: r.error_code, msg: r.msg });
      return;
    }
    if (u.pathname === '/__fake/google') {
      const f = flows.get(u.searchParams.get('state'));
      if (!f) { html(400, page('Google (pretend)', '<h2>That sign-in has run out.</h2>')); return; }
      const cb = `/auth/v1/callback?state=${u.searchParams.get('state')}`;
      html(200, page('Google (pretend)', `<h2>Choose an account</h2><p>to continue to RuneScape TCG</p>${GOOGLE_PEOPLE.map((e) => `<a data-as="${e}" href="${cb}&as=${encodeURIComponent(e)}">${e}</a>`).join('')}<a data-as="" href="${cb}&error=access_denied">Cancel</a>`));
      return;
    }
    if (u.pathname === '/__fake/site') { html(200, page('Site URL', '<h2>The Site URL</h2><p>A sign-in sent back somewhere that is not on the allowed list ends up here.</p>')); return; }
    if (u.pathname === '/auth/v1/callback') {
      const f = flows.get(u.searchParams.get('state'));
      flows.delete(u.searchParams.get('state'));
      if (!f) { out(400, { code: 400, error_code: 'bad_oauth_state', msg: 'OAuth callback with invalid state' }); return; }
      // an error goes back in the address, both after ? and after #, as Supabase sends it
      const fail = (error, error_code, description) => {
        const q = new URLSearchParams({ error, ...(error_code ? { error_code } : {}), error_description: description });
        hop(`${f.back}${f.back.includes('?') ? '&' : '?'}${q}#${q}&sb=`);
      };
      if (u.searchParams.get('error')) { fail('access_denied', '', 'The user denied the request'); return; }
      const email = String(u.searchParams.get('as') || ''), sub = 'google-' + email;
      const owner = [...users.values()].find((x) => x.identities.some((i) => i.provider === 'google' && i.sub === sub));
      let who;
      if (f.link) {                                   // tying Google to the player who asked
        who = users.get(f.link);
        if (!who) { fail('server_error', 'user_not_found', 'User not found'); return; }
        if (owner && owner.id !== who.id) { fail('server_error', 'identity_already_exists', 'Identity is already linked to another user'); return; }
        const taken = byEmail(email);
        if (!who.email && taken && taken.id !== who.id) { fail('invalid_request', 'email_exists', 'A user with this email address has already been registered'); return; }
        if (!owner) who.identities.push({ provider: 'google', sub, email });
        who.is_anonymous = false;
        if (!who.email) who.email = email;
      } else {                                        // signing in: the player who owns it, or has its address, or a new one
        who = owner || byEmail(email);
        if (who && !owner) who.identities.push({ provider: 'google', sub, email });
        if (!who) who = newUser({ is_anonymous: false, email, identities: [{ provider: 'google', sub, email }] });
      }
      const code = crypto.randomUUID();
      codes.set(code, { uid: who.id, challenge: f.challenge });
      hop(`${f.back}${f.back.includes('?') ? '&' : '?'}code=${code}`);
      return;
    }

    if (req.headers.apikey !== KEY) { out(401, { message: 'Invalid API key' }); return; }
    const oops = (status, error_code, msg) => out(status, { code: status, error_code, msg });
    const read = async () => JSON.parse((await body(req)) || '{}');
    if (u.pathname === '/auth/v1/signup' && req.method === 'POST') { out(200, session(newUser().id)); return; }
    if (u.pathname === '/auth/v1/settings') { out(200, { external: { anonymous_users: true, google: flags.google, email: flags.email, phone: false }, disable_signup: false, mailer_autoconfirm: !flags.confirm }); return; }
    if (u.pathname === '/auth/v1/token' && u.searchParams.get('grant_type') === 'refresh_token') {
      const b = await read();
      const s0 = sessions.get(b.refresh_token);
      if (!s0 || !users.has(s0.uid)) { oops(400, 'refresh_token_not_found', 'Invalid Refresh Token: Refresh Token Not Found'); return; }
      sessions.delete(b.refresh_token);
      out(200, session(s0.uid, s0.sid)); return;
    }
    if (u.pathname === '/auth/v1/token' && u.searchParams.get('grant_type') === 'pkce') {
      const b = await read();
      if (!b.auth_code || !b.code_verifier) { oops(400, 'validation_failed', 'invalid request: both auth code and code verifier should be non-empty'); return; }
      const c = codes.get(b.auth_code);
      if (!c) { oops(404, 'flow_state_not_found', 'invalid flow state, no valid flow state found'); return; }
      if (crypto.createHash('sha256').update(String(b.code_verifier)).digest('base64url') !== c.challenge) { oops(400, 'bad_code_verifier', 'code challenge does not match previously saved code verifier'); return; }
      codes.delete(b.auth_code);
      out(200, session(c.uid)); return;
    }
    if (u.pathname === '/auth/v1/user/identities/authorize' && req.method === 'GET') {
      const me = claims(tokenOf(req));
      if (!me) { oops(401, 'bad_jwt', 'invalid JWT'); return; }
      if (!flags.linking) { oops(404, 'manual_linking_disabled', 'Manual linking is disabled'); return; }
      const r = leave(me.id);
      if (r.url) out(200, { url: r.url }); else oops(r.status, r.error_code, r.msg);
      return;
    }
    if (u.pathname === '/auth/v1/user') {
      const me = claims(tokenOf(req));
      if (!me) { oops(401, 'bad_jwt', 'invalid JWT'); return; }
      const who = users.get(me.id);
      if (req.method === 'GET') { out(200, userJson(who)); return; }
      if (req.method === 'PUT') {
        const b = await read();
        const email = String(b.email || '').trim();
        if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) { oops(400, 'validation_failed', 'Unable to validate email address: invalid format'); return; }
        if (!flags.email) { oops(400, 'email_provider_disabled', 'Email logins are disabled'); return; }
        const taken = byEmail(email);
        if (taken && taken.id !== who.id) { oops(422, 'email_exists', 'A user with this email address has already been registered'); return; }
        if (who.is_anonymous && !flags.confirm) {     // "Confirm email" is off: the address goes straight on
          Object.assign(who, { email, is_anonymous: false }); who.identities.push({ provider: 'email', sub: who.id, email });
          out(200, userJson(who)); return;
        }
        const code = otp(), no = send(email, 'email_change', code);
        if (no) { oops(no.status, no.error_code, no.msg); return; }
        who.pending.change = { email, code, at: Date.now() };
        out(200, userJson(who)); return;
      }
    }
    if (u.pathname === '/auth/v1/otp' && req.method === 'POST') {
      const b = await read();
      const email = String(b.email || '').trim();
      if (!flags.email) { oops(400, 'email_provider_disabled', 'Email logins are disabled'); return; }
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) { oops(400, 'validation_failed', 'Unable to validate email address: invalid format'); return; }
      let who = byEmail(email);
      if (!who && b.create_user === false) { oops(422, 'otp_disabled', 'Signups not allowed for otp'); return; }
      const made = !who, code = otp();
      // a new player gets the "Confirm signup" email, one coming back gets the "Magic Link" one
      if (!who) { who = newUser({ is_anonymous: false, email, confirmed: false }); who.identities.push({ provider: 'email', sub: who.id, email }); }
      const kind = who.confirmed === false ? 'signup' : 'magiclink';
      const no = send(email, kind, code);
      if (no) { if (made) users.delete(who.id); oops(no.status, no.error_code, no.msg); return; }
      who.pending[kind] = { code, at: Date.now() };
      out(200, {}); return;
    }
    if (u.pathname === '/auth/v1/verify' && req.method === 'POST') {
      const b = await read();
      const no = () => oops(403, 'otp_expired', 'Token has expired or is invalid');
      if (b.type === 'email_change') {
        const who = [...users.values()].find((x) => x.pending.change && x.pending.change.email.toLowerCase() === String(b.email).toLowerCase());
        if (!who || !fresh(who.pending.change, b.token)) { no(); return; }
        const taken = byEmail(who.pending.change.email);
        if (taken && taken.id !== who.id) { no(); return; }
        Object.assign(who, { email: who.pending.change.email, is_anonymous: false });
        if (!who.identities.some((i) => i.provider === 'email')) who.identities.push({ provider: 'email', sub: who.id, email: who.email });
        who.pending = {};
        out(200, session(who.id)); return;
      }
      if (b.type === 'email') {                       // a new player's code or a returning player's: both are looked for
        const who = byEmail(b.email);
        if (!who || !(fresh(who.pending.signup, b.token) || fresh(who.pending.magiclink, b.token))) { no(); return; }
        who.confirmed = true; who.pending = {};
        out(200, session(who.id)); return;
      }
      oops(400, 'validation_failed', 'Unsupported verification type'); return;
    }
    if (u.pathname === '/auth/v1/logout' && req.method === 'POST') {
      const me = claims(tokenOf(req));
      if (!me) { oops(401, 'bad_jwt', 'invalid JWT'); return; }
      const scope = u.searchParams.get('scope') || 'global';
      for (const [k, v] of sessions) if (v.uid === me.id && (scope === 'global' || (scope === 'local') === (v.sid === me.sid))) sessions.delete(k);
      res.writeHead(204, CORS); res.end(); return;
    }
    if (u.pathname === '/rest/v1/progress' && req.method === 'GET') {
      const me = claims(tokenOf(req));
      if (!me) { out(401, { code: 'PGRST301', message: 'JWT expired' }); return; }
      const id = (u.searchParams.get('user_id') || '').replace(/^eq\./, ''), since = +(u.searchParams.get('rev') || 'gt.0').replace(/^gt\./, '');
      out(200, id && id !== me.id ? [] : progress.load(me, since)); return;
    }
    if (u.pathname === '/rest/v1/rpc/progress_save' && req.method === 'POST') {
      const me = claims(tokenOf(req));
      if (!me) { out(401, { code: 'PGRST301', message: 'JWT expired' }); return; }
      const b = await read();
      try { out(200, progress.save(me, b.p_data, b.p_rev == null ? null : b.p_rev)); } catch (e) { out(e.status || 500, { code: e.code, message: e.text || String(e) }); }
      return;
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
