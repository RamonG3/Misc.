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
//   view { match, since? }        -> what you may see (referee view) + { status, names, deadline, now, next }
//   move { match, seq, move }     -> the same, after your move. move: { t, v } or { t: 'concede' }
//   rematch { match, deck, cape, version }  -> { match, waiting } or { match, started }
//                                    after a duel: offer a rematch (a room the other player can take), or take
//                                    up theirs (the new duel starts). { match, off: true } withdraws your offer.
//
// The bank (phase 3b, ONLINE_BRIEF.md): every player's GP, cards and packs live on the server. Each answers
// { bank: { gp, coll, packs, free, rev, at } } and more:
//   bank { snapshot? }            -> { bank, opened?, doubt? }      opens it the first time: the account's cloud save,
//                                    else this device's progress (snapshot) if honest play could have made it,
//                                    else the starter decks (doubt: why the device's progress wasn't taken)
//   earn { claims: [{ ref: 'bot:<seed>', result: 'win' | 'loss' | 'draw' }] }  -> { bank, done: [ref], refused: [{ ref, why }] }
//                                    bot duels, as the device reports them (believed: Ramon's call), each once
//   pack { ref: 'pack:<nonce>' } or { free: true }   -> { bank, cards: [{ name, rar }] }   200 GP (the tutorial's: free,
//                                    once); the server picks the cards. The same ref again gives the same pack.
//   replace { snapshot }          -> { bank }           "keep this device's progress": the same check as opening
//   reset                         -> { bank }           back to the starter decks and 0 GP (Story Mode's rewards stay)
//   story { keys: ['ch1:A'] }     -> { bank, done: [key] }  Story Mode: the card a road gives (packs.js STORY_REWARDS),
//                                    as the device reports finishing it (believed, like bot duels), each key once ever
// Online duels are paid by the database as the result is recorded (duel_append), and a deck taken online must
// be one you own (challenge, join, rematch).
//
// A decision whose time has run out is settled on the next request from either player.
import S from './gen/engine.js';
import DATA from './gen/cards.js';
import RARITY from './gen/rarity.js';
import DR from './gen/deckrules.js';
import PK from './gen/packs.js';
import PD from './gen/packdata.js';
import REF from './gen/referee.js';
import { VERSION, FINGERPRINT, FIXTURE } from './gen/version.js';

S.setData(DATA);
const F = REF.make(S, DR.make(S.CARDS, RARITY));
const P = PK.make(S.CARDS, PD.set1, PD.decks);

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
  const base = { match: m.id, status: m.status, kind: m.kind, code: m.code, now: new Date().toISOString(), next: m.next || null };
  if (m.status === 'waiting' || m.status === 'cancelled' || !m.seats) return base;
  if (m.version !== VERSION) return { ...base, status: 'stopped', names: m.names, result: m.result, why: 'This duel was played on an older version of the game.' };
  const seat = m.seats.indexOf(uid);
  return { ...base, names: m.names, deadline: m.deadline, ...F.view(data.secret.setup, data.moves, seat, since) };
}

// A room for a friend to join, with a fresh code.
async function openRoom(uid, entry) {
  for (let tries = 0; tries < 6; tries++) {
    const code = newCode();
    try { return { match: await rpc('duel_create', { p_host: uid, p_code: code, p_version: VERSION, p_entry: entry }), code }; }
    catch (e) { if (!/23505|duplicate key/.test(e.message)) throw e; }
  }
  throw new Oops('busy', 'Please try again.', 503);
}
// The guest takes the seat: the coin toss, the seed, and the duel starts.
async function startDuel(uid, room, entry) {
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
  return room.id;
}
const fresh = (room) => Date.now() - Date.parse(room.created_at) <= ROOM_HOURS * 3600e3;

