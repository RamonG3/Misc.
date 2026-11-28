// Necromancy parity (2026-11-27): the five Necromancy decks against the starter and tournament field, with capes,
// in the JS engine and bot. Compare with `python web/test/parity_necro.py N` (same matchups, the Python engine).
//   node web/test/parity_necro.js 100
const fs = require('fs');
const path = require('path');
const R = require('../engine.js');
const { HeuristicBot } = require('../bot.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const N = +(process.argv[2] || 100);
const ALL_NEW = ['Necromancy', 'Lean Necrotic', 'Skeleton Melee', 'Zombie Ranged', 'Ghost Mage'];
const NEW = process.argv[3] ? process.argv[3].split(',') : ALL_NEW;   // or any decks, to compare with the old ones
const D = Object.fromEntries(R.DECKS.map((d) => [d.name, d]));
const FIELD = R.DECKS.filter((d) => (d.group === 'Starter decks' || d.group === 'Tournament decks') && !ALL_NEW.includes(d.name)).map((d) => d.name);

(async () => {
  const t0 = Date.now();
  const stats = { summoned: 0, raised: 0, necro: 0, games: 0 };
  for (const me of NEW) {
    const rs = [];
    for (const cape of D[me].capes) {
      let w = 0, n = 0;
      for (const opp of FIELD) for (let s = 0; s < N; s++) for (const seat of [0, 1]) {
        const ds = [null, null], cs = [null, null];
        ds[seat] = D[me].cards; ds[1 - seat] = D[opp].cards;
        cs[seat] = cape; cs[1 - seat] = D[opp].capes[0];
        const g = new R.Game(ds, [new HeuristicBot(2 * s + 1 + 7 * seat), new HeuristicBot(2 * s + 2 + 7 * seat)], 700000 + s, { capes: cs });
        await g.run();
        w += g.winner === seat; n++;
        stats.games++; stats.necro += g.players[seat].cape_prog.necro_hit || 0;
      }
      rs.push([cape, 100 * w / n]);
    }
    const avg = rs.reduce((t, [, x]) => t + x, 0) / rs.length;
    console.log(`${me.padEnd(16)} with capes ${avg.toFixed(1)}%  (${rs.map(([c, x]) => `${c} ${x.toFixed(1)}`).join(', ')})`);
  }
  console.log(`Necromancy hits a game: ${(stats.necro / stats.games).toFixed(2)}; ${stats.games} games in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
})();
