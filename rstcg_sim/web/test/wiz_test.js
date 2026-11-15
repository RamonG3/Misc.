// Deck Wizard check: builds decks around various focus cards from a starter-sized collection and plays them.
//   node web/test/wiz_test.js [games per starter=12]
const fs = require('fs'), path = require('path');
const R = require('../engine.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const W = require('../deckwiz.js');
const SET1 = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'print', 'set1.json'), 'utf8'));
const rarity = (n) => (SET1[n] ? SET1[n].rarity : 'C');
const isBasic = (n) => R.CARDS[n] && R.CARDS[n].kind === 'material' && (!SET1[n] || SET1[n].rarity === 'C');
const coll = {};
for (const d of R.DECKS.filter((d) => d.group === 'Starter decks')) for (const [n, k] of Object.entries(d.cards)) if (!isBasic(n)) coll[n] = (coll[n] || 0) + k;
const games = +(process.argv[2] || 12);
const cases = [
  [['Mithril Scimitar'], {}], [['Air Bolt', 'Rune Pouch'], {}], [['Willow Shortbow'], {}],
  [['Rune Scimitar'], { 'Rune Scimitar': 2, 'Rune Bar': 3 }], [['Mithril Scimitar', 'Air Blast'], {}],
  [['Lobster'], {}], [['Yew Shortbow', 'Rapid'], { 'Yew Shortbow': 1 }], [['Excalibur'], {}],
];
(async () => {
  for (const [focus, extra] of cases) {
    const c2 = { ...coll, ...extra };
    const owned = (n) => (isBasic(n) ? Infinity : c2[n] || 0);
    const t0 = Date.now();
    const cands = W.candidates(focus, owned, { rarity, count: 3 });
    const res = [];
    for (const b of cands) { const s = await W.evaluate(b.cards, { games, cape: b.cape }); res.push([s.rate, b]); }
    res.sort((a, b) => b[0] - a[0]);
    const ms = Date.now() - t0;
    console.log(`\n${focus.join(' + ')} -> ${cands.length} builds in ${ms} ms: ${res.map(([r, b]) => `${b.template} ${(100 * r).toFixed(0)}%`).join(' | ')}`);
    if (res[0]) {
      const b = res[0][1];
      const byKind = {};
      for (const [n, k] of Object.entries(b.cards)) (byKind[R.CARDS[n].kind] = byKind[R.CARDS[n].kind] || []).push(`${k} ${n}`);
      console.log('  cape', b.cape, '| legal', W.legal(b.cards, owned, rarity), '| total', Object.values(b.cards).reduce((s, k) => s + k, 0));
      for (const [k, v] of Object.entries(byKind)) console.log(`  ${k}: ${v.join(', ')}`);
    }
  }
})();
