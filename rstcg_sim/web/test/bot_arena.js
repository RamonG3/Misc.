// Bot vs bot, headless, on the same engine.js and data the Arena game uses.
//   node web/test/bot_arena.js [games=40] [botA=greedy] [botB=greedy] [decks=starters|all]
// Every pairing of decks, both seat orders, each side wearing one of its deck's capes. Prints bot A's win
// rate per matchup and overall, and how long each bot takes per decision (the game runs on a phone too).
//
// Add a bot: put it in BOTS below as a factory (seed) => agent. An agent is any object with
//   setup(g), choose(g, i, legal) -> one of legal, react(g, j, amount, style, src, opts) -> card name or null,
//   discard_choice(g, i) -> a card name in g.players[i].hand
// Any of these may return a Promise. The engine awaits them.
const path = require('path');
const fs = require('fs');
const R = require('../engine.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const { HeuristicBot } = require('../bot.js');
const L = require('../bot_levels.js');

const BOTS = {                                     // bot_ladder.js is the fuller version of this (cores, peek test)
  greedy: (seed) => new HeuristicBot(seed),
  easy: (seed) => L.makeBot('easy', seed),
  normal: (seed) => L.makeBot('normal', seed),
  hard: (seed) => L.makeBot('hard', seed),
};

const games = +(process.argv[2] || 40);
const A = process.argv[3] || 'greedy', B = process.argv[4] || 'greedy';
const which = process.argv[5] || 'starters';
for (const k of [A, B]) if (!BOTS[k]) { console.error(`unknown bot "${k}"; have: ${Object.keys(BOTS).join(', ')}`); process.exit(1); }
const decks = R.DECKS.filter((d) => (which === 'all' ? ['Starter decks', 'Tournament decks'].includes(d.group) : d.group === 'Starter decks'));

function timed(agent, clock) {                       // wraps an agent to time its decisions
  const t = (fn) => async (...a) => { const t0 = process.hrtime.bigint(); const r = await fn(...a); const ms = Number(process.hrtime.bigint() - t0) / 1e6; clock.n++; clock.ms += ms; clock.max = Math.max(clock.max, ms); return r; };
  return { setup: agent.setup && agent.setup.bind(agent), choose: t(agent.choose.bind(agent)), react: agent.react && t(agent.react.bind(agent)), discard_choice: t(agent.discard_choice.bind(agent)) };
}

(async () => {
  const clocks = { [A]: { n: 0, ms: 0, max: 0 }, [B]: { n: 0, ms: 0, max: 0 } };
  if (A === B) clocks[B] = clocks[A];
  let seed = 1, winsA = 0, total = 0, draws = 0;
  const rows = [];
  for (const da of decks) for (const db of decks) {
    let w = 0, n = 0;
    for (let k = 0; k < games; k++) {
      const aFirst = k % 2 === 0;
      const s = seed++;
      const botA = timed(BOTS[A](s * 7 + 1), clocks[A]), botB = timed(BOTS[B](s * 7 + 2), clocks[B]);
      const capeA = da.capes[s % da.capes.length], capeB = db.capes[(s >> 1) % db.capes.length];
      const seats = aFirst ? [0, 1] : [1, 0];            // seats[0] = A's seat
      const deckL = [], agents = [], capes = [];
      deckL[seats[0]] = da.cards; deckL[seats[1]] = db.cards;
      agents[seats[0]] = botA; agents[seats[1]] = botB;
      capes[seats[0]] = capeA; capes[seats[1]] = capeB;
      const g = new R.Game(deckL, agents, s, { capes }, ['A', 'B']);
      while (g.winner === null && g.turn < 400) await g.play_turn();
      n++; total++;
      if (g.winner === seats[0]) { w++; winsA++; } else if (g.winner === null) draws++;
    }
    rows.push(`${da.name.padEnd(16)} vs ${db.name.padEnd(16)} ${A} wins ${(100 * w / n).toFixed(0).padStart(3)}%`);
  }
  console.log(rows.join('\n'));
  console.log(`\n${A} vs ${B}: ${A} won ${(100 * winsA / total).toFixed(1)}% of ${total} games (${draws} unfinished)`);
  for (const [k, c] of Object.entries(A === B ? { [A]: clocks[A] } : clocks)) console.log(`${k}: ${(c.ms / Math.max(1, c.n)).toFixed(2)} ms per decision on average, slowest ${c.max.toFixed(1)} ms (${c.n} decisions)`);
})();