// ---------- the bank ----------
const rnd = () => crypto.getRandomValues(new Uint32Array(1))[0] / 2 ** 32;
const MAX_CLAIMS = 50;
async function openBank(uid, snapshot) {
  const had = await rpc('bank_get', { p_user: uid });
  if (had) return { bank: had };
  const saved = await rpc('bank_progress', { p_user: uid });     // an account's cloud save is believed as it is
  const from = saved || (snapshot && typeof snapshot === 'object' ? snapshot : null);
  const c = from ? P.plausible(from) : null;
  let how, doubt;
  if (saved) how = 'progress';
  else if (c && c.ok) how = 'device';
  else { how = 'starter'; if (c) doubt = c.why; }
  const v = how === 'starter' ? { gp: 0, coll: P.starter().coll, packs: 0 } : c;
  const bank = await rpc('bank_open', { p_user: uid, p_gp: v.gp, p_coll: v.coll, p_packs: v.packs,
    p_free: how !== 'starter' && !!from.tutorialPack, p_how: how });
  if (how !== 'starter') await storyKept(uid, c.story);
  return doubt ? { bank, opened: how, doubt } : { bank, opened: how };
}
// Story rewards a device already holds when its progress becomes the bank: logged as given (cards already in),
// so finishing that road again can't give a second copy.
async function storyKept(uid, keys) {
  for (const key of keys || []) await rpc('bank_apply', { p_user: uid, p_ref: `story:${key}`, p_kind: 'story-kept', p_gp: 0, p_cards: null, p_packs: 0, p_free: false });
}
// a collection with exactly the story's cards for these roads (a reset or a replace keeps them), the rest as it was
async function withStory(uid, coll, keys) {
  const out = { ...coll };
  for (const card of Object.values(PK.STORY_REWARDS)) delete out[card];
  const refs = new Set([...((await rpc('bank_story_refs', { p_user: uid })) || []), ...(keys || []).map((k) => `story:${k}`)]);
  for (const ref of refs) { const card = PK.STORY_REWARDS[ref.slice('story:'.length)]; if (card) out[card] = (out[card] || 0) + 1; }
  return out;
}
async function bankOrOops(uid) {
  const b = await rpc('bank_get', { p_user: uid });
  if (!b) throw new Oops('nobank', 'Your progress isn\'t on the server yet. Go back to the menu and try again.', 409);
  return b;
}
// a deck taken online must be one you own
async function owns(uid, cards) {
  const b = await bankOrOops(uid);
  const short = Object.entries(cards).filter(([n, k]) => !P.isBasic(n) && k > (b.coll[n] || 0)).map(([n]) => n);
  if (short.length) throw new Oops('deck', `You don't own enough copies of ${short.join(', ')}.`);
}
const packList = (cards) => Object.entries(cards || {}).flatMap(([name, k]) => Array(k).fill(name))
  .map((name) => ({ name, rar: P.rarityOf(name) })).sort((a, b) => 'CURL'.indexOf(a.rar) - 'CURL'.indexOf(b.rar));

