// The online referee (web/referee.js), headless: node web/test/referee_test.js [duels=2000] [driven=150]
//
// 1. Mirror duels: bots (and a random player) duel on the engine; the move list is then replayed through the
//    referee, which must reach the same end. Every picture either player would get is checked for leaks:
//    - every field of Player and Game is sorted into "public" or "hidden" (a new engine field fails here);
//    - at each decision, the other player's hand and deck list, both decks' order and the seed are dealt
//      again at random, and the picture (and the choices offered) must not change;
//    - the log, effects and moves never name a card the player hasn't been able to see.
// 2. Driven duels: played through accept()/timeout() one move at a time, as the server will. Illegal,
//    out-of-turn, wrong-seat, wrong-kind and stale moves must be refused; timeouts, strikes, forfeits and
//    concedes must end duels as the rules say; catching up move by move must match starting afresh.
// 3. Deck entry: legal decks pass, broken ones are refused.
// Ends "all passed".
const fs = require('fs');
const path = require('path');
const WEB = path.join(__dirname, '..');
const DUELS = +(process.argv[2] || 2000);
const DRIVEN = +(process.argv[3] || 150);

const data = JSON.parse(fs.readFileSync(path.join(WEB, 'cards.json'), 'utf8'));
const A = require(path.join(WEB, 'engine.js'));       // the bots read cards from this copy
A.setData(data);
const LV = require(path.join(WEB, 'bot_levels.js'));
const S = (() => { const m = { exports: {} }; new Function('module', 'window', LV.unwait(fs.readFileSync(path.join(WEB, 'engine.js'), 'utf8')))(m, {}); return m.exports; })();
S.setData(data);
const { HeuristicBot } = require(path.join(WEB, 'bot.js'));
// Rarities: from the page's assets when they're here (Ramon's PC), else the copy the server build keeps
const set1 = fs.existsSync(path.join(WEB, 'arena_assets.js'))
  ? (() => { const a = fs.readFileSync(path.join(WEB, 'arena_assets.js'), 'utf8'); return JSON.parse(a.slice(a.indexOf('=') + 1).trim().replace(/;\s*$/, '')).set1; })()
  : Object.fromEntries(Object.entries(JSON.parse(fs.readFileSync(path.join(WEB, '..', 'server', 'rarity.json'), 'utf8'))).map(([n, r]) => [n, { rarity: r }]));
const D = require(path.join(WEB, 'deckrules.js')).make(S.CARDS, set1);
const REF = require(path.join(WEB, 'referee.js'));
const F = REF.make(S, D);

let failures = 0;
const fail = (msg) => { failures += 1; if (failures <= 25) console.log('FAIL', msg); };
const ok = (cond, msg) => { if (!cond) fail(msg); };
const J = (x) => JSON.stringify(x);

const CARD_NAMES = Object.keys(S.CARDS).sort((a, b) => b.length - a.length);   // longest first
const ALL = Object.keys(S.CARDS).filter((n) => S.CARDS[n].kind !== 'cape');
const POOL = S.DECKS.filter((d) => d.group === 'Starter decks' || d.group === 'Tournament decks');

