// The `duel` Edge Function, run in node against a pretend database: node server/test_local.mjs [duels=40]
//
// Builds server/supabase/functions/duel/gen (as deployed: add --min to test the shrunk build), then:
// 1. the generated engine + referee give exactly the same views as web/referee.js on the source engine;
// 2. index.js, with Deno's few calls stood in for and the database calls answered from memory (mirroring
//    server/supabase/migrations/*.sql, see fake_db.mjs), plays whole duels between two "devices" that know only what the
//    server sends them: challenge, peek, join, view, move, timeouts on a moved clock, concede, refusals, and rematches.
// Ends "all passed".
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import { makeDb } from './fake_db.mjs';

const require = createRequire(import.meta.url);
process.removeAllListeners('warning');      // node's note that gen/ files are ES modules (they are, for Deno)
process.on('warning', () => {});
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, '..', 'web');
const MIN = process.argv.includes('--min');
const DUELS = +(process.argv.find((a) => /^\d+$/.test(a)) || 40);
execFileSync('node', [path.join(HERE, 'build.js'), ...(MIN ? ['--min'] : [])], { stdio: 'inherit' });

let failures = 0;
const fail = (m) => { failures += 1; if (failures <= 25) console.log('FAIL', m); };
const ok = (c, m) => { if (!c) fail(m); };
const J = (x) => JSON.stringify(x);

// ---------- 1. the generated build agrees with the source ----------
const G = {
  S: (await import(path.join(HERE, 'supabase/functions/duel/gen/engine.js'))).default,
  DATA: (await import(path.join(HERE, 'supabase/functions/duel/gen/cards.js'))).default,
  RAR: (await import(path.join(HERE, 'supabase/functions/duel/gen/rarity.js'))).default,
  DR: (await import(path.join(HERE, 'supabase/functions/duel/gen/deckrules.js'))).default,
  REF: (await import(path.join(HERE, 'supabase/functions/duel/gen/referee.js'))).default,
};
G.S.setData(G.DATA);
const GF = G.REF.make(G.S, G.DR.make(G.S.CARDS, G.RAR));
const data = JSON.parse(fs.readFileSync(path.join(WEB, 'cards.json'), 'utf8'));
const LV = require(path.join(WEB, 'bot_levels.js'));
const S = (() => { const m = { exports: {} }; new Function('module', 'window', LV.unwait(fs.readFileSync(path.join(WEB, 'engine.js'), 'utf8')))(m, {}); return m.exports; })();
S.setData(data);
const REF = require(path.join(WEB, 'referee.js'));
// Rarities: from the page's assets when they're here (Ramon's PC), else the copy the server build keeps
const set1 = fs.existsSync(path.join(WEB, 'arena_assets.js'))
  ? (() => { const a = fs.readFileSync(path.join(WEB, 'arena_assets.js'), 'utf8'); return JSON.parse(a.slice(a.indexOf('=') + 1).trim().replace(/;\s*$/, '')).set1; })()
  : Object.fromEntries(Object.entries(JSON.parse(fs.readFileSync(path.join(HERE, 'rarity.json'), 'utf8'))).map(([n, r]) => [n, { rarity: r }]));
const F = REF.make(S, require(path.join(WEB, 'deckrules.js')).make(S.CARDS, set1));
const POOL = S.DECKS.filter((d) => d.group === 'Starter decks' || d.group === 'Tournament decks');

