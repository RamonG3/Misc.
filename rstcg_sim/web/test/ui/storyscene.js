// Story Mode, 2026-11-20: changes of place, the stranger in Lumbridge Swamp, the places' recorded music, and boss
// rematches. Played with full motion and the music on (the other story test plays with motion reduced).
// - A change of place clears the box; the new place fades in slowly with its name; clicks wait; then the next line.
// - The stranger meets you in the swamp, in Ramon's painting, with his own chathead; the swamp's music stops and
//   Ambient Room follows. His paintings are never menu backdrops.
// - Lumbridge plays Bazaar, the swamp Swamp Land (when this page has them: arena_music.js).
// - A boss you've beaten on a finished road can be fought again from the Story screen: no story moves, no reward.
// node web/test/ui/storyscene.js   ends "all passed"; screenshots in web/test/ui/shots/storyscene/
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'storyscene');
fs.mkdirSync(SHOTS, { recursive: true });
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };

(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const page = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => {
    window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
    localStorage.clear();
    localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'full', music: 'med', sfx: 'off', tips: 'off' }));
  });
  await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
  await page.waitForFunction(() => window.__arena);
  await page.evaluate(() => window.__arena.act('tutskip'));
  await page.mouse.click(1500, 900); await page.waitForTimeout(300);
  const A = (what, v) => page.evaluate(([w, x]) => { const r = window.__arena.act(w, x); return r; }, [what, v]);
  const ui = (f) => page.evaluate(f);
  const playing = () => page.evaluate(() => RSAudio.playing);
  const tracks = await page.evaluate(() => RSAudio.tracks);
  const shot = (n) => page.screenshot({ path: path.join(SHOTS, `${n}.png`) });
  const step = () => ui(() => { const r = window.__arena.profile.story.main; return `${r.node}:${r.i}:${r.r ? r.r.j : '-'}`; });
  const hasBox = () => ui(() => !!document.querySelector('#board .stbox'));
  // click on until there's a box (waiting out any change of place)
  async function next() {
    await page.waitForFunction(() => document.querySelector('#board [data-do="stnext"], #board [data-do="stpick"], #board [data-do="stduel"]'), null, { timeout: 8000 });
    const pick = await page.$('#board [data-do="stpick"]');
    await page.click(pick ? '#board [data-do="stpick"][data-v="0"]' : '#board [data-do="stnext"]');
    await page.waitForTimeout(80);
  }
  async function untilText(re, max = 60) {
    for (let n = 0; n < max; n++) {
      const t = await ui(() => (document.querySelector('#board .stbox') || {}).innerText || '');
      if (re.test(t)) return true;
      await next();
    }
    return false;
  }

  ok(!(await ui(() => Object.keys(window.ARENA_ASSETS.backdrop).some((k) => /stranger/i.test(k)))), 'the stranger is never a menu backdrop');
  ok(await ui(() => !!(window.ARENA_ASSETS.scene['Lumbridge Swamp stranger'] && window.ARENA_ASSETS.face.Stranger)), 'his painting and chathead are in the page');

  // Lumbridge, then the map, then the swamp
  await A('story'); await page.waitForTimeout(400);
  await A('stnew'); await page.waitForTimeout(1600);
  if (tracks.includes('Bazaar')) ok(await playing() === 'Bazaar', 'Lumbridge plays Bazaar: ' + await playing());
  ok(await untilText(/Mind the bog/), 'Hans sends you south');
  await next(); await page.waitForTimeout(1200);
  ok(await ui(() => window.__arena.ui.screen) === 'map', 'the map');
  await A('stdot', 'swamp'); await page.waitForTimeout(1500);
  if (tracks.includes('Swamp Land')) ok(await playing() === 'Swamp Land', 'the swamp plays Swamp Land: ' + await playing());
  // the bandit: won at once
  await page.waitForFunction(() => document.querySelector('#board [data-do="stduel"]'), null, { timeout: 8000 }).catch(() => {});
  for (let n = 0; n < 10 && !(await page.$('#board [data-do="stduel"]')); n++) await next();
  await A('stduel'); await A('ststart');
  await page.waitForFunction(() => window.__arena.ui.g && !window.__arena.ui.intro, null, { timeout: 20000 });
  await page.evaluate(() => window.__arena.story.endDuel(true));
  await page.waitForFunction(() => window.__arena.ui.screen === 'result');
  await A('stwon'); await page.waitForTimeout(1600);
  ok(/regret this/.test(await ui(() => document.querySelector('#board').innerText)), 'the bandit runs off');

  // the change of place: no box, the painting fading in, clicks wait
  await page.click('#board [data-do="stnext"]');
  await page.waitForTimeout(500);
  const at = await step();
  ok(!(await hasBox()), 'the box clears while the place changes');
  ok(await ui(() => window.__arena.ui.sc.scene.art) === 'Lumbridge Swamp stranger', 'the stranger in the swamp');
  ok(await ui(() => document.querySelectorAll('#board .stbg').length) === 2, 'the old place under the new one, fading');
  ok(!(await ui(() => document.querySelector('#board .sttitle'))), 'the same place: no name shown');
  await shot('1-stranger-arriving');
  await page.keyboard.press('Space'); await A('stnext');
  ok(await step() === at, 'clicks wait for the place to come in');
  await page.waitForTimeout(1700);
  if (tracks.includes('Ambient Room')) ok(await playing() === 'Ambient Room', 'the music stops; Ambient Room: ' + await playing());
  await page.waitForTimeout(1300);
  ok(await hasBox(), 'then the next line');
  ok(/standing in your way/.test(await ui(() => document.querySelector('#board .stbox').innerText)), 'still in the swamp, before Lumbridge');
  ok(await ui(() => document.querySelector('#board .stbg img').src) === await ui(() => window.ARENA_ASSETS.scene['Lumbridge Swamp stranger']), 'his painting behind it');
  ok(await untilText(/\.\.\.\.\.\./), 'he says nothing');
  ok(await ui(() => (document.querySelector('#board .sthead img') || {}).src) === await ui(() => window.ARENA_ASSETS.face.Stranger), 'with his own chathead');
  await page.waitForTimeout(400);
  await shot('2-stranger-speaks');
  ok(await untilText(/cut from a single ruby/), 'he draws the sword');
  await page.waitForTimeout(2000);
  ok(await ui(() => window.__arena.ui.sc.scene.art) === 'Lumbridge Swamp stranger close', 'the world blurs round him');
  ok(await hasBox(), 'the box stays for that');
  await shot('3-the-sword');
  // the flash: he's gone before anything can be seen again
  ok(await untilText(/The sword rises/), 'the sword rises');
  await page.evaluate(() => { window.__fx = []; const was = RSAudio.sfx; RSAudio.sfx = (n, o) => { window.__fx.push(n); return was(n, o); }; });
  await page.click('#board [data-do="stnext"]');
  ok((await page.evaluate(() => window.__fx)).includes('flare'), 'the flare sounds with the flash');
  const look = () => ui(() => { const bgs = [...document.querySelectorAll('#board .stbg')], f = document.querySelector('#board .stflash');
    return { n: bgs.length, newOp: +getComputedStyle(bgs[bgs.length - 1]).opacity, flash: f ? +getComputedStyle(f).opacity : 0, box: !!document.querySelector('#board .stbox'), art: window.__arena.ui.sc.scene.art }; });
  await page.waitForTimeout(250);
  let l = await look();
  ok(l.art === 'Lumbridge Swamp stranger gone' && l.n === 2 && l.newOp === 0 && l.flash > 0.5 && !l.box, 'as the flash comes up, he is still there, under it: ' + JSON.stringify(l));
  await page.waitForTimeout(450);
  l = await look();
  ok(l.newOp === 1 && l.flash > 0.95, 'he is gone while the flash covers everything: ' + JSON.stringify(l));
  await page.waitForTimeout(700);
  await shot('3b-coming-back-into-focus');
  l = await look();
  ok(!l.box, 'no box while your sight comes back');
  await page.waitForTimeout(2000);
  ok(/sight comes back/.test(await ui(() => (document.querySelector('#board .stbox') || {}).innerText || '')), 'then: the path is empty');
  ok(await ui(() => document.querySelector('#board .stbg img').src) === await ui(() => window.ARENA_ASSETS.scene['Lumbridge Swamp stranger gone']), 'the same swamp, without him');
  await shot('3c-he-is-gone');
  ok(await untilText(/What was that/), 'the flash, and he is gone');
  if (tracks.includes('Ambient Room')) ok(await playing() === 'Ambient Room', 'Ambient Room until Lumbridge');
  // into Lumbridge: a new place, so its name
  await next(); await page.waitForTimeout(700);
  ok(!(await hasBox()), 'into Lumbridge: no box');
  ok(/Lumbridge/.test(await ui(() => (document.querySelector('#board .sttitle') || {}).innerText || '')), 'the place\'s name shows');
  await page.waitForTimeout(500);
  await shot('4-arriving-in-lumbridge');
  await page.waitForTimeout(2400);
  ok(await hasBox(), 'then Hans');
  if (tracks.includes('Bazaar')) ok(await playing() === 'Bazaar', 'Bazaar again in Lumbridge');

  // reloading in the middle of the stranger scene: his painting and his music come back
  await page.evaluate(() => { const r = window.__arena.profile.story.main; r.node = 'swamp'; r.i = 14; r.r = null; window.__arena.act('story'); window.__arena.act('stcont'); });
  await page.waitForTimeout(800);
  ok(await ui(() => window.__arena.ui.sc.scene.art) === 'Lumbridge Swamp stranger close', 'a save mid-scene comes back to the right painting');

  // boss rematches
  await A('stmap'); await A('menu'); await page.waitForTimeout(300); await A('story'); await page.waitForTimeout(600);
  ok(await ui(() => document.querySelectorAll('#board [data-do="strematch"]:disabled').length) === 2, 'no rematches before a road is finished');
  await page.evaluate(() => { window.__arena.profile.story.roads = { ch1: ['A'] }; window.__arena.render(); });
  await page.waitForTimeout(300);
  ok(await ui(() => !document.querySelector('#board [data-do="strematch"][data-v="delrith"]').disabled), 'Delrith, once the road to Draynor is done');
  ok(await ui(() => document.querySelector('#board [data-do="strematch"][data-v="draynor"]').disabled), 'not the Count yet');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(SHOTS, '5-rematches.png'), clip: { x: 100, y: 140, width: 600, height: 680 } });
  const before = await ui(() => JSON.stringify(window.__arena.profile.story));
  await A('strematch', 'draynor');
  ok(await ui(() => window.__arena.ui.screen) === 'story', "a locked rematch doesn't open");
  await A('strematch', 'delrith');
  ok(await ui(() => window.__arena.ui.screen) === 'pick', 'the deck picker');
  await A('stback');
  ok(await ui(() => window.__arena.ui.screen) === 'story', 'Back goes back to the Story screen');
  await A('strematch', 'delrith'); await A('ststart');
  await page.waitForFunction(() => window.__arena.ui.g && !window.__arena.ui.intro, null, { timeout: 20000 });
  const cfg = await ui(() => window.__arena.ui.config.story);
  ok(cfg.rematch && cfg.opp === 'delrith' && cfg.location === 'Draynor Manor', 'a rematch, with the cellar locked in: ' + JSON.stringify(cfg));
  ok(await ui(() => window.__arena.ui.g.location) === 'Draynor Manor', 'Draynor Manor in play');
  await page.evaluate(() => window.__arena.story.endDuel(true));
  await page.waitForFunction(() => window.__arena.ui.screen === 'result');
  ok(/Back to Story/.test(await ui(() => document.querySelector('#board').innerText)), 'Back to Story');
  await A('stwon'); await page.waitForTimeout(300);
  ok(await ui(() => window.__arena.ui.screen) === 'story', 'back on the Story screen');
  const after = await ui(() => JSON.stringify(window.__arena.profile.story));
  ok(before === after, 'the story and its rewards are untouched');

  ok(!errors.length, 'no page errors: ' + errors.join(' | '));
  console.log(`${checks} checks · ${failures ? failures + ' FAILED' : 'all passed'}${tracks.length ? '' : ' (this page has no recorded music)'}`);
  await b.close();
  process.exit(failures ? 1 : 0);
})();