// ---------- players for the tests ----------
function randomAgent(seed) {
  const rng = new S.RNG(seed);
  const pick = (xs) => xs[Math.floor(rng.random() * xs.length)];
  return {
    choose: (g, i, legal) => (rng.random() < 0.08 || !legal.length ? ['pass'] : pick(legal)),
    react: (g, j, amount, style, src, opts) => (rng.random() < 0.5 ? pick(opts) : null),
    discard_choice: (g, i) => pick(g.players[i].hand),
  };
}
function recorder(agent, seat, moves) {
  return {
    setup: (g) => agent.setup && agent.setup(g),
    choose: (g, i, legal) => { const a = agent.choose(g, i, legal); moves.push({ seat: i, t: 'choose', v: a }); return a; },
    react: (g, j, amount, style, src, opts) => { const c = agent.react ? agent.react(g, j, amount, style, src, opts) : null; moves.push({ seat: j, t: 'react', v: c === undefined ? null : c }); return c; },
    discard_choice: (g, i) => { const n = agent.discard_choice(g, i); moves.push({ seat: i, t: 'discard', v: n }); return n; },
  };
}
const inOrder = (cards) => Object.fromEntries(Object.entries(cards).sort(([x], [y]) => (x < y ? -1 : 1)));   // as the referee builds decks
function setupFor(n) {
  const a = POOL[n % POOL.length], b = POOL[(n * 7 + 3) % POOL.length];
  return { seed: (n * 2654435761) >>> 1, decks: [inOrder(a.cards), inOrder(b.cards)], capes: [a.capes[n % a.capes.length], b.capes[(n + 1) % b.capes.length]], names: ['Zezima', 'Woox'] };
}
const kindOf = (n) => ['heuristic', 'random', 'mixed'][n % 3];
function agentsFor(n) {
  const k = kindOf(n);
  if (k === 'heuristic') return [new HeuristicBot(n * 3 + 1), new HeuristicBot(n * 3 + 2)];
  if (k === 'random') return [randomAgent(n * 5 + 1), randomAgent(n * 5 + 2)];
  return [new HeuristicBot(n * 3 + 1), randomAgent(n * 5 + 2)];
}

// ---------- what a seat can see ----------
function publicNames(state, seat, into) {
  for (const [i, p] of state.players.entries()) {
    for (const n of p.discard) into.add(n);
    for (const n of p.removed) into.add(n);
    for (const n of Object.keys(p.materials)) into.add(n);
    for (const n of Object.keys(p.fresh)) into.add(n);
    for (const n of p.completed) into.add(n);
    for (const x of [p.weapon && p.weapon.name, p.shield && p.shield.name, p.armor, p.ammo && p.ammo.card, p.prayer, p.buff, p.quest && p.quest.name, p.cape_name]) if (x) into.add(x);
    if (i === seat) { for (const n of p.hand) into.add(n); for (const n of Object.keys(p.decklist)) into.add(n); }
  }
  if (state.location) into.add(state.location);
  for (const n of state.cape_table) into.add(n);
  for (const n of Object.keys(state.cape_claimed)) into.add(n);
  return into;
}
function moveNames(m, into) {
  const walk = (x) => { if (typeof x === 'string' && S.CARDS[x]) into.add(x); else if (Array.isArray(x)) x.forEach(walk); };
  walk(m.v);
  return into;
}
// Card names in `text` that aren't in `seen` (names that are part of a seen name don't count).
function unseen(text, seen) {
  let t = text;
  for (const n of CARD_NAMES) if (seen.has(n)) t = t.split(n).join(' ');
  return CARD_NAMES.filter((n) => !seen.has(n) && t.includes(n));
}
function checkShape(state, seat, where) {
  ok(J(Object.keys(state).sort()) === J([...REF.GAME_PUBLIC, 'players'].sort()), `${where}: game fields ${Object.keys(state)}`);
  const want = J([...REF.PLAYER_PUBLIC, 'lp', 'handSize', 'hand', 'decklist', 'left'].sort());
  state.players.forEach((p, i) => {
    ok(J(Object.keys(p).sort()) === want, `${where}: player ${i} fields`);
    if (i !== seat) ok(p.hand === null && p.decklist === null && p.left === null, `${where}: the other player's hand, list or deck is showing`);
    else {
      ok(Array.isArray(p.hand) && p.hand.length === p.handSize, `${where}: your hand is missing`);
      const n = Object.values(p.left || {}).reduce((s, k) => s + k, 0);
      ok(n === p.lp && J(Object.keys(p.left)) === J(Object.keys(p.left).sort()), `${where}: what's left in your deck doesn't add up (${n} for ${p.lp})`);
    }
  });
}
function classified(g) {
  for (const k of Object.keys(g)) ok(REF.GAME_PUBLIC.includes(k) || REF.GAME_HIDDEN.includes(k), `Game field "${k}" is in neither list in referee.js`);
  for (const p of g.players) for (const k of Object.keys(p)) ok(REF.PLAYER_PUBLIC.includes(k) || REF.PLAYER_HIDDEN.includes(k), `Player field "${k}" is in neither list in referee.js`);
}

