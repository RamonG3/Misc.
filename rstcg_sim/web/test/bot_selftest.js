// Quick checks on the pieces the bots stand on. node web/test/bot_selftest.js   (ends "all passed")
//
//   1. The wait-free copy of the engine used for playouts plays the same games as engine.js, move for move.
//   2. A copy of a game is exact: played on with the same dice it ends the same way as the original.
//   3. A dealt copy never shows the bot what it can't see, and keeps everything else.
//   4. Thinking changes nothing: the live game is the same before and after every Easy and Hard decision.
//   5. The same seed and position give the same answer, even from a fresh bot (Hard keeps no hidden state).
const fs = require('fs');
const path = require('path');
const R = require('../engine.js');
const { HeuristicBot } = require('../bot.js');
const L = require('../bot_levels.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const DECK = Object.fromEntries(R.DECKS.map((d) => [d.name, d]));
const names = ['Melee', 'Ranged', 'Air Mage'];
const pair = (s) => { const x = DECK[names[s % 3]], y = DECK[names[Math.floor(s / 3) % 3]]; return { decks: [x.cards, y.cards], capes: [x.capes[0], y.capes[0]] }; };
const sig = (g) => JSON.stringify([g.winner, g.turn, g.end_reason, g.players.map((p) => [p.deck, p.hand, p.discard, p.materials, p.weapon, p.shield, p.cape, p.qp])]);
const picture = (g) => JSON.stringify([g.turn, g.active, g.winner, g.location, g.actions_left, g.free_plays_used, g.cape_claimed, g.rng.s, g.players.map((p) => ({ ...p, rng: 0 }))]);
let failed = 0;
const check = (name, ok, detail) => { console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`); if (!ok) failed++; };

(async () => {
  // 1. the wait-free engine
  const fast = L.fastEngine();
  check('playouts use the wait-free copy of the engine', fast.sync);
  if (fast.sync) {
    const F = { Game: fast.Game.constructor };
    let same = 0;
    const n = 300;
    for (let s = 1; s <= n; s++) {
      const c = pair(s);
      const a = new R.Game(c.decks, [new HeuristicBot(s * 2 + 1), new HeuristicBot(s * 2 + 2)], s, { capes: c.capes });
      await a.run();
      const b = new F.Game(c.decks, [new HeuristicBot(s * 2 + 1), new HeuristicBot(s * 2 + 2)], s, { capes: c.capes });
      b.run();
      same += sig(a) === sig(b);
    }
    check('the wait-free copy plays the same games as engine.js', same === n, `${same}/${n} identical`);
  }

  // 2 and 3. copies
  let exact = 0, tried = 0, leak = 0, kept = 0, deals = 0;
  for (let s = 1; s <= 40; s++) {
    const c = pair(s);
    const g = new R.Game(c.decks, [new HeuristicBot(s * 2 + 1), new HeuristicBot(s * 2 + 2)], s, { capes: c.capes });
    for (let t = 0; t < 6 && g.winner === null; t++) await g.play_turn();
    if (g.winner !== null) continue;
    // dealt copies: seat 0's view
    const before = picture(g);
    for (let k = 0; k < 20; k++) {
      const d = L.cloneGame(g, 0, new R.RNG(1000 + k), [null, null]);
      const me = d.players[0], op = d.players[1];
      deals++;
      const sorted = (a) => a.slice().sort().join('|');
      const sameCards = sorted(me.deck) === sorted(g.players[0].deck) && sorted(op.hand.concat(op.deck)) === sorted(g.players[1].hand.concat(g.players[1].deck))
        && op.hand.length === g.players[1].hand.length && me.hand.join('|') === g.players[0].hand.join('|');
      kept += sameCards;
      leak += me.deck.join('|') === g.players[0].deck.join('|') || (op.hand.join('|') === g.players[1].hand.join('|') && op.deck.join('|') === g.players[1].deck.join('|'));
    }
    if (picture(g) !== before) leak += 1000;
    // an exact copy, given the same dice and the same players, finishes just like the original
    const twin = L.cloneGame(g, null, new R.RNG(1), [new L.SteadyBot(), new L.SteadyBot()]);
    twin.rng.s = g.rng.s;
    for (const b of twin.agents) b.setup(twin);
    g.agents = [new L.SteadyBot(), new L.SteadyBot()];
    for (const b of g.agents) b.setup(g);
    await twin.run(); await g.run();
    tried++; exact += sig(twin) === sig(g);
  }
  check('an exact copy plays on exactly like the original', exact === tried, `${exact}/${tried}`);
  check('a dealt copy keeps the same cards in the same places the bot can see', kept === deals, `${kept}/${deals}`);
  check('a dealt copy reshuffles what the bot cannot see', leak <= 2, `${leak} of ${deals} deals came out in the true order`);

  // 4 and 5. thinking changes nothing, and is repeatable
  for (const level of ['easy', 'hard']) {
    let touched = 0, asked = 0, differ = 0, again = 0;
    for (let s = 1; s <= (level === 'hard' ? 6 : 30); s++) {
      const c = pair(s);
      const bot = L.makeBot(level, s * 2 + 1);
      const watch = {
        setup: (g) => bot.setup(g),
        async choose(g, i, legal) {
          const before = picture(g);
          const a = await bot.choose(g, i, legal);
          asked++; touched += picture(g) !== before;
          if (level === 'hard' && asked % 5 === 0) {         // a fresh bot, same seed, same position
            const fresh = L.makeBot(level, s * 2 + 1); fresh.setup(g);
            again++; differ += JSON.stringify(await fresh.choose(g, i, legal)) !== JSON.stringify(a);
          }
          return a;
        },
        async react(g, ...rest) { const before = picture(g); const r = await bot.react(g, ...rest); asked++; touched += picture(g) !== before; return r; },
        async discard_choice(g, i) { const before = picture(g); const r = await bot.discard_choice(g, i); asked++; touched += picture(g) !== before; return r; },
      };
      const g = new R.Game(c.decks, [watch, new HeuristicBot(s * 2 + 2)], s, { capes: c.capes });
      await g.run();
    }
    check(`${level}: the live game is untouched by its thinking`, touched === 0, `${asked} decisions, ${touched} changed something`);
    if (level === 'hard') check('hard: a fresh bot with the same seed gives the same answer', differ === 0, `${again} decisions asked again, ${differ} differed`);
  }
  console.log(failed ? `${failed} FAILED` : 'all passed');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
