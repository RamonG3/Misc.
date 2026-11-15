// The `duel` Edge Function: the online referee's front door (Supabase, Deno).
//
// Every request is a POST of { op, ... } from a signed-in player (the platform checks the JWT first:
// deploy with verify_jwt on). The referee itself is web/referee.js; server/build.js copies it, the
// synchronous engine, the card data and the deck rules into gen/ (never edit gen/ by hand).
//
//   hello                         -> { version, name, duels: [{ id, status, code, host }], intact }
//                                    your name, your open room or duel; intact: this copy of the rules is whole
//   challenge { deck, cape, version }      -> { match, code }       open a room for a friend
//   peek { code }                 -> { host, yours }               who is challenging you
//   join { code, deck, cape, version }     -> { match }             take the seat; the duel starts
//   cancel { match }              -> { ok }                         close your room before anyone joins
//   view { match, since? }        -> what you may see (referee view) + { status, names, deadline, now }
//   move { match, seq, move }     -> the same, after your move. move: { t, v } or { t: 'concede' }
//
// A decision whose time has run out is settled on the next request from either player.
import S from './gen/engine.js';
import DATA from './gen/cards.js';
import RARITY from './gen/rarity.js';
import DR from './gen/deckrules.js';
import REF from './gen/referee.js';
import { VERSION, FINGERPRINT, FIXTURE } from './gen/version.js';

S.setData(DATA);
const F = REF.make(S, DR.make(S.CARDS, RARITY));

const BASE = Deno.env.get('SUPABASE_URL');
const SECRET = (() => { try { return JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') || '{}').default || null; } catch { return null; } })();
const LEGACY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
const KEY = SECRET ? { apikey: SECRET } : { apikey: LEGACY, Authorization: `Bearer ${LEGACY}` };
const CODE_CHARS = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';     // no 0/O, 1/I/L
const ROOM_HOURS = 24;
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

class Oops extends Error {
  constructor(code, text, status = 400) { super(text); this.code = code; this.status = status; }
}

// ---------- the database (PostgREST, with the secret key) ----------
async function rpc(fn, args) {
  const r = await fetch(`${BASE}/rest/v1/rpc/${fn}`, { method: 'POST', headers: { ...KEY, 'Content-Type': 'application/json' }, body: JSON.stringify(args) });
  const text = await r.text();
  if (!r.ok) throw new Error(`${fn}: ${r.status} ${text}`);
  return text ? JSON.parse(text) : null;
}
async function profile(uid) {
  const r = await fetch(`${BASE}/rest/v1/profiles?select=id,name&id=eq.${encodeURIComponent(uid)}`, { headers: KEY });
  if (!r.ok) throw new Error(`profiles: ${r.status} ${await r.text()}`);
  return (await r.json())[0] || null;
}

// ---------- small things ----------
function userOf(req) {          // the platform has already checked the token's signature (verify_jwt)
  const h = req.headers.get('Authorization') || '';
  const parts = (h.startsWith('Bearer ') ? h.slice(7) : '').split('.');
  if (parts.length !== 3) return null;
  try {
    const b = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b + '='.repeat((4 - (b.length % 4)) % 4)), (c) => c.charCodeAt(0))));
    return claims.role === 'authenticated' && typeof claims.sub === 'string' ? claims.sub : null;
  } catch { return null; }
}
const randomInt = (n) => crypto.getRandomValues(new Uint32Array(1))[0] % n;
const newCode = () => Array.from({ length: 5 }, () => CODE_CHARS[randomInt(CODE_CHARS.length)]).join('');
const cleanCode = (c) => String(c || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 5);
function checkVersion(b) {
  if (b.version !== VERSION) throw new Oops('update', 'This app is out of date. Reload it to get the new version.', 409);
}
function entryOf(b) {
  const problems = F.entry(b.deck, b.cape);
  if (problems.length) throw new Oops('deck', `That deck can't be used: ${problems.join('; ')}.`);
  const cards = {};
  for (const [n, k] of Object.entries(b.deck)) cards[n] = k;
  return { cards, cape: b.cape };
}
// Does this copy of the engine and cards still play the fixed duel the build recorded? (Worked out once.)
let intact = null;
async function checkIntact() {
  if (intact !== null) return intact;
  const enc = new TextEncoder();
  const text = JSON.stringify(DATA) + JSON.stringify(RARITY) + JSON.stringify(F.selfPlay(FIXTURE));
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(text)));
  intact = Array.from(hash, (x) => x.toString(16).padStart(2, '0')).join('').slice(0, 16) === FINGERPRINT;
  if (!intact) console.error('The deployed engine or card data differs from the build.');
  return intact;
}
const deadlineFor = (pending, first) => (pending ? new Date(Date.now() + F.allowance(pending, first) * 1000).toISOString() : null);