// At a decision: deal everything `seat` can't see again at random; its picture must not change.
function mutationCheck(g, seat, ask, rng, where) {
  const before = J(F.seatState(g, seat));
  const wantLegal = ask.seat === seat && ask.t === 'choose' ? J(F.canon([...g.legal_actions(seat), ['pass']])) : null;
  const me = g.players[seat], op = g.players[1 - seat];
  const saved = { opHand: op.hand.slice(), opDeck: op.deck.slice(), opList: op.decklist, myDeck: me.deck.slice(), s: g.rng.s };
  const rand = () => ALL[Math.floor(rng.random() * ALL.length)];
  op.hand = op.hand.map(rand);
  op.deck = op.deck.map(rand);
  op.decklist = { [rand()]: 60 };
  rng.shuffle(me.deck);
  g.rng.s = (g.rng.s * 7 + 12345) >>> 0;
  ok(J(F.seatState(g, seat)) === before, `${where}: the picture depends on something hidden`);
  if (wantLegal !== null) ok(J(F.canon([...g.legal_actions(seat), ['pass']])) === wantLegal, `${where}: the choices depend on something hidden`);
  op.hand = saved.opHand; op.deck = saved.opDeck; op.decklist = saved.opList; me.deck = saved.myDeck; g.rng.s = saved.s;
}

// ---------- 1. mirror duels ----------
const t0 = Date.now();
const tally = { decisions: 0, results: {}, maxMoves: 0, pointsChecked: 0, viewBytes: 0, views: 0 };
for (let n = 1; n <= DUELS; n++) {
  const setup = setupFor(n);
  const moves = [];
  const bots = agentsFor(n);
  const g = new S.Game(setup.decks, [recorder(bots[0], 0, moves), recorder(bots[1], 1, moves)], setup.seed, { capes: setup.capes }, setup.names);
  while (g.winner === null && g.turn < REF.MAX_TURNS) g.play_turn();
  classified(g);
  tally.decisions += moves.length;
  tally.maxMoves = Math.max(tally.maxMoves, moves.length);

  const st = F.state(setup, moves);
  if (n <= 50) {      // the order the database hands a deck list back in doesn't matter
    const shuffled = { ...setup, decks: setup.decks.map((d) => Object.fromEntries(Object.entries(d).reverse())) };
    ok(J(F.view(shuffled, moves, 0)) === J(F.view(setup, moves, 0)), `duel ${n}: a deck list in another order gave another duel`);
  }
  ok(st.pending === null && st.result && st.result.winner === g.winner, `duel ${n}: the referee's duel ended differently`);
  ok(st.result.reason === (g.winner === null ? 'turns' : g.end_reason), `duel ${n}: end reason ${st.result.reason} vs ${g.end_reason}`);
  tally.results[st.result.reason] = (tally.results[st.result.reason] || 0) + 1;

  const rng = new S.RNG(n * 31 + 7);
  for (const seat of [0, 1]) {
    const mutateAll = n <= 300;
    const r = F.replay(setup, moves, {
      viewer: seat, since: 0,
      onPoint: (gg, k, ask) => { if (mutateAll || rng.random() < 0.1) { mutationCheck(gg, seat, ask, rng, `duel ${n} seat ${seat} move ${k}`); tally.pointsChecked += 1; } },
    });
    ok(J(r.result) === J(st.result), `duel ${n}: a viewer's replay ended differently`);
    const seen = new Set();
    publicNames(r.points[0].state, seat, seen);
    for (const name of unseen(J(r.g.log.slice(0, r.points[0].log)), seen)) fail(`duel ${n} seat ${seat}: the opening log names ${name}`);
    for (let m = 0; m < moves.length; m++) {
      const to = r.points[m + 1];
      checkShape(to.state, seat, `duel ${n} seat ${seat} after move ${m}`);
      publicNames(to.state, seat, seen);
      moveNames(moves[m], seen);
      const said = J(r.g.log.slice(r.points[m].log, to.log)) + J(r.g.events.slice(r.points[m].ev, to.ev));
      for (const name of unseen(said, seen)) fail(`duel ${n} seat ${seat} move ${m}: the log names ${name}, which it can't see`);
    }
    // The views the server would send: afresh, and catching up from a few places.
    const full = F.view(setup, moves, seat);
    tally.viewBytes += J(full).length; tally.views += 1;
    for (const name of unseen(J(full).replace(J(full.state.players[seat].hand), '').replace(J(full.state.players[seat].decklist), ''), seen)) fail(`duel ${n} seat ${seat}: the view names ${name}`);
    ok(J(full.state) === J(r.points[r.points.length - 1].state), `duel ${n}: a fresh view disagrees with the replay`);
    for (const since of [moves.length - 1, moves.length - 5, moves.length - REF.MAX_STEPS]) {
      if (since < 0) continue;
      const v = F.view(setup, moves, seat, since);
      ok(v.steps && v.steps.length === moves.length - since, `duel ${n}: catching up from ${since} gave ${v.steps && v.steps.length} steps`);
      if (v.steps && v.steps.length) ok(J(v.steps[v.steps.length - 1].state) === J(full.state), `duel ${n}: the last step isn't the duel as it stands`);
      ok(!v.log, `duel ${n}: a catch-up view resent the whole log`);
    }
  }
  if (n % 500 === 0) console.log(`  ${n} mirror duels…`);
}
const avgBytes = Math.round(tally.viewBytes / Math.max(1, tally.views));
console.log(`mirror: ${DUELS} duels, ${tally.decisions} decisions (max ${tally.maxMoves} in one duel), ${tally.pointsChecked} pictures dealt again, ends ${J(tally.results)}, a fresh view at the end ≈ ${avgBytes} bytes, ${((Date.now() - t0) / 1000).toFixed(1)} s`);

