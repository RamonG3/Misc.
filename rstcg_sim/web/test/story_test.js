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
function walk(chId, pick, { lose = null, prev = null } = {}) {
  const run = S.start(chId, prev);
  const seen = { duels: [], maps: [], rewards: [], scenes: [], lines: [], asked: [] };
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
    if (st.choose) { if (st.ask) seen.asked.push(st.choose.map((o) => o.t)); S.advance(run, pick(st, run)); continue; }
    if (st.duel) { ok(S.OPP[st.duel], `opponent ${st.duel}`); seen.duels.push(st.duel); }
    if (st.reward) seen.rewards.push(st.reward);
    if (st.rack) seen.rewards.push(...st.rack);
    if (st.actEnd) seen.actEnd = st.actEnd;
    if (st.scene) seen.scenes.push(st.scene);
    if (st.who) ok(st.who === 'you' || S.SPEAKERS[st.who], `speaker ${st.who}`);
    if (st.t) seen.lines.push(st.t);
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

// chapter 2: both roads, after either chapter 1 road
for (const prev of ['A', 'B']) {
  const a = walk('ch2', fork('A'), { prev: { ch1: prev } });
  ok(a.run.done && a.run.road === 'A', 'ch2 road A ends as A');
  ok(a.seen.duels.join() === 'cultist,zknight,corpse,dragith', 'ch2 road A duels: ' + a.seen.duels);
  ok(a.seen.maps.join() === 'camp,a_vaults,a_tunnels,a_chamber', 'ch2 road A dots: ' + a.seen.maps);
  ok(a.seen.rewards.join() === 'White Knight Armor', 'ch2 road A reward');
  const delrith = a.seen.lines.some((t) => t.startsWith('Delrith climbed')), draynor = a.seen.lines.some((t) => t.startsWith('Count Draynor rose'));
  ok(prev === 'A' ? delrith && !draynor : draynor && !delrith, `ch2 tells Sir Amik about chapter 1's road ${prev}`);
  const b = walk('ch2', fork('B'), { prev: { ch1: prev } });
  ok(b.run.road === 'B', 'ch2 road B ends as B');
  ok(b.seen.duels.join() === 'cultist,scout,shadow,melzar', 'ch2 road B duels: ' + b.seen.duels);
  ok(b.seen.maps.join() === 'camp,b_road,b_house,b_prison', 'ch2 road B dots: ' + b.seen.maps);
  ok(b.seen.rewards.join() === 'Cabbage Armor', 'ch2 road B reward');
  for (const k of [0, 1, 2]) ok(walk('ch2', (st) => (st.fork ? st.choose.findIndex((o) => o.road === prev) : Math.min(k, st.choose.length - 1)), { prev: { ch1: prev } }).run.road === prev, `ch2 option ${k}`);
}
{ // with no chapter 1 road known (an old save), neither line is said and nothing breaks
  const a = walk('ch2', fork('A'));
  ok(!a.seen.lines.some((t) => t.startsWith('Delrith climbed') || t.startsWith('Count Draynor rose')), 'no chapter 1 road: no line about it');
  ok(S.start('ch2').dots.falador === 'done' && !S.start('ch2').dots.lumbridge, 'chapter 2 starts in Falador');
}

// chapter 3 (2026-11-22): both roads, after either chapter 2 road; the question list; the rack; the end of Act 1
for (const prev of ['A', 'B']) {
  for (const road of ['A', 'B']) {
    const w = walk('ch3', fork(road), { prev: { ch1: 'A', ch2: prev } });
    ok(w.run.done && w.run.road === road, `ch3 road ${road} ends as ${road}`);
    ok(w.seen.duels.join() === (road === 'A' ? 'bknight,peon,honour,daquarius' : 'bknight,pair,smith,daquarius'), `ch3 road ${road} duels: ${w.seen.duels}`);
    ok(w.seen.maps.join() === (road === 'A' ? 'a_balcony,a_door,hall' : 'b_corridor,b_forge,hall'), `ch3 road ${road} dots: ${w.seen.maps}`);
    ok(w.seen.rewards.join() === 'Kinshra Shortbow,Kinshra Sword,Kinshra Battlestaff', 'ch3: the rack');
    ok(w.seen.actEnd === 1, 'ch3 ends Act 1');
    const chest = w.seen.lines.some((t) => t.startsWith('I found the chest')), melzar = w.seen.lines.some((t) => t.startsWith('Melzar the Mad was locked'));
    ok(prev === 'A' ? chest && !melzar : melzar && !chest, `ch3 tells Daquarius about chapter 2's road ${prev}`);
    ok(w.seen.lines.includes('Not that much. I came in over the balcony.') === (road === 'A'), `the balcony line only on the quiet road (${road})`);
    ok(w.seen.lines.includes('Lucan Mordrel.'), 'the name is said');
    // the questions (the walker takes the last option each time): the reveal only once the rest are asked, then "That's all."
    const q = w.seen.asked;
    ok(q.length === 5 && q[0].join() === 'What is the Blade of Memories?,Why did the Kinshra have the Blade?', 'two questions first (not Sulla, not the reveal): ' + JSON.stringify(q[0]));
    ok(q[1].includes('Who was Sulla?'), 'Sulla once the name has come up: ' + JSON.stringify(q[1]));
    ok(q[3].join() === 'Then who took it from Falador?' && q[4].join() === "That's all.", 'the reveal last, then done');
  }
  // the peon's cloak: "you said" only after the bluff
  for (const k of [0, 3]) {
    const w = walk('ch3', (st, run) => (st.fork ? 0 : run.node === 'a_balcony' ? k : 0), { prev: { ch1: 'A', ch2: prev } });
    const said = w.seen.lines.includes('Recruits get a Kinshra cloak, you said.'), plain = w.seen.lines.includes("That's a nice cloak. Kinshra crest and everything.");
    ok(k === 3 ? said && !plain : plain && !said, `the cloak line fits option ${k}`);
  }
}
{ // a question list, saved part way: the same questions are left
  const run = S.start('ch3', { ch2: 'A' });
  for (let g = 0; g < 500; g++) {
    const st = S.step(run);
    if (st.ask && (run.asked || []).length === 2) break;
    if (st.onMap) S.enter(run, S.dotsOf(run).find((d) => d.state === 'open').id);
    else if (st.choose) S.advance(run, 0); else S.advance(run);
  }
  const copy = JSON.parse(JSON.stringify(run));
  ok(S.step(copy).choose.length === 1 && JSON.stringify(S.step(copy)) === JSON.stringify(S.step(run)), 'a saved question list carries on');
  ok(S.start('ch3').dots.gate === 'done', 'chapter 3 starts at the gate');
}
{ // every order of questions (Ramon, 2026-11-23): "Who was Sulla?" never before an answer that names him
  const QS = S.CHAPTERS.find((c) => c.id === 'ch3').nodes.hall.find((st) => st.ask);
  const names = ['What is the Blade of Memories?', 'Why did the Kinshra have the Blade?'];
  let orders = 0, bad = 0;
  const go = (run) => {
    const st = S.step(run);
    if (!st.ask || run.r) { orders += 1; return; }
    const ts = st.choose.map((o) => o.t);
    if (ts.includes('Who was Sulla?') && !names.some((n) => (run.asked || []).map((k) => QS.ask[k].t).includes(n))) bad += 1;
    for (let k = 0; k < ts.length; k++) {
      const c = JSON.parse(JSON.stringify(run));
      S.advance(c, k);
      while (c.r) S.advance(c);
      go(c);
    }
  };
  const run = S.start('ch3', { ch2: 'A' });
  for (let g = 0; g < 500 && !S.step(run).ask; g++) {
    const st = S.step(run);
    if (st.onMap) S.enter(run, S.dotsOf(run).find((d) => d.state === 'open').id);
    else if (st.choose) S.advance(run, 0); else S.advance(run);
  }
  go(run);
  ok(orders === 4 && !bad, `every order of questions (${orders}) names Sulla before asking who he was (${bad} wrong)`);
  ok(QS.ask.every((o) => !o.after || o.after.every((k) => QS.ask[k] && /Sulla/.test(QS.ask[k].then.map((l) => l.t).join(' ')))), 'the answers Sulla waits for both name him');
}
ok(S.NEXT.ch2.id === 'ch3' && S.NEXT.ch3.soon && S.chapter('ch2').title === 'The White City' && S.chapter('ch3').title === 'The Black Fortress', 'chapter titles and what follows');

// the opponents' decks and Locations name real cards (placeholder decks name decks from cards.json)
const deckNames = new Set(CARDS.decks.map((d) => d.name));
for (const [id, o] of Object.entries(S.OPP)) {
  if (o.deckName) ok(deckNames.has(o.deckName), `${id}: deck ${o.deckName}`);
  if (o.deck) for (const n of Object.keys(o.deck)) ok(!!CARDS.cards[n], `${id}: card ${n}`);
  ok(o.lose && o.lose.t, `${id}: a losing line`);
  if (o.location) ok(CARDS.cards[o.location] && CARDS.cards[o.location].kind === 'location', `${id}: Location ${o.location}`);
}
for (const ch of S.CHAPTERS) for (const card of Object.values(ch.rewards || {})) ok(CARDS.cards[card] && CARDS.cards[card].story === 'reward', `${ch.id}: reward ${card}`);

console.log(`${checks} checks · all passed`);