function randomAgent(seed, moves) {
  const rng = new S.RNG(seed);
  const pick = (xs) => xs[Math.floor(rng.random() * xs.length)];
  return {
    choose: (g, i, legal) => { const a = rng.random() < 0.08 || !legal.length ? ['pass'] : pick(legal); moves.push({ seat: i, t: 'choose', v: a }); return a; },
    react: (g, j, a, s, src, opts) => { const c = rng.random() < 0.5 ? pick(opts) : null; moves.push({ seat: j, t: 'react', v: c }); return c; },
    discard_choice: (g, i) => { const n = pick(g.players[i].hand); moves.push({ seat: i, t: 'discard', v: n }); return n; },
  };
}
for (let n = 1; n <= 150; n++) {
  const a = POOL[n % POOL.length], b = POOL[(n * 5 + 1) % POOL.length];
  const inOrder = (c) => Object.fromEntries(Object.entries(c).sort(([x], [y]) => (x < y ? -1 : 1)));
  const setup = { seed: n * 99991, decks: [inOrder(a.cards), inOrder(b.cards)], capes: [a.capes[0], b.capes[0]], names: ['Ann', 'Bo'] };
  const moves = [];
  const g = new S.Game(setup.decks, [randomAgent(n, moves), randomAgent(n + 1e6, moves)], setup.seed, { capes: setup.capes }, setup.names);
  while (g.winner === null && g.turn < 400) g.play_turn();
  for (const seat of [0, 1]) {
    ok(J(GF.view(setup, moves, seat)) === J(F.view(setup, moves, seat)), `build duel ${n}: the deployed build's view differs`);
    const since = Math.max(0, moves.length - 7);
    ok(J(GF.view(setup, moves, seat, since)) === J(F.view(setup, moves, seat, since)), `build duel ${n}: the deployed build's catch-up differs`);
  }
  ok(J(GF.state(setup, moves.slice(0, 9))) === J(F.state(setup, moves.slice(0, 9))), `build duel ${n}: states differ`);
}
for (const d of POOL) ok(GF.entry(d.cards, d.capes[0]).length === 0, `build: deck ${d.name} refused`);
console.log('build: the deployed copy agrees with the source');

// ---------- 2. the function, against a pretend database ----------
let clock = Date.parse('2026-11-15T12:00:00Z');
Date.now = () => clock;
const { db, RPC } = makeDb({ now: () => clock });
const uuid = () => crypto.randomUUID();
const BASE = 'http://db.test';
let dbCalls = 0;
globalThis.fetch = async (url, init = {}) => {
  dbCalls += 1;
  const u = new URL(url);
  ok((init.headers || {}).apikey === 'sb_secret_test', 'the function called the database without the secret key');
  const send = (status, body) => new Response(body === undefined ? '' : J(body), { status });
  if (u.pathname.startsWith('/rest/v1/rpc/')) {
    const fn = RPC[u.pathname.slice('/rest/v1/rpc/'.length)];
    try { return send(200, fn(JSON.parse(init.body))); } catch (e) { return new Response(e.text || String(e), { status: e.status || 500 }); }
  }
  if (u.pathname === '/rest/v1/profiles') {
    const id = (u.searchParams.get('id') || '').replace(/^eq\./, '');
    return send(200, db.profiles.has(id) ? [db.profiles.get(id)] : []);
  }
  return send(404, { message: 'no such route' });
};
let handler = null;
globalThis.Deno = { env: { get: (k) => ({ SUPABASE_URL: BASE, SUPABASE_SECRET_KEYS: '{"default":"sb_secret_test"}' })[k] }, serve: (h) => { handler = h; } };
const errorLog = console.error;
console.error = (...a) => { fail(`the function logged an error: ${a.map(String).join(' ')}`); };
await import(path.join(HERE, 'supabase/functions/duel/index.js'));
const { VERSION } = await import(path.join(HERE, 'supabase/functions/duel/gen/version.js'));

const b64 = (o) => Buffer.from(J(o)).toString('base64url');
const jwt = (sub, role = 'authenticated') => `${b64({ alg: 'ES256' })}.${b64({ sub, role })}.sig`;
async function call(uid, body, token) {
  const r = await handler(new Request('http://fn.test/duel', { method: 'POST', headers: { Authorization: `Bearer ${token || jwt(uid)}`, 'Content-Type': 'application/json' }, body: J(body) }));
  return { status: r.status, body: await r.json() };
}
async function expectError(uid, body, code, msg, token) {
  const r = await call(uid, body, token);
  ok(r.status >= 400 && r.body.error === code, `${msg}: wanted "${code}", got ${r.status} ${J(r.body)}`);
}

// Two devices that know only what the server tells them
const ann = uuid(), bo = uuid(), cy = uuid();
db.profiles.set(ann, { id: ann, name: 'Ann' });
db.profiles.set(bo, { id: bo, name: 'Bo' });
const deckA = POOL[0], deckB = POOL[1];
const entryA = { deck: deckA.cards, cape: deckA.capes[0], version: VERSION };
const entryB = { deck: deckB.cards, cape: deckB.capes[0], version: VERSION };

