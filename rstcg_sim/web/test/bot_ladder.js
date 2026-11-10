// Bot against bot on the real engine, across decks and both seat orders, on several cores.
//
//   node web/test/bot_ladder.js 40 hard greedy              starter decks: 40 games per pairing per seat order
//   node web/test/bot_ladder.js 10 hard greedy all          every starter and tournament deck
//   node web/test/bot_ladder.js 40 hard:playouts=64 greedy  a bot with its settings changed
//   node web/test/bot_ladder.js 10 hard greedy --peek-test  also check the first bot never uses hidden cards
//   options: --jobs N (cores to use)  --seed N (first seed)  --no-capes  --mirror-only  --no-mirror
//
// Each pairing is played on the same seeds with each bot going first, so shuffle luck cancels out.
// A few games are always played a second time in another process: the same seed must give the same game.
// The peek test asks the bot every question twice, the second time with everything it can't see dealt
// out differently. A bot that never looks at hidden cards gives the same answer both times.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { fork } = require('child_process');
const R = require('../engine.js');
const { HeuristicBot } = require('../bot.js');
const L = require('../bot_levels.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));

// Register a bot here to measure it: name -> (seed, options) => agent
const BOTS = {
  greedy: (seed) => new HeuristicBot(seed),
  easy: (seed, o) => new L.EasyBot(seed, o),
  normal: (seed) => new HeuristicBot(seed),
  hard: (seed, o) => new L.HardBot(seed, o),
  sharp: (seed, o) => new L.SharpBot(o),            // Hard's playout policy on its own, no search
};

function parseBot(spec) {                 // "hard:playouts=64,reacts=false" -> { name, opts }
  const [name, rest] = spec.split(':');
  if (!BOTS[name]) throw new Error(`unknown bot "${name}" (have: ${Object.keys(BOTS).join(', ')})`);
  const opts = {};
  for (const kv of (rest || '').split(',').filter(Boolean)) {
    const [k, v] = kv.split('=');
    opts[k] = v === 'true' ? true : v === 'false' ? false : Number.isNaN(+v) ? v : +v;
  }
  return { name, opts, make: (seed) => BOTS[name](seed, opts) };
}

const DECK = Object.fromEntries(R.DECKS.map((d) => [d.name, d]));
const hash = (s) => { let h = 0x811c9dc5; for (let k = 0; k < s.length; k++) { h ^= s.charCodeAt(k); h = Math.imul(h, 0x01000193); } return (h >>> 0).toString(16); };

// ---------- the peek test's other world: same table, different hidden cards ----------
function otherWorld(g, me, rng) {
  const t = Object.assign(Object.create(Object.getPrototypeOf(g)), g);
  for (const k of Object.keys(t)) if (typeof t[k] === 'function') delete t[k];
  t.rng = new R.RNG(rng.randrange(0x7fffffff) + 1);
  t.players = g.players.map((p) => Object.assign(Object.create(Object.getPrototypeOf(p)), JSON.parse(JSON.stringify(p)), { rng: t.rng }));
  t.cape_claimed = { ...g.cape_claimed };
  t.stats = [{}, {}]; t.log = []; t.events = [];
  const mine = t.players[me];
  const theirs = t.players[1 - me];
  rng.shuffle(mine.deck);
  const n = theirs.hand.length;
  const pool = theirs.hand.concat(theirs.deck);
  rng.shuffle(pool);
  theirs.hand = pool.slice(0, n);
  theirs.deck = pool.slice(n);
  return t;
}

// ---------- one game ----------
async function playGame(A, B, x, y, seat, seed, o) {
  const decks = [], capes = [], agents = [];
  const dx = DECK[x], dy = DECK[y];
  decks[seat] = dx.cards; decks[1 - seat] = dy.cards;
  capes[seat] = dx.capes[seed % dx.capes.length]; capes[1 - seat] = dy.capes[(seed >> 3) % dy.capes.length];
  const trace = [];
  const time = [{ n: 0, total: 0, max: 0 }, { n: 0, total: 0, max: 0 }];       // [A, B]
  const peek = { asked: 0, changed: 0, examples: [] };
  const scr = new R.RNG(seed * 31 + 7);
  const wrap = (bot, who, twin) => {
    const timed = (kind, fn, again) => async (...args) => {
      const t0 = process.hrtime.bigint();
      const out = await fn.apply(bot, args);
      const ms = Number(process.hrtime.bigint() - t0) / 1e6;
      const st = time[who];
      st.n++; st.total += ms; if (ms > st.max) st.max = ms;
      trace.push(who + kind + JSON.stringify(out));
      if (twin) {
        const g = args[0];
        const other = await again(otherWorld(g, args[1], scr), ...args.slice(1));
        peek.asked++;
        if (JSON.stringify(other) !== JSON.stringify(out)) {
          peek.changed++;
          if (peek.examples.length < 3) peek.examples.push(`turn ${g.turn + 1} ${kind}: ${JSON.stringify(out)} became ${JSON.stringify(other)}`);
        }
      }
      return out;
    };
    return {
      setup: (g) => { bot.setup(g); if (twin) twin.setup(g); },
      choose: timed('c', bot.choose, (w, i) => twin.choose(w, i, w.legal_actions(i))),
      react: timed('r', bot.react, (w, ...rest) => twin.react(w, ...rest)),
      discard_choice: timed('d', bot.discard_choice, (w, i) => twin.discard_choice(w, i)),
    };
  };
  agents[seat] = wrap(A.make(seed * 2 + 1), 0, o.peekTest ? A.make(seed * 2 + 1) : null);
  agents[1 - seat] = wrap(B.make(seed * 2 + 2), 1, null);
  const g = new R.Game(decks, agents, seed, o.noCapes ? null : { capes });
  await g.run();
  trace.push(`end ${g.winner} ${g.turn} ${g.end_reason} ${g.players[0].lp} ${g.players[1].lp}`);
  return { won: g.winner === seat ? 1 : 0, draw: g.winner === null ? 1 : 0, turns: g.turn, reason: g.end_reason, time, peek, sig: hash(trace.join('|')) };
}

// ---------- a worker: plays the jobs it is sent ----------
if (process.argv[2] === '--worker') {
  process.on('message', async (job) => {
    try {
      const A = parseBot(job.a), B = parseBot(job.b);
      const games = [];
      for (const seed of job.seeds) games.push(await playGame(A, B, job.x, job.y, job.seat, seed, job.o));
      process.send({ id: job.id, games });
    } catch (e) {
      process.send({ id: job.id, error: String(e && e.stack || e) });
    }
  });
  return;
}

// ---------- the master: deals the jobs out and adds up ----------
(async () => {
  const args = process.argv.slice(2);
  const flag = (name) => { const k = args.indexOf(name); if (k < 0) return null; const v = args[k + 1]; args.splice(k, 2); return v; };
  const has = (name) => { const k = args.indexOf(name); if (k < 0) return false; args.splice(k, 1); return true; };
  const jobsN = +(flag('--jobs') || Math.max(1, Math.min(os.cpus().length, 4)));
  const seed0 = +(flag('--seed') || 1);
  const o = { noCapes: has('--no-capes'), peekTest: has('--peek-test') };
  const mirrorOnly = has('--mirror-only'), noMirror = has('--no-mirror'), json = has('--json');
  const n = +(args[0] || 20);
  const a = args[1] || 'hard', b = args[2] || 'greedy';
  const all = args[3] === 'all';
  parseBot(a); parseBot(b);
  const names = R.DECKS.filter((d) => d.group === 'Starter decks' || (all && d.group === 'Tournament decks')).map((d) => d.name);
  const pairs = [];
  for (const x of names) for (const y of names) if ((x === y ? !noMirror : !mirrorOnly)) pairs.push([x, y]);

  const jobs = [];
  pairs.forEach(([x, y], p) => {
    const seeds = Array.from({ length: n }, (_, k) => seed0 + p * 100003 + k);
    for (const seat of [0, 1]) for (let k = 0; k < n; k += 4) jobs.push({ id: jobs.length, a, b, x, y, seat, seeds: seeds.slice(k, k + 4), o });
  });
  const recheck = [];                                     // a few jobs to play again, for "same seed, same game"
  for (let k = 0; k < jobs.length && recheck.length < 6; k += Math.max(1, Math.floor(jobs.length / 6))) recheck.push({ ...jobs[k], id: jobs.length + recheck.length, again: jobs[k].id });
  const queue = jobs.concat(recheck);
  const total = queue.length;
  const res = new Array(total);
  const t0 = Date.now();
  let sent = 0, got = 0;
  await new Promise((resolve, reject) => {
    const workers = Array.from({ length: Math.min(jobsN, total) }, () => fork(__filename, ['--worker']));
    const next = (w) => { if (sent < total) w.send(queue[sent++]); };
    for (const w of workers) {
      w.on('message', (m) => {
        if (m.error) { for (const x of workers) x.kill(); reject(new Error(m.error)); return; }
        res[m.id] = m.games;
        got++;
        if (!json && process.stderr.isTTY) process.stderr.write(`\r${got}/${total} `);
        if (got === total) { for (const x of workers) x.kill(); resolve(); } else next(w);
      });
      next(w);
    }
  });

  // ---------- add up ----------
  const cell = {};                                        // "x|y" -> [wins, games]
  const time = [{ n: 0, total: 0, max: 0 }, { n: 0, total: 0, max: 0 }];
  const reasons = {};
  const seatW = [[0, 0], [0, 0]];
  const peek = { asked: 0, changed: 0, examples: [] };
  let wins = 0, games = 0, turns = 0, draws = 0;
  for (const job of jobs) for (const gm of res[job.id]) {
    const c = (cell[job.x + '|' + job.y] = cell[job.x + '|' + job.y] || [0, 0]);
    c[0] += gm.won; c[1]++;
    wins += gm.won; games++; turns += gm.turns; draws += gm.draw;
    seatW[job.seat][0] += gm.won; seatW[job.seat][1]++;
    reasons[gm.reason] = (reasons[gm.reason] || 0) + 1;
    for (const k of [0, 1]) { time[k].n += gm.time[k].n; time[k].total += gm.time[k].total; time[k].max = Math.max(time[k].max, gm.time[k].max); }
    peek.asked += gm.peek.asked; peek.changed += gm.peek.changed; peek.examples.push(...gm.peek.examples);
  }
  let same = 0, replayed = 0;
  for (const job of recheck) res[job.id].forEach((gm, k) => { replayed++; same += gm.sig === res[job.again][k].sig; });

  const pct = (w, g) => (g ? (100 * w / g).toFixed(1) : '-');
  const rate = wins / games;
  const out = { a, b, decks: names, games, wins, rate: +(100 * rate).toFixed(2), ci: +(196 * Math.sqrt(rate * (1 - rate) / games)).toFixed(2),
    first: +pct(...seatW[0]), second: +pct(...seatW[1]), turns: +(turns / games).toFixed(1), reasons, draws,
    msA: { avg: +(time[0].total / time[0].n).toFixed(3), worst: +time[0].max.toFixed(1), perGame: +(time[0].n / games).toFixed(1) },
    msB: { avg: +(time[1].total / time[1].n).toFixed(3), worst: +time[1].max.toFixed(1), perGame: +(time[1].n / games).toFixed(1) },
    deterministic: same === replayed, replayed, peek: o.peekTest ? peek : null, seconds: +((Date.now() - t0) / 1000).toFixed(0), cells: cell };
  if (json) { console.log(JSON.stringify(out)); return; }

  if (process.stderr.isTTY) process.stderr.write('\r' + ' '.repeat(20) + '\r');
  const A = a.split(':')[0];
  console.log(`${a} vs ${b} · ${all ? 'starter and tournament decks' : 'starter decks'} · ${n} games per pairing per seat order · ${games} games · ${jobsN} jobs · ${out.seconds} s`);
  if (names.length <= 4) {
    console.log(' '.repeat(22) + names.map((y) => ('vs ' + y).padStart(13)).join('') + '          all');
    for (const x of names) {
      let rw = 0, rg = 0;
      const row = names.map((y) => { const c = cell[x + '|' + y]; if (!c) return '-'.padStart(13); rw += c[0]; rg += c[1]; return (pct(...c) + '%').padStart(13); }).join('');
      console.log(`  ${(A + ' as ' + x).padEnd(20)}${row}${(pct(rw, rg) + '%').padStart(13)}`);
    }
  } else {
    for (const x of names) {
      let rw = 0, rg = 0, cw = 0, cg = 0;
      for (const y of names) { const c = cell[x + '|' + y]; if (c) { rw += c[0]; rg += c[1]; } const d = cell[y + '|' + x]; if (d) { cw += d[0]; cg += d[1]; } }
      console.log(`  ${x.padEnd(16)} ${A} playing it ${(pct(rw, rg) + '%').padStart(6)}   ${A} against it ${(pct(cw, cg) + '%').padStart(6)}`);
    }
  }
  console.log(`  ${A} wins ${out.rate.toFixed(1)}% of ${games} games (±${out.ci.toFixed(1)})   going first ${pct(...seatW[0])}%, second ${pct(...seatW[1])}%`);
  console.log(`  games: ${out.turns} turns on average; ` + Object.entries(reasons).map(([k, v]) => `${k} ${pct(v, games)}%`).join(', '));
  console.log('  ms per decision          avg     worst   decisions per game');
  for (const [k, nm] of [[0, a], [1, b]]) console.log(`    ${nm.padEnd(20)} ${(time[k].total / time[k].n).toFixed(3).padStart(7)} ${time[k].max.toFixed(1).padStart(9)}   ${(time[k].n / games).toFixed(1)}`);
  console.log(`  same seed, same game: ${same === replayed ? 'yes' : 'NO'} (${replayed} games played again in another process${same === replayed ? '' : `, ${replayed - same} differed`})`);
  if (o.peekTest) {
    console.log(`  peek test: ${peek.changed === 0 ? 'passed' : 'FAILED'}: ${peek.asked} decisions asked again with the hidden cards dealt differently, ${peek.changed} changed`);
    for (const e of peek.examples.slice(0, 5)) console.log('    ' + e);
  }
  if (same !== replayed || (o.peekTest && peek.changed)) process.exitCode = 1;
})().catch((e) => { console.error(e); process.exit(1); });