async function load(uid, id) {
  if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/i.test(id)) throw new Oops('nomatch', 'No such duel.', 404);
  const data = await rpc('duel_load', { p_match: id });
  if (!data || (data.match.host !== uid && data.match.guest !== uid)) throw new Oops('nomatch', 'No such duel.', 404);
  return data;
}
// Record a referee outcome (a move, a timeout). False if another request got in first.
function record(m, seq, out) {
  return rpc('duel_append', { p_match: m.id, p_seq: seq, p_move: out.move, p_pending: out.pending, p_deadline: deadlineFor(out.pending, false),
    p_strikes: out.late, p_result: out.result });
}
// A decision that has run out of time is settled first, whoever is asking.
async function settle(uid, data) {
  for (let tries = 0; tries < 3; tries++) {
    const m = data.match;
    if (m.status !== 'playing' || !m.deadline || Date.now() < Date.parse(m.deadline)) return data;
    if (m.version !== VERSION) return data;
    const out = F.timeout(data.secret.setup, data.moves);
    if (!out) return data;
    await record(m, data.moves.length, out);       // if someone else got in first, the reload shows theirs
    data = await load(uid, m.id);
  }
  return data;
}
function seen(uid, data, since) {
  const m = data.match;
  const base = { match: m.id, status: m.status, kind: m.kind, code: m.code, now: new Date().toISOString() };
  if (m.status === 'waiting' || m.status === 'cancelled' || !m.seats) return base;
  if (m.version !== VERSION) return { ...base, status: 'stopped', names: m.names, result: m.result, why: 'This duel was played on an older version of the game.' };
  const seat = m.seats.indexOf(uid);
  return { ...base, names: m.names, deadline: m.deadline, ...F.view(data.secret.setup, data.moves, seat, since) };
}

// ---------- the requests ----------
const OPS = {
  async hello(uid) {
    const [duels, me, whole] = await Promise.all([rpc('duel_mine', { p_user: uid }), profile(uid), checkIntact()]);
    return { version: VERSION, name: (me || {}).name || null, duels, intact: whole };
  },
  async challenge(uid, b) {
    checkVersion(b);
    const entry = entryOf(b);
    if (!(await profile(uid))) throw new Oops('noname', 'Pick a name first.');
    for (let tries = 0; tries < 6; tries++) {
      const code = newCode();
      try { return { match: await rpc('duel_create', { p_host: uid, p_code: code, p_version: VERSION, p_entry: entry }), code }; }
      catch (e) { if (!/23505|duplicate key/.test(e.message)) throw e; }
    }
    throw new Oops('busy', 'Please try again.', 503);
  },
  async peek(uid, b) {
    const room = await rpc('duel_find', { p_code: cleanCode(b.code) });
    if (!room) throw new Oops('nocode', 'No duel is waiting with that code.', 404);
    const expired = Date.now() - Date.parse(room.created_at) > ROOM_HOURS * 3600e3;
    if (expired) throw new Oops('nocode', 'That challenge has expired.', 404);
    return { host: room.host_name, yours: room.host === uid };
  },
  async join(uid, b) {
    checkVersion(b);
    const entry = entryOf(b);
    const room = await rpc('duel_find', { p_code: cleanCode(b.code) });
    if (!room || Date.now() - Date.parse(room.created_at) > ROOM_HOURS * 3600e3) throw new Oops('nocode', 'No duel is waiting with that code.', 404);
    if (room.host === uid) throw new Oops('own', "That's your own challenge.");
    if (room.version !== VERSION) throw new Oops('nocode', 'That challenge was made on an older version of the game.', 404);
    const me = await profile(uid);
    if (!me) throw new Oops('noname', 'Pick a name first.');
    const data = await rpc('duel_load', { p_match: room.id });
    const hostFirst = randomInt(2) === 0;                       // the coin toss
    const seats = hostFirst ? [room.host, uid] : [uid, room.host];
    const entries = hostFirst ? [data.secret.host_entry, entry] : [entry, data.secret.host_entry];
    const names = hostFirst ? [room.host_name, me.name] : [me.name, room.host_name];
    const setup = { seed: randomInt(2 ** 31), decks: entries.map((e) => e.cards), capes: entries.map((e) => e.cape), names };
    const st = F.state(setup, []);
    const ok = await rpc('duel_start', { p_match: room.id, p_guest: uid, p_entry: entry, p_setup: setup, p_seats: seats, p_names: names,
      p_pending: st.pending, p_deadline: deadlineFor(st.pending, true) });
    if (!ok) throw new Oops('taken', 'Someone else has taken that duel.', 409);
    return { match: room.id };
  },
  async cancel(uid, b) {
    return { ok: !!(await rpc('duel_cancel', { p_match: String(b.match || ''), p_host: uid })) };
  },
  async view(uid, b) {
    const data = await settle(uid, await load(uid, b.match));
    return seen(uid, data, b.since);
  },
  async move(uid, b) {
    const data = await settle(uid, await load(uid, b.match));
    const m = data.match;
    if (m.status !== 'playing') throw new Oops('over', 'The duel is over.', 409);
    if (m.version !== VERSION) throw new Oops('update', 'This duel was played on an older version of the game.', 409);
    const seat = m.seats.indexOf(uid);
    let out;
    try { out = F.accept(data.secret.setup, data.moves, seat, b.seq, b.move); } catch (e) {
      if (e instanceof REF.Refused) throw new Oops(e.code, e.message, 409);
      throw e;
    }
    if (!(await record(m, data.moves.length, out))) throw new Oops('stale', 'The duel has moved on.', 409);
    return seen(uid, await load(uid, m.id), Number.isInteger(b.seq) ? b.seq : undefined);
  },
};

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: CORS });
  const reply = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...CORS, 'Content-Type': 'application/json' } });
  try {
    if (req.method !== 'POST') throw new Oops('method', 'POST only.', 405);
    const uid = userOf(req);
    if (!uid) throw new Oops('auth', 'Sign in first.', 401);
    const b = await req.json().catch(() => ({}));
    const op = b && typeof b.op === 'string' && Object.prototype.hasOwnProperty.call(OPS, b.op) ? OPS[b.op] : null;
    if (!op) throw new Oops('op', 'Unknown request.');
    return reply(await op(uid, b));
  } catch (e) {
    if (e instanceof Oops) return reply({ error: e.code, message: e.message }, e.status);
    console.error(e);
    return reply({ error: 'server', message: 'Something went wrong on the server.' }, 500);
  }
});