// ---------- 2. driven duels ----------
const t1 = Date.now();
const refused = (fn, code, msg) => {
  try { fn(); fail(`${msg}: was accepted`); } catch (e) {
    if (!(e instanceof REF.Refused)) fail(`${msg}: threw ${e.message}`);
    else if (code && e.code !== code) fail(`${msg}: refused as "${e.code}", not "${code}"`);
  }
};
const dtally = { moves: 0, refusals: 0, late: 0, ends: {} };
for (let n = 1; n <= DRIVEN; n++) {
  const setup = setupFor(n + 50000);
  const rng = new S.RNG(n * 977 + 3);
  const playStyle = n % 4;          // 0: plays it out; 1: walks away (times out); 2: concedes at some point; 3: now and then late
  const quitAt = 5 + Math.floor(rng.random() * 40);
  let moves = [];
  let st = F.state(setup, moves);
  ok(st.pending && st.pending.seat === 0 && st.pending.t === 'choose', `driven ${n}: the duel doesn't open with the first player choosing`);
  const bots = [new HeuristicBot(n * 11 + 1), new HeuristicBot(n * 11 + 2)];
  let guard = 0;
  while (!st.result && guard++ < 3000) {
    const r = F.replay(setup, moves);
    const p = r.pending, g = r.g;
    const seat = p.seat;
    bots[0].setup(g); bots[1].setup(g);
    // The legal answer, by the bot that holds that seat
    let v;
    if (p.t === 'choose') { const legal = p.data.legal.filter((a) => a[0] !== 'pass'); v = legal.length ? bots[seat].choose(g, seat, legal) : ['pass']; }
    else if (p.t === 'react') v = bots[seat].react(g, seat, p.data.amount, p.data.style, p.data.src, p.data.opts);
    else v = bots[seat].discard_choice(g, seat);
    if (v === undefined) v = null;
    const seq = moves.length;
    // Things that must be refused (one or two per decision, at random, to keep it quick)
    if (rng.random() < 0.5) {
      dtally.refusals += 1;
      const which = Math.floor(rng.random() * 6);
      if (which === 0) refused(() => F.accept(setup, moves, 1 - seat, seq, { t: p.t, v }), 'turn', `driven ${n}: a move from the wrong player`);
      if (which === 1) refused(() => F.accept(setup, moves, seat, seq + (rng.random() < 0.5 ? 1 : -1), { t: p.t, v }), 'stale', `driven ${n}: a move from an old picture`);
      if (which === 2) refused(() => F.accept(setup, moves, seat, seq, { t: p.t === 'choose' ? 'discard' : 'choose', v }), 'kind', `driven ${n}: the wrong kind of move`);
      if (which === 3) {
        const hand = g.players[seat].hand;
        const notHeld = ALL.find((c) => !hand.includes(c) && !g.players[seat].discard.includes(c));
        const bad = p.t === 'choose' ? ['play', notHeld] : p.t === 'react' ? notHeld : notHeld;
        refused(() => F.accept(setup, moves, seat, seq, { t: p.t, v: bad }), 'illegal', `driven ${n}: an illegal ${p.t} (${J(bad)})`);
      }
      if (which === 4) refused(() => F.accept(setup, moves, 2, seq, { t: p.t, v }), 'seat', `driven ${n}: a move from someone not in the duel`);
      if (which === 5 && p.t === 'choose') refused(() => F.accept(setup, moves, seat, seq, { t: 'choose', v: ['swing', 'nonsense'] }), 'illegal', `driven ${n}: a made-up action`);
    }
    let out;
    const lateNow = (playStyle === 1 && moves.length >= quitAt && seat === 1) || (playStyle === 3 && rng.random() < 0.04);
    if (playStyle === 2 && moves.length === quitAt) out = F.accept(setup, moves, rng.random() < 0.5 ? 0 : 1, 0, { t: 'concede' });   // concede ignores seq
    else if (lateNow) { out = F.timeout(setup, moves); dtally.late += 1; }
    else out = F.accept(setup, moves, seat, seq, { t: p.t, v });
    ok(out.moves.length === moves.length + 1, `driven ${n}: a move wasn't added`);
    if (out.move.t === 'forfeit') ok(out.result && out.result.winner === 1 - out.move.seat && out.result.reason === 'time', `driven ${n}: a forfeit didn't lose`);
    if (out.move.t === 'concede') ok(out.result && out.result.winner === 1 - out.move.seat && out.result.reason === 'concede', `driven ${n}: a concede didn't lose`);
    if (out.move.late) {
      ok(out.late[out.move.seat] === st.late[out.move.seat] + 1 && out.late[out.move.seat] < REF.STRIKES, `driven ${n}: strikes didn't count`);
      if (out.move.t === 'choose') ok(J(out.move.v) === J(['pass']), `driven ${n}: running out of time didn't end the turn`);
      if (out.move.t === 'react') ok(out.move.v === null, `driven ${n}: running out of time reacted`);
    }
    if (rng.random() < 0.15) {        // catching up must match starting afresh, for both seats
      const s = Math.floor(rng.random() * 2), since = Math.max(0, out.moves.length - 1 - Math.floor(rng.random() * 6));
      const a = F.view(setup, out.moves, s, since), b = F.view(setup, out.moves, s);
      ok(J(a.state) === J(b.state) && J(a.pending) === J(b.pending), `driven ${n}: catching up disagrees with starting afresh`);
      if (a.steps) ok(J(a.steps[a.steps.length - 1].state) === J(b.state), `driven ${n}: the last step isn't the duel as it stands`);
      if (b.pending && b.pending.seat === s) ok(b.pending.t === 'choose' ? Array.isArray(b.pending.legal) : b.pending.t === 'react' ? Array.isArray(b.pending.opts) : true, `driven ${n}: your choices are missing`);
      if (b.pending && b.pending.seat !== s) ok(!('legal' in b.pending) && !('opts' in b.pending), `driven ${n}: the other player's choices are showing`);
    }
    moves = out.moves;
    st = out;
    dtally.moves += 1;
  }
  ok(!!st.result, `driven ${n}: never ended`);
  if (playStyle === 1) ok(st.result.reason !== 'time' || st.result.winner === 0, `driven ${n}: the player who walked away didn't lose`);
  dtally.ends[st.result.reason] = (dtally.ends[st.result.reason] || 0) + 1;
  refused(() => F.accept(setup, moves, 0, moves.length, { t: 'choose', v: ['pass'] }), 'over', `driven ${n}: a move after the end`);
  refused(() => F.accept(setup, moves, 1, moves.length, { t: 'concede' }), 'over', `driven ${n}: a concede after the end`);
  ok(F.timeout(setup, moves) === null, `driven ${n}: a timeout after the end`);
  const again = F.state(setup, moves);
  ok(J(again.result) === J(st.result), `driven ${n}: the finished duel replays differently`);
  // Replays are the same every time
  ok(J(F.view(setup, moves, 0)) === J(F.view(setup, moves, 0)) && J(F.view(setup, moves, 1, Math.max(0, moves.length - 3))) === J(F.view(setup, moves, 1, Math.max(0, moves.length - 3))), `driven ${n}: views differ between replays`);
  // A move list that has been tampered with doesn't load
  if (moves.length > 4) {
    const bent = moves.map((m) => ({ ...m }));
    bent[2] = { ...bent[2], seat: 1 - bent[2].seat };
    let threw = false;
    try { F.state(setup, bent); } catch (e) { threw = true; }
    ok(threw, `driven ${n}: a tampered move list loaded`);
  }
}
console.log(`driven: ${DRIVEN} duels, ${dtally.moves} moves, ${dtally.refusals} bad moves tried, ${dtally.late} timeouts, ends ${J(dtally.ends)}, ${((Date.now() - t1) / 1000).toFixed(1)} s`);

