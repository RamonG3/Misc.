// Story Mode's difficulty ladder (STORY_OPPONENTS.md): how often each starter deck, played by the Guard bot
// (standing in for a new player), beats each story opponent at its own level, deck and locked Location.
//   node web/test/story_tune.js 60            60 games per starter per seat order, every opponent
//   node web/test/story_tune.js 60 delrith    one opponent (or several: delrith,draynor)
// Same seeds for both seat orders; spread over the machine's cores.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { fork } = require('child_process');
const R = require('../engine.js');
const { HeuristicBot } = require('../bot.js');
const L = require('../bot_levels.js');
const ST = require('../story.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const DECK = Object.fromEntries(R.DECKS.map((d) => [d.name, d]));
const STARTERS = ['Melee', 'Ranged', 'Air Mage'];
const LEVEL = { easy: (s) => new L.EasyBot(s), normal: (s) => new HeuristicBot(s), hard: (s) => new L.HardBot(s) };
const TARGET = { bandit: [70, 75], highwayman: [60, 65], goblins: [60, 65], skeleton: [50, 55], wizard: [50, 55], delrith: [30, 35], draynor: [30, 35],
  cultist: [70, 75], zknight: [60, 65], scout: [60, 65], corpse: [50, 55], shadow: [50, 55], dragith: [30, 35], melzar: [30, 35],
  // chapter 3 (STORY_CH3_BRIEF.md): the Lord is the hardest fight of Act 1
  bknight: [70, 75], peon: [60, 65], pair: [60, 65], honour: [50, 55], smith: [55, 60], daquarius: [23, 28] };

async function game(oppId, starter, seat, seed) {
  const o = ST.OPP[oppId], od = DECK[o.deckName], sd = DECK[starter];
  const decks = [], capes = [], agents = [];
  decks[seat] = sd.cards; decks[1 - seat] = od.cards;
  capes[seat] = sd.capes[0]; capes[1 - seat] = od.capes[seed % od.capes.length];
  agents[seat] = new HeuristicBot(seed * 2 + 1);
  agents[1 - seat] = LEVEL[o.level](seed * 2 + 2);
  const g = new R.Game(decks, agents, seed, Object.assign({ capes }, o.location ? { locked_location: o.location } : {}));
  await g.run();
  return { won: g.winner === seat ? 1 : 0, turns: g.turn };
}

if (process.argv[2] === '--worker') {
  process.on('message', async (job) => {
    const out = [];
    for (const seed of job.seeds) out.push(await game(job.opp, job.starter, job.seat, seed));
    process.send({ id: job.id, out });
  });
  return;
}

(async () => {
  const n = +(process.argv[2] || 40);
  const only = process.argv[3];
  const opps = Object.keys(ST.OPP).filter((k) => !only || only.split(',').includes(k));
  const jobs = [];
  for (const opp of opps) for (const starter of STARTERS) for (const seat of [0, 1]) {
    for (let c = 0; c < n; c += 10) jobs.push({ id: jobs.length, opp, starter, seat, seeds: Array.from({ length: Math.min(10, n - c) }, (_, k) => 50000 + c + k) });
  }
  const workers = Array.from({ length: Math.max(1, os.cpus().length) }, () => fork(__filename, ['--worker']));
  const res = {};
  let next = 0, done = 0;
  const t0 = Date.now();
  await new Promise((resolve) => {
    const give = (w) => { if (next < jobs.length) w.send(jobs[next++]); };
    for (const w of workers) {
      w.on('message', (m) => {
        const j = jobs[m.id];
        const key = j.opp;
        res[key] = res[key] || { won: 0, n: 0, turns: 0, by: {} };
        const r = res[key];
        r.by[j.starter] = r.by[j.starter] || [0, 0];
        for (const x of m.out) { r.won += x.won; r.n += 1; r.turns += x.turns; r.by[j.starter][0] += x.won; r.by[j.starter][1] += 1; }
        if (++done === jobs.length) resolve(); else give(w);
      });
      give(w);
    }
  });
  for (const w of workers) w.kill();
  console.log(`starter decks (Guard) vs each story opponent, ${n} games per starter per seat · ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  for (const opp of opps) {
    const r = res[opp], p = (100 * r.won) / r.n, se = 100 * Math.sqrt((p / 100) * (1 - p / 100) / r.n);
    const t = TARGET[opp], mark = p < t[0] - 2 * se ? 'too hard' : p > t[1] + 2 * se ? 'too easy' : 'on target';
    const by = STARTERS.map((s) => `${s} ${((100 * r.by[s][0]) / r.by[s][1]).toFixed(0)}%`).join(', ');
    console.log(`  ${ST.OPP[opp].name.padEnd(14)} ${ST.OPP[opp].level.padEnd(6)} starters win ${p.toFixed(1)}% ±${se.toFixed(1)} (target ${t[0]}-${t[1]}) ${mark} · ${by} · ${(r.turns / r.n).toFixed(0)} turns`);
  }
})();