// ---------- the requests ----------
const OPS = {
  async hello(uid) {
    const [duels, me, whole] = await Promise.all([rpc('duel_mine', { p_user: uid }), profile(uid), checkIntact()]);
    return { version: VERSION, name: (me || {}).name || null, duels, intact: whole };
  },
  async challenge(uid, b) {
    checkVersion(b);
    const entry = entryOf(b);
    await owns(uid, entry.cards);
    if (!(await profile(uid))) throw new Oops('noname', 'Pick a name first.');
    return openRoom(uid, entry);
  },
  async peek(uid, b) {
    const room = await rpc('duel_find', { p_code: cleanCode(b.code) });
    if (!room) throw new Oops('nocode', 'No duel is waiting with that code.', 404);
    if (!fresh(room)) throw new Oops('nocode', 'That challenge has expired.', 404);
    return { host: room.host_name, yours: room.host === uid };
  },
  async join(uid, b) {
    checkVersion(b);
    const entry = entryOf(b);
    const room = await rpc('duel_find', { p_code: cleanCode(b.code) });
    if (!room || !fresh(room)) throw new Oops('nocode', 'No duel is waiting with that code.', 404);
    if (room.host === uid) throw new Oops('own', "That's your own challenge.");
    if (room.version !== VERSION) throw new Oops('nocode', 'That challenge was made on an older version of the game.', 404);
    await owns(uid, entry.cards);
    return { match: await startDuel(uid, room, entry) };
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
  async rematch(uid, b) {
    const old = (await load(uid, b.match)).match;
    if (b.off) {                                    // withdraw your offer (closes your room, if it's still open)
      if (old.next) await rpc('duel_cancel', { p_match: old.next, p_host: uid });
      return { ok: true };
    }
    if (old.status !== 'done') throw new Oops('notover', "That duel isn't over.", 409);
    checkVersion(b);
    if (old.version !== VERSION) throw new Oops('update', 'That duel was played on an older version of the game.', 409);
    const entry = entryOf(b);
    await owns(uid, entry.cards);
    if (!(await profile(uid))) throw new Oops('noname', 'Pick a name first.');
    for (let tries = 0; tries < 4; tries++) {
      const m = (await load(uid, old.id)).match;
      const room = m.next ? await rpc('duel_room', { p_match: m.next }) : null;
      if (room && room.status === 'waiting' && fresh(room) && room.version === VERSION) {
        if (room.host === uid) return { match: room.id, waiting: true };            // you offered already
        try { return { match: await startDuel(uid, room, entry), started: true }; }  // take up their offer
        catch (e) { if (e instanceof Oops && e.code === 'taken') continue; throw e; }
      }
      if (room && room.status === 'playing' && (room.host === uid || room.guest === uid)) return { match: room.id, started: true };
      if (room && room.status === 'done') throw new Oops('over', 'That rematch has been played.', 409);
      const mine = await openRoom(uid, entry);       // offer one: a room, and the old duel pointing at it
      if (await rpc('duel_next', { p_match: old.id, p_user: uid, p_next: mine.match, p_version: VERSION })) return { match: mine.match, waiting: true };
      await rpc('duel_cancel', { p_match: mine.match, p_host: uid });     // their offer got in first: take that one instead
    }
    throw new Oops('busy', 'Please try again.', 503);
  },
  async bank(uid, b) {
    return openBank(uid, b.snapshot);
  },
  async earn(uid, b) {
    const claims = Array.isArray(b.claims) ? b.claims.slice(0, MAX_CLAIMS) : [];
    let bank = await bankOrOops(uid);
    const done = [], refused = [];
    for (const c of claims) {
      const ref = c && typeof c.ref === 'string' ? c.ref : '', pay = c && { win: 100, loss: 50, draw: 50 }[c.result];
      if (!/^bot:[\w-]{1,40}$/.test(ref) || !pay) { refused.push({ ref, why: 'bad' }); continue; }
      const r = await rpc('bank_apply', { p_user: uid, p_ref: ref, p_kind: 'bot', p_gp: pay, p_cards: null, p_packs: 0, p_free: false });
      if (r.bank) bank = r.bank;
      if (r.ok) done.push(ref); else refused.push({ ref, why: r.why });
    }
    return { bank, done, refused };
  },
  async pack(uid, b) {
    await bankOrOops(uid);
    const free = b.free === true, ref = free ? 'free' : String(b.ref || '');
    if (!free && !/^pack:[\w-]{8,40}$/.test(ref)) throw new Oops('bad', 'Unknown request.');
    const cards = {};
    for (const c of P.roll(rnd)) cards[c.name] = (cards[c.name] || 0) + 1;
    const r = await rpc('bank_apply', { p_user: uid, p_ref: ref, p_kind: free ? 'free' : 'pack', p_gp: free ? 0 : -PK.PACK_COST, p_cards: cards, p_packs: 1, p_free: free });
    if (!r.ok) {
      if (r.why === 'short') throw new Oops('short', `A pack costs ${PK.PACK_COST} GP.`, 409);
      if (r.why === 'free') throw new Oops('free', 'Your free pack has already been opened.', 409);
      throw new Oops(r.why || 'bank', 'Please try again.', 409);
    }
    return { bank: r.bank, cards: packList(r.cards) };
  },
  async replace(uid, b) {
    const c = P.plausible(b.snapshot);
    if (!c.ok) throw new Oops('doubt', 'That progress doesn\'t add up, so it can\'t replace the account\'s.', 409);
    const free = !!(b.snapshot && b.snapshot.tutorialPack);
    const bank = await rpc('bank_set', { p_user: uid, p_ref: `replace:${Date.now()}`, p_kind: 'replace', p_gp: c.gp, p_coll: await withStory(uid, c.coll, c.story), p_packs: c.packs, p_free: free });
    await storyKept(uid, c.story);
    return { bank };
  },
  async reset(uid) {      // the story's rewards are kept: finishing a road again never gives its card twice
    const coll = await withStory(uid, P.starter().coll, []);
    return { bank: await rpc('bank_set', { p_user: uid, p_ref: `reset:${Date.now()}`, p_kind: 'reset', p_gp: 0, p_coll: coll, p_packs: 0, p_free: false }) };
  },
  async story(uid, b) {
    const keys = Array.isArray(b.keys) ? b.keys.slice(0, 20) : [];
    let bank = await bankOrOops(uid);
    const done = [];
    for (const key of keys) {
      const card = typeof key === 'string' && Object.prototype.hasOwnProperty.call(PK.STORY_REWARDS, key) ? PK.STORY_REWARDS[key] : null;
      if (!card) continue;
      const r = await rpc('bank_apply', { p_user: uid, p_ref: `story:${key}`, p_kind: 'story', p_gp: 0, p_cards: { [card]: 1 }, p_packs: 0, p_free: false });
      if (r.bank) bank = r.bank;
      if (r.ok) done.push(key);
    }
    return { bank, done };
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
