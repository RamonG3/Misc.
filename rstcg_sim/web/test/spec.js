// Run a list of matchups in the JS engine: node spec.js spec.json games out.json
const fs = require('fs'), path = require('path');
const R = require('../engine.js'); const { HeuristicBot } = require('../bot.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const D = Object.fromEntries(R.DECKS.map((d) => [d.name, d.cards]));
const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8')); const n = +process.argv[3];
(async () => {
  const out = [];
  for (let k = 0; k < spec.length; k++) {
    const m = spec[k]; let w = 0, t = 0, c = 0, q = 0;
    for (let s = 1; s <= n; s++) {
      const seed = k * 10000 + s;
      const g = new R.Game([m.da || D[m.a], m.db || D[m.b]], [new HeuristicBot(seed * 2 + 1), new HeuristicBot(seed * 2 + 2)], seed,
        m.capes ? { capes: m.capes } : null);
      await g.run();
      w += g.winner === 0; t += g.turn; c += g.end_reason === 'cape'; q += g.players[0].qp + g.players[1].qp;
    }
    out.push([w / n, t / n, c / n, q / n]);
  }
  fs.writeFileSync(process.argv[4], JSON.stringify(out));
})();