// ---------- 3. deck entry ----------
for (const d of POOL) ok(F.entry(d.cards, d.capes[0]).length === 0, `deck "${d.name}" was refused: ${F.entry(d.cards, d.capes[0])}`);
const base = { ...POOL[0].cards };
const nonBasic = Object.keys(base).find((n) => !D.isBasic(n) && base[n] >= 1);
const minus = { ...base }; minus[nonBasic] -= 1; if (!minus[nonBasic]) delete minus[nonBasic];
ok(F.entry(minus, POOL[0].capes[0]).length > 0, '59 cards were allowed');
const trim = Object.keys(base).filter((n) => D.isBasic(n)).sort((a, b) => base[b] - base[a])[0];   // the most plentiful basic
const five = { ...base }; five[trim] -= 5 - base[nonBasic]; five[nonBasic] = 5;
ok(F.entry(five, POOL[0].capes[0]).some((p) => /More than 4/.test(p)), '5 copies were allowed');
const legends = ALL.filter((n) => D.rarityOf(n) === 'L' && D.real(n)).slice(0, 2);
const two = { ...base }; two[trim] -= 2; two[legends[0]] = 1; two[legends[1]] = 1;
ok(legends.length === 2 && F.entry(two, POOL[0].capes[0]).some((p) => /Legendaries/.test(p)), '2 Legendaries were allowed');
const fake = { ...base }; fake[trim] -= 1; fake['Bronze Lightsaber'] = 1;
ok(F.entry(fake, POOL[0].capes[0]).some((p) => /Not cards/.test(p)), 'a made-up card was allowed');
const cape = Object.keys(S.CAPES)[0];
const withCape = { ...base }; withCape[trim] -= 1; withCape[cape] = 1;
ok(!S.CARDS[cape] || F.entry(withCape, POOL[0].capes[0]).some((p) => /Not cards/.test(p)), 'a cape in the deck was allowed');
ok(F.entry(base, 'Cape of Lies').length > 0, 'a made-up cape was allowed');
ok(F.entry(null, POOL[0].capes[0]).length > 0 && F.entry([], POOL[0].capes[0]).length > 0, 'no deck was allowed');
const neg = { ...base }; neg[trim] += 1; neg[nonBasic] = -1;
ok(F.entry(neg, POOL[0].capes[0]).length > 0, 'a negative count was allowed');

console.log(failures ? `${failures} FAILED` : 'all passed');
process.exit(failures ? 1 : 0);