await expectError(ann, { op: 'hello' }, 'auth', 'a token that is not a user', jwt(ann, 'anon'));
await expectError(ann, { op: 'hello' }, 'auth', 'no token', 'nonsense');
await expectError(ann, { op: 'fly' }, 'op', 'an unknown request');
await expectError(ann, { ...entryA, op: 'challenge', version: 'old' }, 'update', 'an out-of-date app');
await expectError(ann, { op: 'challenge', deck: { 'Bronze Dagger': 60 }, cape: deckA.capes[0], version: VERSION }, 'deck', 'an illegal deck');
await expectError(cy, { ...entryA, op: 'challenge' }, 'noname', 'a challenge without a name');
const hello0 = await call(ann, { op: 'hello' });
ok(hello0.body.version === VERSION && hello0.body.name === 'Ann' && hello0.body.duels.length === 0 && hello0.body.intact === true, `hello: ${J(hello0.body)}`);

const rng = new S.RNG(4242);
const pick = (xs) => xs[Math.floor(rng.random() * xs.length)];
let roomsChecked = false;
const tally = { duels: 0, moves: 0, ends: {}, refused: 0, timeouts: 0, catchups: 0 };
for (let n = 0; n < DUELS; n++) {
  const ch = await call(ann, { ...entryA, op: 'challenge' });
  ok(ch.status === 200 && /^[A-HJKMNP-Z2-9]{5}$/.test(ch.body.code), `challenge: ${J(ch.body)}`);
  const { match, code } = ch.body;
  const peek = await call(bo, { op: 'peek', code: code.toLowerCase() });
  ok(peek.body.host === 'Ann' && peek.body.yours === false, `peek: ${J(peek.body)}`);
  if (!roomsChecked) {
    roomsChecked = true;
    await expectError(ann, { ...entryA, op: 'join', code }, 'own', 'joining your own challenge');
    await expectError(cy, { ...entryA, op: 'join', code }, 'noname', 'joining without a name');
    await expectError(bo, { op: 'view', match: uuid() }, 'nomatch', 'viewing a duel that does not exist');
    const w = await call(ann, { op: 'view', match });
    ok(w.body.status === 'waiting' && !w.body.state, `a waiting room shows a duel: ${J(w.body)}`);
    await expectError(bo, { op: 'view', match }, 'nomatch', 'viewing someone else\'s room');
    // a room can be cancelled, and then can't be joined
    const c2 = await call(ann, { ...entryA, op: 'challenge' });
    ok(db.matches.get(match).status === 'cancelled', 'a new challenge did not close the old room');
    ok((await call(ann, { op: 'cancel', match: c2.body.match })).body.ok === true, 'cancel failed');
    await expectError(bo, { ...entryB, op: 'join', code: c2.body.code }, 'nocode', 'joining a cancelled room');
    n -= 1;   // the first room was closed by the second: start this duel again
    continue;
  }
  const jn = await call(bo, { ...entryB, op: 'join', code });
  ok(jn.status === 200 && jn.body.match === match, `join: ${J(jn.body)}`);
  if (n === 1) { db.profiles.set(cy, { id: cy, name: 'Cy' }); await expectError(cy, { ...entryB, op: 'join', code }, 'nocode', 'joining a duel already taken'); }

  const dev = { [ann]: { seen: 0, v: null }, [bo]: { seen: 0, v: null } };
  const refresh = async (uid) => {
    const d = dev[uid];
    const r = await call(uid, { op: 'view', match, since: d.v ? d.seen : undefined });
    ok(r.status === 200, `view: ${J(r.body)}`);
    if (d.v && r.body.steps) {
      tally.catchups += 1;
      ok(r.body.steps.length === r.body.seq - d.seen, `catch-up steps: ${r.body.steps.length} for ${r.body.seq - d.seen} moves`);
    }
    d.v = r.body; d.seen = r.body.seq;
    return r.body;
  };
  const walkAway = n % 5 === 3 ? (rng.random() < 0.5 ? ann : bo) : null;
  const concedeAt = n % 5 === 4 ? 10 + Math.floor(rng.random() * 20) : -1;
  let v = await refresh(ann);
  await refresh(bo);
  ok(v.names.includes('Ann') && v.names.includes('Bo') && v.state && v.pending && v.deadline, `first view: ${J(Object.keys(v))}`);
  for (let guard = 0; guard < 2000; guard++) {
    const a = await refresh(ann), b = await refresh(bo);
    if (a.result) { ok(J(a.result) === J(b.result) && a.status === 'done', 'the two devices disagree on the result'); break; }
    const seat = a.pending.seat;
    const uid = a.you === seat ? ann : bo, other = uid === ann ? bo : ann;
    const mine = dev[uid].v;
    ok(dev[other].v.pending.legal === undefined && dev[other].v.pending.opts === undefined, 'the waiting device can see the other player\'s choices');
    ok(dev[other].v.state.players[seat].hand === null, 'the waiting device can see the other hand');
    if (concedeAt >= 0 && mine.seq === concedeAt) {
      const r = await call(other, { op: 'move', match, seq: 0, move: { t: 'concede' } });
      ok(r.status === 200 && r.body.result && r.body.result.reason === 'concede' && r.body.result.winner === seat, `concede: ${J(r.body.result)}`);
      continue;
    }
    if (uid === walkAway && mine.seq > 6) {
      clock += (mine.pending.t === 'react' ? 20 : 60) * 1000 + 6000;        // nobody answers; the other device looks again
      tally.timeouts += 1;
      continue;
    }
    // now and then, things the server must refuse
    if (rng.random() < 0.2) {
      tally.refused += 1;
      const which = Math.floor(rng.random() * 3);
      if (which === 0) await expectError(other, { op: 'move', match, seq: mine.seq, move: { t: mine.pending.t, v: null } }, 'turn', 'a move from the player not deciding');
      if (which === 1) await expectError(uid, { op: 'move', match, seq: mine.seq - 1, move: { t: mine.pending.t, v: null } }, 'stale', 'a move from an old picture');
      if (which === 2 && mine.pending.t === 'choose') await expectError(uid, { op: 'move', match, seq: mine.seq, move: { t: 'choose', v: ['play', 'Dragon Claws of Make-Believe'] } }, 'illegal', 'an illegal move');
    }
    const p = mine.pending;
    let move;
    if (p.t === 'choose') move = { t: 'choose', v: rng.random() < 0.1 ? ['pass'] : pick(p.legal) };
    else if (p.t === 'react') move = { t: 'react', v: rng.random() < 0.5 ? pick(p.opts) : null };
    else move = { t: 'discard', v: pick(mine.state.players[mine.you].hand) };
    clock += 1500;
    const r = await call(uid, { op: 'move', match, seq: mine.seq, move });
    ok(r.status === 200 && r.body.seq === mine.seq + 1, `move: ${r.status} ${J(r.body).slice(0, 200)}`);
    if (r.status === 200 && r.body.steps) ok(J(r.body.steps[0].move) === J({ seat, ...move }) || move.t === 'choose', `the mover's step isn't its move: ${J(r.body.steps[0].move)}`);
    dev[uid].v = r.body; dev[uid].seen = r.body.seq;
    tally.moves += 1;
  }
  const end = dev[ann].v.result;
  ok(!!end, `duel ${n} never ended`);
  if (end) tally.ends[end.reason] = (tally.ends[end.reason] || 0) + 1;
  if (walkAway && end && end.reason === 'time') ok(end.winner !== dev[walkAway].v.you, 'the player who walked away won on time');
  await expectError(ann, { op: 'move', match, seq: dev[ann].v.seq, move: { t: 'concede' } }, 'over', 'a move after the end');
  // the server's copy replays to the same result
  const sec = db.secrets.get(match);
  ok(J(F.state(sec.setup, db.moves.get(match).map((x) => x.move)).result) === J(end), 'the stored duel replays to a different result');
  ok(!J(dev[ann].v).includes(String(sec.setup.seed)) || String(sec.setup.seed).length < 4, 'the seed reached a device');
  tally.duels += 1;
  if (process.env.VERBOSE) console.log(`  duel ${n}: ${dev[ann].v.seq} moves, ${end && end.reason}`);
}

