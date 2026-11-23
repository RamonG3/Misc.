// Story Mode chapter 3 in the page (2026-11-22): from a save that finished chapter 2 by road B, Continue starts
// chapter 3 (The Black Fortress); road A is clicked through (duels started for real, then ended with the test hook),
// with the lines that depend on chapter 2's road and the Peon's bluff; Lord Daquarius fights in the Kinshra Hall;
// the questions, the reveal last; the rack gives one Kinshra weapon; the end of Act 1 (black, then its title, timed to
// the music's cue); Replay Chapter plays road B and the rack offers only the two left; a third time the rack is
// empty and passed by; one rematch button for the Lord; the Kinshra Hall stays out of the menu's tour; no "Bot".
// node web/test/ui/story3.js [W H]   ends "all passed"; screenshots in web/test/ui/shots/story3/
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'story3');
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
  // chapter 1 by road A, chapter 2 by road B, both finished in the main story
  await page.evaluate(() => {
    const p = JSON.parse(localStorage.getItem('rstcg-profile-v1'));
    p.story = { main: { ch: 'ch2', node: null, i: 0, r: null, dots: { falador: 'done', camp: 'done', b_road: 'done', b_house: 'done', b_prison: 'done' }, at: null,
      tone: {}, road: 'B', done: true, prev: { ch1: 'A' } },
      replay: null, roads: { ch1: ['A'], ch2: ['B'] }, path: { ch1: 'A', ch2: 'B' }, claimed: { 'ch1:A': 'Claws of Guthix', 'ch2:B': 'Cabbage Armor' } };
    p.coll['Claws of Guthix'] = 1; p.coll['Cabbage Armor'] = 1;
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
  const asked = [];
  const done = new Set();
  const shotWhen = async (re, name) => { if (!done.has(name) && re.test(await text())) { done.add(name); await shot(name); } };

  // click through the scene: options (pick(labels)), lines; stop at a duel, the map, the rack or an act's ending
  async function play(pick, upto = 400) {
    for (let n = 0; n < upto; n++) {
      const sc = await screen();
      if (sc !== 'scene') return sc;
      if (await page.evaluate(() => !!window.__arena.ui.sc.finale)) return 'finale';
      if (await page.$('#board [data-do="strack"]')) return 'rack';
      const t = await text();
      said.push(t);
      await shotWhen(/mercy budget/, 'gate');
      await shotWhen(/Lord Daquarius\n/, 'daquarius');
      await shotWhen(/Lucan Mordrel\./, 'lucan');
      const opts = await page.$$('#board [data-do="stpick"]');
      if (opts.length) {
        const labels = await Promise.all(opts.map((o) => o.innerText()));
        if (labels.some((l) => /Blade of Memories\?|Who was Sulla|Why did the Kinshra|who took it|That's all/.test(l))) asked.push(labels.map((l) => l.replace(/^\d+\s*/, ''))); if (asked.length === 1) await shot('questions');
        await page.click(`#board [data-do="stpick"][data-v="${pick(labels)}"]`);
        continue;
      }
      const next = await page.$('#board [data-do="stnext"], #board [data-do="streward"]');
      if (next) { await page.click('#board [data-do="stnext"], #board [data-do="streward"]').catch(() => {}); continue; }
      return 'stopped';
    }
    return 'too long';
  }
  async function duel(name, { loc = null } = {}) {
    ok(await page.$('#board [data-do="stduel"]'), `${name}: a duel waits`);
    await page.click('#board [data-do="stduel"]');
    ok(await screen() === 'pick', `${name}: the deck picker`);
    ok(!/\bBOT\b|vs Bot|Bot difficulty/.test(await text()), `${name}: no "Bot" on the picker`);
    await page.click('#board [data-do="ststart"]');
    await page.waitForFunction(() => window.__arena.ui.g && !window.__arena.ui.intro, null, { timeout: 15000 });
    await page.waitForTimeout(1200);
    ok(!/\bBot\b|\bBOT\b/.test(await text()), `${name}: no "Bot" on the board`);
    ok(!!(await page.$('#board .np-face img')), `${name}: the nameplate shows the opponent's chathead`);
    if (loc) {
      const where = await page.evaluate(() => window.__arena.ui.g.location);
      ok(where === loc, `${name}: ${loc} is locked in play (${where})`);
      ok(/Kneel, and I'll make it quick/.test(await page.evaluate(() => (document.querySelector('#sttaunt') || {}).innerText || '')), `${name}: says something at the start`);
      await shot(`${name}-start`);
      await page.evaluate(() => window.__arena.story.hit());
      await page.waitForFunction(() => /Who trained you/.test((document.querySelector('#sttaunt') || {}).innerText || ''), null, { timeout: 12000 }).catch(() => {});
      ok(/Who trained you/.test(await page.evaluate(() => (document.querySelector('#sttaunt') || {}).innerText || '')), `${name}: reacts to the first hit`);
    }
    await page.evaluate(() => window.__arena.story.endDuel(true));
    await page.waitForFunction(() => window.__arena.ui.screen === 'result');
    await page.click('#board [data-do="stwon"]');
  }
  // the rack: the cards on offer, then take the first; the reward panel follows
  async function rack(expect, label) {
    const offered = await page.$$eval('#board [data-do="strack"]', (els) => els.map((e) => e.dataset.v));
    ok(offered.join() === expect.join(), `${label}: the rack offers ${offered.join(', ')} (want ${expect.join(', ')})`);
    await shot(`${label}-rack`);
    await page.click(`#board [data-do="strack"][data-v="${offered[0]}"]`);
    ok(/Added to your collection/.test(await text()), `${label}: ${offered[0]} added`);
    await page.click('#board [data-do="stnext"]');
    return offered[0];
  }
  // the end of Act 1: the last line, black on the music's cue, then the title; Continue only with the title
  async function finale(label) {
    const t0 = Date.now();
    ok(/from the Wilderness/.test(await text()), `${label}: the last line`);
    await page.click('#board .stbox').catch(() => {});
    ok(await page.evaluate(() => !!window.__arena.ui.sc.finale), `${label}: a tap doesn't skip the ending`);
    if (label === 'A') await shot('finale-line');
    await page.waitForSelector('#board .stblack', { timeout: 15000 });
    const cue = await page.evaluate(() => window.RSAudio.cue('finale'));
    ok(cue > 8 && cue < 12, `the ending's cue is the music's last chord (${cue} s)`);
    await page.waitForSelector('#board .stactend', { timeout: 15000 });
    if (label === 'A') await shot('finale-title');
    ok(/End of Act 1/.test(await text()), `${label}: "End of Act 1"`);
    await page.waitForSelector('#board .stactend .btn-g', { timeout: 15000 });
    const took = (Date.now() - t0) / 1000;
    ok(took > cue && took < cue + 6, `${label}: the title and Continue come after the cue (${took.toFixed(1)} s)`);
    await page.click('#board .stactend .btn-g');
    ok(/Chapter 3 complete/i.test(await text()), `${label}: chapter 3 complete`);
  }
  const first = () => 0;
  const quiet = (labels) => { const k = labels.findIndex((l) => /Quietly/.test(l)); if (k >= 0) return k; const r = labels.findIndex((l) => /new recruit/.test(l)); return r >= 0 ? r : 0; };
  const loud = (labels) => { const k = labels.findIndex((l) => /Loudly/.test(l)); return k >= 0 ? k : 0; };

  // ----- the Story screen offers chapter 3 -----
  await A('story');
  const hub = await text();
  ok(/The White City/.test(hub) && /The Black Fortress/.test(hub), 'chapter 2 and 3 have their new names');
  ok(/Act 2/.test(hub) && /COMING SOON/.test(hub), 'Act 2 is coming soon');
  ok(await page.$('#board [data-do="stchapter"][data-v="ch3"]'), 'chapter 3 opens with Continue');
  await shot('hub');
  await page.click('#board [data-do="stchapter"][data-v="ch3"]');
  ok(await screen() === 'scene', 'Continue opens chapter 3');
  ok(await page.evaluate(() => window.__arena.ui.sc.scene.scene) === "The Black Knights' Fortress", 'chapter 3 opens outside the fortress');
  await shot('fortress');
  const d0 = await page.evaluate(() => window.__arena.story.data());
  ok(d0.main.ch === 'ch3' && d0.main.prev.ch2 === 'B' && d0.main.prev.ch1 === 'A', `the earlier roads are carried in (${JSON.stringify(d0.main.prev)})`);

  // ----- road A -----
  ok(await play(first) === 'stopped', 'the gate guard');
  await duel('bknight');
  ok(await play(quiet) === 'map', 'road A: the map');
  await shot('map-A');
  for (const [opp, loc] of [['peon'], ['honour'], ['daquarius', 'The Kinshra Hall']]) {
    await page.click('#board [data-do="stdot"]');
    ok(await play(quiet) === 'stopped', `on to ${opp}`);
    await duel(opp, { loc });
    const sc = await play(quiet);
    if (opp === 'daquarius') { ok(sc === 'rack', 'after the Lord: the rack'); break; }
    ok(sc === 'map', `after ${opp}, the map`);
  }
  const all = said.join('\n');
  ok(/Seek the dark ones/.test(all) && /Melzar the Mad was locked/.test(all) && !/I found the chest/.test(all), "chapter 2's road B is remembered");
  ok(/Recruits get a Kinshra cloak, you said/.test(all), 'the bluff is remembered when the cloak is taken');
  ok(/I came in over the balcony/.test(all), 'the balcony line on the quiet road');
  ok(asked.length === 5 && asked[0].length === 2 && !/Sulla/.test(asked[0].join()) && /Sulla/.test(asked[1].join()) && /who took it/.test(asked[3].join()) && /That's all/.test(asked[4].join()),
    `the questions: Sulla once his name has come up, the reveal last: ${JSON.stringify(asked)}`);
  const got1 = await rack(['Kinshra Shortbow', 'Kinshra Sword', 'Kinshra Battlestaff'], 'A');
  ok(await play(first) === 'finale', 'on to the end of Act 1');
  await finale('A');
  await shot('chapter-done');
  await page.click('#board [data-do="stdone"]');
  const d1 = await page.evaluate(() => window.__arena.story.data());
  ok(d1.roads.ch3 && d1.roads.ch3.join() === 'A' && d1.path.ch3 === 'A', 'road A recorded');
  ok(Object.keys(d1.claimed).filter((k) => k.startsWith('ch3:')).length === 1 && d1.claimed['ch3:bow'] === got1, `one card from the rack, keyed by the card (${JSON.stringify(d1.claimed)})`);
  ok(await page.evaluate((c) => window.__arena.profile.coll[c], got1) === 1, `${got1} in the collection`);
  const hub2 = await text();
  ok(/Lord Daquarius/.test(hub2) && (await page.$$('#board [data-do="strematch"][data-v="daquarius"]')).length === 1, 'one rematch button for the Lord');
  await shot('hub-done');

  // ----- Replay Chapter 3: road B; the rack has two left -----
  said.length = 0; asked.length = 0;
  await page.click('#board [data-do="streplay"][data-v="ch3"]');
  ok(await play(first) === 'stopped', 'replay: the gate guard');
  await duel('bknight-b');
  ok(await play(loud) === 'map', 'replay: road B');
  for (const [opp, loc] of [['pair'], ['smith'], ['daquarius', 'The Kinshra Hall']]) {
    await page.click('#board [data-do="stdot"]');
    ok(await play(first) === 'stopped', `on to ${opp}`);
    if (opp === 'pair') await shot('corridor');
    if (opp === 'smith') await shot('forge');
    await duel(opp === 'daquarius' ? 'daquarius-b' : opp, { loc });
    const sc = await play(first);
    if (opp === 'daquarius') { ok(sc === 'rack', 'road B: the rack'); break; }
    ok(sc === 'map', `after ${opp}, the map`);
  }
  ok(!said.join('\n').includes('I came in over the balcony'), 'no balcony line on the loud road');
  const got2 = await rack(['Kinshra Sword', 'Kinshra Battlestaff'], 'B');
  ok(await play(first) === 'finale', 'replay: the end of Act 1');
  await finale('B');
  await page.click('#board [data-do="stdone"]');
  const d2 = await page.evaluate(() => window.__arena.story.data());
  ok(d2.roads.ch3.sort().join() === 'A,B' && d2.path.ch3 === 'A' && d2.main.road === 'A', 'both roads done; the replay left the main story alone');
  ok(d2.claimed['ch3:sword'] === got2 && await page.evaluate((c) => window.__arena.profile.coll[c], got2) === 1, `${got2} in the collection`);

  // ----- a third time, with the rack empty (the staff given on another device): the rack is passed by -----
  await page.evaluate(() => {
    const p = JSON.parse(localStorage.getItem('rstcg-profile-v1'));
    const s = window.__arena.story.data();
    s.claimed['ch3:staff'] = 'Kinshra Battlestaff';
    const i = window.RSTCGStory.CHAPTERS.find((c) => c.id === 'ch3').nodes.hall.findIndex((st) => st.rack) - 1;
    s.replay = { ch: 'ch3', node: 'hall', i, r: null, dots: { gate: 'done', hall: 'open' }, at: 'hall', tone: {}, road: 'B', done: false, prev: { ch1: 'A', ch2: 'B' } };
    void p;
  });
  await A('streplay', 'ch3');
  ok(await play(first) === 'finale', 'an empty rack is passed by');
  ok(/Bring him to justice/.test(said.join('\n')), 'straight on to "Bring him to justice"');

  // ----- the rest -----
  const tour = await page.evaluate(() => window.__arena.tour.places);
  ok(!tour.includes('The Kinshra Hall') && tour.includes('Lumbridge'), `the Kinshra Hall stays out of the menu's tour (${tour.join(', ')})`);
  const rules = await page.evaluate(() => {
    const deck = (extra) => Object.assign({ 'Bronze Bar': 56 }, extra);
    const r = (x) => window.__arena.deckRules(deck(x), false).known;
    return { sword: r({ 'Kinshra Sword': 1, Shrimp: 3 }), bow: r({ 'Kinshra Shortbow': 1, Shrimp: 3 }), staff: r({ 'Kinshra Battlestaff': 1, Shrimp: 3 }),
      shield: r({ 'Black Knight Shield': 1, Shrimp: 3 }), bar: r({ 'Black Bar': 4 }), wrath: r({ 'Unholy Wrath': 1, Shrimp: 3 }) };
  });
  ok(rules.sword && rules.bow && rules.staff, `the rewards are legal (${JSON.stringify(rules)})`);
  ok(!rules.shield && !rules.bar && !rules.wrath, `the Lord's own cards are refused (${JSON.stringify(rules)})`);
  ok(!/\bBot\b/.test(said.join('\n')), 'no "Bot" in any line');
  ok(!errors.length, 'no page errors: ' + errors.join(' | '));
  await b.close(); site.close();
  console.log(`${checks} checks · ${failures ? failures + ' FAILED' : 'all passed'}`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
