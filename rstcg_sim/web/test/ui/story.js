// Story Mode in the page: both roads of chapter 1 played through by clicking (the duels are started for real
// and then ended at once with a test hook), losing and trying again, the map, the reward, Replay Chapter not
// touching the main story, old saves, and that a story duel never says "Bot".
// node web/test/ui/story.js [W H]   ends "all passed"; screenshots in web/test/ui/shots/story/
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'story');
fs.mkdirSync(SHOTS, { recursive: true });
const W = Number(process.argv[2]) || 1920, H = Number(process.argv[3]) || 1080;
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };

// served over http: a file:// page can lose its saved data on reload (CLAUDE.md)
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
  const A = (what, v) => page.evaluate(([w, x]) => window.__arena.act(w, x), [what, v]);
  const screen = () => page.evaluate(() => window.__arena.ui.screen);
  const shot = async (name) => { await page.waitForTimeout(250); await page.screenshot({ path: path.join(SHOTS, `${name}.png`) }); };
  const text = () => page.evaluate(() => document.querySelector('#board').innerText);
  let shots = 0;

  // click through the story until it stops somewhere that isn't a line or a choice: pick(k, labels) picks
  async function play(pick, upto = 400) {
    for (let n = 0; n < upto; n++) {
      const sc = await screen();
      if (sc !== 'scene') return sc;
      const opts = await page.$$('#board [data-do="stpick"]');
      if (opts.length) {
        const labels = await Promise.all(opts.map((o) => o.innerText()));
        if (shots < 3 && labels.length === 4) { await shot(`choice-${shots}`); shots++; }
        await page.click(`#board [data-do="stpick"][data-v="${pick(labels)}"]`);
        continue;
      }
      const next = await page.$('#board [data-do="stnext"], #board [data-do="streward"]');
      if (next) { await page.click('#board [data-do="stnext"], #board [data-do="streward"]').catch(() => {}); continue; }
      return 'stopped';
    }
    return 'too long';
  }
  async function duel(won, name) {
    ok(await page.$('#board [data-do="stduel"]'), `${name}: a duel waits`);
    { const t = await text(); ok(!/A duel\.|A boss fight\./.test(t) && /duel/i.test(t), `${name}: a battle cry and a Duel button (${t.slice(-80).replace(/\s+/g, ' ')})`); }
    if (name === 'delrith') await shot('delrith-cry');
    await page.click('#board [data-do="stduel"]');
    ok(await screen() === 'pick', `${name}: the deck picker`);
    if (name === 'bandit') await shot('pick');
    const pick = await text();
    ok(!/\bBOT\b|vs Bot|Bot difficulty/.test(pick), `${name}: no "Bot" on the picker`);
    await page.click('#board [data-do="ststart"]');
    await page.waitForFunction(() => window.__arena.ui.g && !window.__arena.ui.intro, null, { timeout: 15000 });
    await page.waitForTimeout(1200);
    const board = await text();
    ok(!/\bBot\b|\bBOT\b/.test(board), `${name}: no "Bot" on the board (${(board.match(/.{0,30}\bBot\b.{0,30}|.{0,20}BOT'S.{0,20}/) || [''])[0]})`);
    if (name === 'bandit') await shot('duel');
    if (name === 'delrith' || name === 'draynor') {
      const loc = await page.evaluate(() => window.__arena.ui.g.location);
      ok(loc === (name === 'delrith' ? 'Draynor Manor' : 'Moonlit Circle'), `${name}: the Location is locked in play (${loc})`);
      ok(await page.$('#sttaunt'), `${name}: says something at the start`);
      await shot(`${name}-start`);
      await page.evaluate(() => window.__arena.story.hit());
      await page.waitForFunction(() => /felt that|wounded me/.test((document.querySelector('#sttaunt') || {}).innerText || ''), null, { timeout: 12000 }).catch(() => {});
      const said = await page.evaluate(() => (document.querySelector('#sttaunt') || {}).innerText || '');
      ok(/felt that|wounded me/.test(said), `${name}: reacts to the first hit (${said.slice(0, 60)})`);
      await shot(`${name}-hit`);
    }
    await page.evaluate((w) => window.__arena.story.endDuel(w), won);
    await page.waitForFunction(() => window.__arena.ui.screen === 'result');
    const res = await text();
    ok(/Continue/.test(res) && !/Bot deck/.test(res), `${name}: story results`);
    if (name === 'bandit' && won) await shot('result');
    await page.click(`#board [data-do="${won ? 'stwon' : 'stlost'}"]`);
  }
  const first = () => 0;
  const road = (r) => (labels) => { const k = labels.findIndex((l) => (r === 'A' ? /Wise Old Man/ : /Reldo/).test(l)); return k >= 0 ? k : 0; };

  // ----- the menu has Story; a new story -----
  ok(/Story/.test(await text()), 'Story on the menu');
  await shot('menu');
  await A('story');
  ok(await screen() === 'story', 'the Story screen');
  await shot('hub');
  await page.click('#board [data-do="stnew"]');
  ok(await screen() === 'scene', 'New Game opens a scene');
  await shot('scene-hans');
  ok(/Hans/.test(await text()), 'Hans speaks first');
  ok(await play(first) === 'map', 'Hans sends you to the map');
  await shot('map-1');
  await page.click('#board [data-do="stdot"]');

  // ----- the bandit: lose, try again, win -----
  ok(await play(first) === 'stopped', 'stops at the bandit duel');
  await duel(false, 'bandit-lose');
  ok(await screen() === 'scene' && /grown a spine/.test(await text()), 'the losing line, then retry');
  await shot('lose');
  await page.click('#board [data-do="stretry"]');
  ok(await screen() === 'pick', 'Try again: the picker');
  await page.click('#board [data-do="stback"]');
  await duel(true, 'bandit');

  // ----- the stranger, then the fork: road A -----
  ok(await play(road('A')) === 'map', 'after the fork, the map');
  const tone = await page.evaluate(() => window.__arena.story.data().main.tone);
  ok(tone.stranger1 === 'polite' && tone.stranger2 === 'polite', 'tone recorded');
  await shot('map-roadA');
  // leaving the story and coming back: Continue picks up on the map
  await A('menu'); await A('story');
  ok(/Continue/.test(await text()), 'Continue on the Story screen');
  await page.click('#board [data-do="stcont"]');
  ok(await screen() === 'map', 'Continue goes back to the map');
  for (const opp of ['highwayman', 'skeleton', 'delrith']) {
    await page.click('#board [data-do="stdot"]');
    ok(await play(first) === 'stopped', `on to ${opp}`);
    await duel(true, opp);
    const sc = await play(first);
    if (opp === 'delrith') { ok(sc === 'stopped', 'the reward'); break; }
    ok(sc === 'map', `after ${opp}, the map`);
  }
  ok(await page.evaluate(() => window.__arena.ui.sc.scene && window.__arena.ui.sc.scene.scene) === 'Draynor Manor cellar', 'after Delrith, still in the cellar');
  await shot('chapter-done');
  ok(/Chapter 1 complete/i.test(await text()), "chapter complete");
  await page.click('#board [data-do="stdone"]');
  const d1 = await page.evaluate(() => window.__arena.story.data());
  ok(d1.roads.ch1 && d1.roads.ch1.join() === 'A', 'road A recorded as done');
  ok(d1.main.done, 'main story finished chapter 1');
  await shot('hub-done');

  // ----- Replay Chapter: road B, the main story untouched -----
  const mainBefore = JSON.stringify(d1.main);
  await page.click('#board [data-do="streplay"]');
  { const r = await play(first); ok(r === 'map', 'replay: the map ' + r + ' ' + await screen()); await shot('replay-map'); }
  await page.click('#board [data-do="stdot"]');
  ok(await play(first) === 'stopped', 'replay: the bandit');
  await duel(true, 'bandit-replay');
  ok(await play(road('B')) === 'map', 'replay: road B');
  await page.click('#board [data-do="stdot"]');
  ok(await play(first) === 'stopped', 'replay: on to the goblins');
  await duel(true, 'goblins');
  ok(await play(first) === 'map', 'replay: after the goblins, the map');
  await page.click('#board [data-do="stdot"]');
  ok(await play(first) === 'stopped', 'replay: on to the dark wizard');
  await shot('night');
  await duel(true, 'wizard');
  ok(await play(first) === 'stopped', 'replay: Count Draynor');
  await shot('draynor');
  await duel(true, 'draynor');
  await play(first);
  await page.click('#board [data-do="stdone"]');
  const d2 = await page.evaluate(() => window.__arena.story.data());
  ok(JSON.stringify(d2.main) === mainBefore, 'Replay Chapter left the main story alone');
  ok(d2.roads.ch1.sort().join() === 'A,B', 'both roads done');
  ok(d2.replay === null, 'the replay is cleared at the end');

  // ----- a saved story duel resumes as a story duel -----
  await page.click('#board [data-do="streplay"]');
  await play(first); await page.click('#board [data-do="stdot"]'); await play(first);
  await page.click('#board [data-do="stduel"]'); await page.click('#board [data-do="ststart"]');
  await page.waitForFunction(() => window.__arena.ui.g && !window.__arena.ui.intro, null, { timeout: 15000 });
  await page.waitForTimeout(800);
  await A('leave');
  ok(/Story · vs Bandit/.test(await text()), 'the menu offers to resume the story duel');
  await page.reload();
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(300);
  await A('resume');
  await page.waitForFunction(() => window.__arena.ui.g, null, { timeout: 15000 });
  await page.waitForTimeout(1500);
  ok(await page.evaluate(() => !!window.__arena.ui.config.story), 'the resumed duel is still a story duel');
  await page.evaluate(() => window.__arena.story.endDuel(true));
  await page.waitForFunction(() => window.__arena.ui.screen === 'result');
  await page.click('#board [data-do="stwon"]');
  ok(await screen() === 'scene' && /I'm going/.test(await text()), 'after the resumed duel the story goes on');
  ok(await page.evaluate(() => window.__arena.ui.sc.scene && window.__arena.ui.sc.scene.scene) === 'Lumbridge Swamp', 'after a reload and a resumed duel, the scene is where the story is');

  // ----- an old profile (no story at all) loads, and the Story screen copes -----
  await page.evaluate(() => { const p = JSON.parse(localStorage.getItem('rstcg-profile-v1')); delete p.story; localStorage.setItem('rstcg-profile-v1', JSON.stringify(p)); });
  await page.reload();
  await page.waitForFunction(() => window.__arena);
  await A('story');
  ok(/New Game/.test(await text()), 'an old profile: New Game');

  ok(!errors.length, 'no page errors: ' + errors.join(' | '));
  await b.close();
  site.close();
  console.log(`${checks} checks · ${failures ? failures + ' FAILED' : 'all passed'}`);
  process.exit(failures ? 1 : 0);
})();
