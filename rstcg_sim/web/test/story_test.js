// Story Mode's runner and chapter data (web/story.js): every road can be walked to its end, choices are
// recorded, the map opens and closes the right dots, and every opponent and speaker the script names exists.
// node web/test/story_test.js  -> "all passed"
const assert = require('assert');
const path = require('path');
const ST = require(path.join(__dirname, '..', 'story.js'));
const CARDS = require(path.join(__dirname, '..', 'cards.json'));
const S = ST.make();
let checks = 0;
const ok = (c, m) => { assert(c, m); checks++; };

// walk a chapter: pick(step, run) chooses an option; at the map, enter the first open dot
function walk(chId, pick, { lose = null } = {}) {
  const run = S.start(chId);
  const seen = { duels: [], maps: [], rewards: [], scenes: [] };
  for (let guard = 0; guard < 2000; guard++) {
    const st = S.step(run);
    if (st.finished) return { run, seen };
    ok(!st.broken, `broken step in ${run.node} at ${run.i}`);
    if (st.onMap) {
      const open = S.dotsOf(run).filter((d) => d.state === 'open');
      ok(open.length === 1, `one open dot on the map (${open.map((d) => d.id)})`);
      seen.maps.push(open[0].id);
      ok(S.enter(run, open[0].id), 'enter the dot');
      continue;
    }
    if (st.choose) { S.advance(run, pick(st, run)); continue; }
    if (st.duel) { ok(S.OPP[st.duel], `opponent ${st.duel}`); seen.duels.push(st.duel); }
    if (st.reward) seen.rewards.push(st.reward);
    if (st.scene) seen.scenes.push(st.scene);
    if (st.who) ok(st.who === 'you' || S.SPEAKERS[st.who], `speaker ${st.who}`);
    S.advance(run);
  }
  throw new Error('the chapter never ended');
}

const fork = (road) => (st) => (st.fork ? st.choose.findIndex((o) => o.road === road) : st.choose.length - 1);
const A = walk('ch1', fork('A'));
ok(A.run.done && A.run.road === 'A', 'road A ends as A');
ok(JSON.stringify(A.seen.duels) === JSON.stringify(['bandit', 'highwayman', 'skeleton', 'delrith']), 'road A duels: ' + A.seen.duels);
ok(JSON.stringify(A.seen.maps) === JSON.stringify(['swamp', 'a_road', 'a_manor', 'a_cellar']), 'road A dots: ' + A.seen.maps);
ok(A.seen.rewards.join() === 'Claws of Guthix', 'road A reward');
ok(A.run.tone.stranger1 === 'witty' && A.run.tone.stranger2 === 'witty', 'tone recorded (last option is witty)');
ok(Object.values(A.run.dots).every((s) => s === 'done'), 'every dot reached is done at the end');

const B = walk('ch1', fork('B'));
ok(B.run.road === 'B', 'road B ends as B');
ok(JSON.stringify(B.seen.duels) === JSON.stringify(['bandit', 'goblins', 'wizard', 'draynor']), 'road B duels: ' + B.seen.duels);
ok(B.seen.rewards.join() === 'Shield of Arrav', 'road B reward');
ok(!Object.keys(B.run.dots).some((d) => d.startsWith('a_')), 'road B never opens road A dots');

// every option in every choice, walked
for (const road of ['A', 'B']) for (const k of [0, 1, 2, 3]) {
  const w = walk('ch1', (st) => (st.fork ? st.choose.findIndex((o) => o.road === road) : Math.min(k, st.choose.length - 1)));
  ok(w.run.road === road, `road ${road} with option ${k}`);
}
const first = walk('ch1', (st) => (st.fork ? 0 : 0));
ok(first.run.tone.stranger1 === 'polite', 'first option is polite');

// a run is plain JSON: saved and loaded part way, it carries on the same
{
  const run = S.start('ch1');
  for (let n = 0; n < 9; n++) { const st = S.step(run); if (st.choose) S.advance(run, 1); else if (st.map) S.enter(run, 'swamp'); else S.advance(run); }
  const copy = JSON.parse(JSON.stringify(run));
  ok(JSON.stringify(S.step(copy)) === JSON.stringify(S.step(run)), 'a saved run is on the same step');
  ok(S.sceneOf(copy) && S.sceneOf(copy).scene, 'a saved run knows its scene');
}

// the opponents' decks and Locations name real cards (placeholder decks name decks from cards.json)
const deckNames = new Set(CARDS.decks.map((d) => d.name));
for (const [id, o] of Object.entries(S.OPP)) {
  if (o.deckName) ok(deckNames.has(o.deckName), `${id}: deck ${o.deckName}`);
  if (o.deck) for (const n of Object.keys(o.deck)) ok(!!CARDS.cards[n], `${id}: card ${n}`);
  ok(o.lose && o.lose.t, `${id}: a losing line`);
}

console.log(`${checks} checks · all passed`);
