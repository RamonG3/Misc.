// Bot-vs-bot in the JS engine, for comparison with the Python simulator.
//   node parity.js field 100      every Round 11 field pairing, 100 games per seat order
//   node parity.js capes 60       cape decks head to head, both wearing their capes
const fs = require('fs');
const path = require('path');
const R = require('../engine.js');
const { HeuristicBot } = require('../bot.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));

const mode = process.argv[2] || 'field';
const n = +(process.argv[3] || 50);
const deck = (name) => R.DECKS.find((d) => d.name === name).cards;

async function play(a, b, seed, rules) {
  const g = new R.Game([a, b], [new HeuristicBot(seed * 2 + 1), new HeuristicBot(seed * 2 + 2)], seed, rules);
  await g.run();
  return g;
}

(async () => {
  const t0 = Date.now();
  if (mode === 'field') {
    const names = R.DECKS.filter((d) => d.group === 'Tournament decks').map((d) => d.name);
    const wins = {}; let games = 0, turns = 0; const reasons = {};
    let seed = 1;
    for (const a of names) for (const b of names) {
      for (let s = 0; s < n; s++, seed++) {
        const g = await play(deck(a), deck(b), seed);
        games++; turns += g.turn; reasons[g.end_reason] = (reasons[g.end_reason] || 0) + 1;
        for (const [seat, me, op] of [[0, a, b], [1, b, a]]) {
          const k = me + '|' + op; wins[k] = wins[k] || [0, 0];
          wins[k][0] += g.winner === seat; wins[k][1] += 1;
        }
      }
    }
    const avg = {};
    for (const a of names) {
      let s = 0;
      for (const b of names) if (b !== a) s += 100 * wins[a + '|' + b][0] / wins[a + '|' + b][1];
      avg[a] = s / (names.length - 1);
    }
    let fs_ = 0; for (const k in wins) if (k.split('|')[0] === k.split('|')[1]) {}
    console.log(`JS engine, field, ${n} games per ordered pairing, ${games} games, ${((Date.now() - t0) / 1000).toFixed(1)}s`);
    for (const a of names.sort((x, y) => avg[y] - avg[x])) console.log(`  ${a.padEnd(15)} ${avg[a].toFixed(0).padStart(3)}%`);
    console.log(`  turns per game ${(turns / games).toFixed(1)}  ends ${JSON.stringify(reasons)}`);
  } else {
    const capeDecks = {
      'Quest Point Cape': 'Quest Melee', 'Attack Cape': 'Lean Melee', 'Ranged Cape': 'Lean Ranged',
      'Magic Cape': 'Lean Mage', 'Defence Cape': 'Defence Melee', 'Prayer Cape': 'Prayer Melee',
      'Cooking Cape': 'Cooking Melee', 'Smithing Cape': 'Lean Melee', 'Woodcutting Cape': 'Lean Ranged',
      'Runecrafting Cape': 'Lean Mage',
    };
    const capes = Object.keys(capeDecks);
    const tot = {}; let games = 0, capeEnd = 0, turns = 0; let seed = 1;
    for (let x = 0; x < capes.length; x++) for (let y = x + 1; y < capes.length; y++) {
      const a = capes[x], b = capes[y];
      for (let s = 0; s < n; s++, seed++) for (const seat of [0, 1]) {
        const decks = seat === 0 ? [deck(capeDecks[a]), deck(capeDecks[b])] : [deck(capeDecks[b]), deck(capeDecks[a])];
        const cs = seat === 0 ? [a, b] : [b, a];
        const g = await play(decks[0], decks[1], seed, { capes: cs });
        games++; turns += g.turn; capeEnd += g.end_reason === 'cape';
        for (const [c, me] of [[a, seat], [b, 1 - seat]]) {
          tot[c] = tot[c] || [0, 0]; tot[c][0] += g.winner === me; tot[c][1] += 1;
        }
      }
    }
    console.log(`JS engine, capes head to head, ${n} games per seat order per pairing, ${games} games, ${((Date.now() - t0) / 1000).toFixed(1)}s`);
    console.log(`  capes decided ${(100 * capeEnd / games).toFixed(0)}%   turns per game ${(turns / games).toFixed(1)}`);
    for (const c of capes.sort((p, q) => tot[q][0] / tot[q][1] - tot[p][0] / tot[p][1])) console.log(`  ${c.padEnd(19)} ${(100 * tot[c][0] / tot[c][1]).toFixed(0).padStart(3)}%`);
  }
})();