// ---------- rematches ----------
async function quickDuel(host = ann, guest = bo) {        // a duel that ends at once: the host concedes
  const ch = await call(host, { ...entryA, op: 'challenge' });
  await call(guest, { ...entryB, op: 'join', code: ch.body.code });
  const r = await call(host, { op: 'move', match: ch.body.match, seq: 0, move: { t: 'concede' } });
  ok(r.body.result && r.body.result.reason === 'concede', `quick duel: ${J(r.body).slice(0, 120)}`);
  return ch.body.match;
}
const offer = (uid, match, extra = {}) => call(uid, { ...(uid === ann ? entryA : entryB), op: 'rematch', match, ...extra });
{
  // in progress: no rematch yet; a stranger can't offer one
  const ch = await call(ann, { ...entryA, op: 'challenge' });
  await call(bo, { ...entryB, op: 'join', code: ch.body.code });
  await expectError(bo, { ...entryB, op: 'rematch', match: ch.body.match }, 'notover', 'a rematch before the duel is over');
  await call(ann, { op: 'move', match: ch.body.match, seq: 0, move: { t: 'concede' } });
  await expectError(cy, { ...entryB, op: 'rematch', match: ch.body.match }, 'nomatch', 'a rematch from someone who did not play');
  await expectError(bo, { ...entryB, op: 'rematch', match: ch.body.match, version: 'old' }, 'update', 'a rematch from an old app');

  // Bo offers, Ann sees it and takes it up: the new duel starts with both of them
  const old = ch.body.match;
  const o1 = await offer(bo, old);
  ok(o1.status === 200 && o1.body.waiting === true && db.matches.get(old).next === o1.body.match, `rematch offer: ${J(o1.body)}`);
  ok((await offer(bo, old)).body.match === o1.body.match, 'offering twice opened a second room');
  ok((await call(ann, { op: 'view', match: old })).body.next === o1.body.match, 'the other player can\'t see the offer');
  const o2 = await offer(ann, old);
  ok(o2.status === 200 && o2.body.started === true && o2.body.match === o1.body.match, `taking up a rematch: ${J(o2.body)}`);
  const re = db.matches.get(o1.body.match);
  ok(re.status === 'playing' && re.seats.includes(ann) && re.seats.includes(bo), `the rematch didn't start: ${J(re).slice(0, 160)}`);
  ok((await offer(bo, old)).body.started === true, 'asking again after it started');
  const v = await call(bo, { op: 'view', match: re.id });
  ok(v.body.status === 'playing' && v.body.state && v.body.pending, 'the rematch can\'t be viewed');
  await call(ann, { op: 'move', match: re.id, seq: 0, move: { t: 'concede' } });
  await expectError(ann, { ...entryA, op: 'rematch', match: old }, 'over', 'a second rematch from the same duel');

  // an offer can be withdrawn; then the other player's offer stands
  const old2 = await quickDuel();
  const w1 = await offer(ann, old2);
  ok((await offer(ann, old2, { off: true })).body.ok === true, 'withdrawing an offer');
  ok(db.matches.get(w1.body.match).status === 'cancelled' && db.matches.get(old2).next === null, 'a withdrawn offer is still open');
  const w2 = await offer(bo, old2);
  ok(w2.body.waiting === true && w2.body.match !== w1.body.match, `an offer after a withdrawn one: ${J(w2.body)}`);
  // a new challenge closes the open offer too
  await call(bo, { ...entryB, op: 'challenge' });
  ok(db.matches.get(old2).next === null, 'a new challenge left the rematch offer standing');
  // two offers at once: the second finds the first and takes it
  const w3 = await offer(ann, old2);
  ok(!RPC.duel_next({ p_match: old2, p_user: bo, p_next: uuid(), p_version: VERSION }), 'a second offer replaced a standing one');
  const w4 = await offer(bo, old2);
  ok(w4.body.started === true && w4.body.match === w3.body.match, `the second offer didn't take the first: ${J(w4.body)}`);
  // an offer on an older version doesn't stand in the way
  const old3 = await quickDuel();
  const w5 = await offer(ann, old3);
  db.matches.get(w5.body.match).version = 'old';
  const w6 = await offer(bo, old3);
  ok(w6.body.waiting === true && w6.body.match !== w5.body.match, `an offer from an older version stood: ${J(w6.body)}`);
  tally.rematches = 4;
}

console.error = errorLog;
console.log(`rematches: offered, taken up, withdrawn, replaced, raced`);
console.log(`function: ${tally.duels} duels, ${tally.moves} moves, ${tally.refused} bad moves tried, ${tally.timeouts} clock jumps, ${tally.catchups} catch-ups, ends ${J(tally.ends)}, ${dbCalls} database calls`);
console.log(failures ? `${failures} FAILED` : 'all passed');
process.exit(failures ? 1 : 0);
