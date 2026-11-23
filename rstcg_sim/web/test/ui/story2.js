// Story Mode chapter 2 in the page (2026-11-20): from a save that finished chapter 1 (made before chapter 2
// existed), Continue starts chapter 2; road A is played through by clicking (the duels started for real, then
// ended with the test hook), with the line that depends on chapter 1's road; Replay Chapter plays road B, with
// the Prayer Cape greyed out for Melzar's Hidden Prison; both rewards land; no "Bot" anywhere.
// node web/test/ui/story2.js [W H]   ends "all passed"; screenshots in web/test/ui/shots/story2/
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'story2');
fs.mkdirSync(SHOTS, { recursive: true });
const W = Number(process.argv[2]) || 1920, H = Number(process.argv[3]) || 1080;
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };

const DIST = path.join(__dirname, '..', '..', 'dist');
const site = require('http').createServer((req, res) => {
  fs.readFile(path.join(DIST, 'arena.html'), (e, d) => { res.writeHead(e ? 404 : 200, { 'Content-Type': 'text/html' }); res.end(d); });
});

(async () => {
  await new Promise((r) => site.listen(0, '127.0.0.1', r));
  const PAGE = `http://127.0.0.1:${site.address().port}/arena.html`;
  const b = await chromium.launch();
  const errors = [];
  const ctx = await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => {
    window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
    if (!sessionStorage.getItem('kept')) {
      localStorage.clear();
      localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' }));
      sessionStorage.setItem('kept', '1');
    }
  });
  await page.goto(PAGE);
  await page.waitForFunction(() => window.__arena);
  await page.evaluate(() => window.__arena.act('tutskip'));
  await page.waitForTimeout(300);
  // a save from before chapter 2: chapter 1 finished by road A, no `path`
  await page.evaluate(() => {
    const p = JSON.parse(localStorage.getItem('rstcg-profile-v1'));
    p.story = { main: { ch: 'ch1', node: null, i: 0, r: null, dots: { lumbridge: 'done' }, at: null, tone: { stranger1: 'witty', stranger2: 'witty' }, road: 'A', done: true },
      replay: null, roads: { ch1: ['A'] }, claimed: { 'ch1:A': 'Claws of Guthix' } };
    p.coll['Claws of Guthix'] = 1;
    localStorage.setItem('rstcg-profile-v1', JSON.stringify(p));
  });
  await page.reload();
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(300);

  const A = (what, v) => page.evaluate(([w, x]) => window.__arena.act(w, x), [what, v]);
  const screen = () => page.evaluate(() => window.__arena.ui.screen);
  const shot = async (name) => { await page.waitForTimeout(250); await page.screenshot({ path: path.join(SHOTS, `${name}.png`) }); };
  const text = () => page.evaluate(() => document.querySelector('#board').innerText);
  const said = [];
  const shots = { want: new Set(), done: new Set() };
  const shotWhen = async (re, name) => { if (!shots.done.has(name) && re.test(await text())) { shots.done.add(name); await shot(name); } };

  async function play(pick, upto = 400) {
    for (let n = 0; n < upto; n++) {
      const sc = await screen();
      if (sc !== 'scene') return sc;
      const t = await text();
      said.push(t);
      await shotWhen(/Sir Amik Varze/, 'amik');
      await shotWhen(/Far to the north/, 'flash-far');
      await shotWhen(/cabbage with a moustache/, 'melzar-voice');
      const opts = await page.$$('#board [data-do="stpick"]');
      if (opts.length) {
        const labels = await Promise.all(opts.map((o) => o.innerText()));
        await page.click(`#board [data-do="stpick"][data-v="${pick(labels)}"]`);
        continue;
      }
      const next = await page.$('#board [data-do="stnext"], #board [data-do="streward"]');
      if (next) { await page.click('#board [data-do="stnext"], #board [data-do="streward"]').catch(() => {}); continue; }
      return 'stopped';
    }
    return 'too long';
  }
  async function duel(name, { loc = null, prison = false } = {}) {
    ok(await page.$('#board [data-do="stduel"]'), `${name}: a duel waits`);
    await page.click('#board [data-do="stduel"]');
    ok(await screen() === 'pick', `${name}: the deck picker`);
    const pick = await text();
    ok(!/\bBOT\b|vs Bot|Bot difficulty/.test(pick), `${name}: no "Bot" on the picker`);
    if (prison) {
      const off = await page.$('#board .ct:disabled');
      ok(off && /Prayer Cape/.test(await off.innerText()), "Melzar's fight: the Prayer Cape is greyed out");
      ok(!(await page.$('#board [data-do="pickcape"][data-v="Prayer Cape"]')), "Melzar's fight: the Prayer Cape can't be picked");
      ok(/can't be earned there/.test(pick), "Melzar's fight: the picker says why");
      await shot('prison-pick');
    }
    await page.click('#board [data-do="ststart"]');
    await page.waitForFunction(() => window.__arena.ui.g && !window.__arena.ui.intro, null, { timeout: 15000 });
    await page.waitForTimeout(1200);
    const board = await text();
    ok(!/\bBot\b|\bBOT\b/.test(board), `${name}: no "Bot" on the board`);
    { const want = await page.evaluate(() => { const st = window.__arena.ui.config.story; return !!window.__arena.storyFace(st.opp); });
      ok(!!(await page.$('#board .np-face img')) === want, `${name}: the nameplate shows the opponent's chathead when there is one (${want})`); }
    if (loc) {
      const where = await page.evaluate(() => window.__arena.ui.g.location);
      ok(where === loc, `${name}: ${loc} is locked in play (${where})`);
      ok(await page.$('#sttaunt'), `${name}: says something at the start`);
      await shot(`${name}-start`);
      await page.evaluate(() => window.__arena.story.hit());
      await page.waitForFunction(() => /more fight|good arm/.test((document.querySelector('#sttaunt') || {}).innerText || ''), null, { timeout: 12000 }).catch(() => {});
      ok(/more fight|good arm/.test(await page.evaluate(() => (document.querySelector('#sttaunt') || {}).innerText || '')), `${name}: reacts to the first hit`);
    } else if (name === 'cultist' || name === 'shadow') await shot(`${name}-duel`);
    await page.evaluate(() => window.__arena.story.endDuel(true));
    await page.waitForFunction(() => window.__arena.ui.screen === 'result');
    await page.click('#board [data-do="stwon"]');
  }
  const first = () => 0;
  const road = (r) => (labels) => { const k = labels.findIndex((l) => (r === 'A' ? /White Knights/ : /Lead the way/).test(l)); return k >= 0 ? k : 0; };

  // ----- the Story screen offers chapter 2 -----
  await A('story');
  ok(await screen() === 'story', 'the Story screen');
  ok(/The White City/.test(await text()) && await page.$('#board [data-do="stchapter"][data-v="ch2"]'), 'chapter 2 (The White City) is open, with Continue');
  await shot('hub');
  await page.click('#board [data-do="stchapter"][data-v="ch2"]');
  ok(await screen() === 'scene', 'Continue opens chapter 2');
  ok(await page.evaluate(() => window.__arena.ui.sc.scene.scene) === 'Falador', 'chapter 2 opens in Falador');
  await shot('falador');
  const d0 = await page.evaluate(() => window.__arena.story.data());
  ok(d0.main.ch === 'ch2' && d0.main.prev && d0.main.prev.ch1 === 'A', `the old save's chapter 1 road is carried into chapter 2 (${JSON.stringify(d0.main.prev)})`);
  ok(d0.roads.ch1.join() === 'A' && d0.claimed['ch1:A'], 'chapter 1 progress kept');
  ok(await play(first) === 'map', 'the knight sends you to the map');
  await shot('map-1');

  // ----- road A -----
  await page.click('#board [data-do="stdot"]');
  ok(await play(first) === 'stopped', 'the cultist');
  await duel('cultist');
  ok(await play(road('A')) === 'map', 'road A: the map');
  await shot('map-roadA');
  for (const [opp, loc] of [['zknight'], ['corpse'], ['dragith', 'Falador Tunnels']]) {
    await page.click('#board [data-do="stdot"]');
    ok(await play(first) === 'stopped', `on to ${opp}`);
    await duel(opp, { loc });
    const sc = await play(first);
    if (opp === 'dragith') { ok(sc === 'stopped', 'the end of road A'); break; }
    ok(sc === 'map', `after ${opp}, the map`);
  }
  const all = said.join('\n');
  ok(/Delrith climbed out of Count Draynor's coffin/.test(all) && !/Count Draynor rose from the stone circle/.test(all), 'Sir Amik hears about Delrith (chapter 1 went by Draynor)');
  ok(/You pick up the old breastplate/.test(all), 'the breastplate is found, not given');
  ok(/Chapter 2 complete/i.test(await text()), 'chapter 2 complete');
  await shot('chapter-done');
  await page.click('#board [data-do="stdone"]');
  const d1 = await page.evaluate(() => window.__arena.story.data());
  ok(d1.roads.ch2 && d1.roads.ch2.join() === 'A', 'road A recorded');
  ok(d1.path && d1.path.ch2 === 'A', 'the main story remembers chapter 2 went by road A');
  ok(await page.evaluate(() => window.__arena.profile.coll['White Knight Armor']) === 1, 'White Knight Armor in the collection');
  await shot('hub-done');

  // ----- Replay Chapter 2: road B -----
  await page.click('#board [data-do="streplay"][data-v="ch2"]');
  ok(await play(first) === 'map', 'replay: the map');
  await page.click('#board [data-do="stdot"]');
  ok(await play(first) === 'stopped', 'replay: the cultist');
  await duel('cultist-b');
  ok(await play(road('B')) === 'map', 'replay: road B');
  for (const [opp, loc] of [['scout'], ['shadow'], ['melzar', 'Hidden Prison']]) {
    await page.click('#board [data-do="stdot"]');
    ok(await play(first) === 'stopped', `on to ${opp}`);
    if (opp === 'shadow') await shot('house');
    await duel(opp, { loc, prison: opp === 'melzar' });
    const sc = await play(first);
    if (opp === 'melzar') { ok(sc === 'stopped', 'the end of road B'); break; }
    ok(sc === 'map', `after ${opp}, the map`);
  }
  await page.click('#board [data-do="stdone"]');
  const d2 = await page.evaluate(() => window.__arena.story.data());
  ok(d2.roads.ch2.sort().join() === 'A,B', 'both chapter 2 roads done');
  ok(d2.path.ch2 === 'A' && d2.main.road === 'A', 'the replay left the main story alone');
  ok(await page.evaluate(() => window.__arena.profile.coll['Cabbage Armor']) === 1, 'Cabbage Armor in the collection');
  ok(!/\bBot\b/.test(said.join('\n')), 'no "Bot" in any line');
  await shot('hub-both');

  // ----- the rewards are real cards: the deck rules allow them, and refuse the bosses' cards -----
  const rules = await page.evaluate(() => {
    const deck = (extra) => Object.assign({ 'Bronze Bar': 56 }, extra);
    const r = (x) => window.__arena.deckRules(deck(x), false).known;
    return { wka: r({ 'White Knight Armor': 1, Shrimp: 3 }), cab: r({ 'Cabbage Armor': 1, Shrimp: 3 }), boss: r({ 'Cabbage Mace': 1, Shrimp: 3 }), cabbage: r({ Cabbage: 4 }) };
  });
  ok(rules.wka && rules.cab, `reward cards are legal (${JSON.stringify(rules)})`);
  ok(!rules.boss && !rules.cabbage, `boss cards and Cabbages are refused (${JSON.stringify(rules)})`);

  ok(!errors.length, 'no page errors: ' + errors.join(' | '));
  await b.close(); site.close();
  console.log(`${checks} checks · ${failures ? failures + ' FAILED' : 'all passed'}`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
