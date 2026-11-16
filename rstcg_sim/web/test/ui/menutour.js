// The main menu's tour: left alone, the menu moves on to another place every so often.
// node web/test/ui/menutour.js      ends "all passed" (about a minute); screenshots in web/test/ui/shots/menutour/
//
// Checks that the painting, its motes and the place's name all change together, that every place comes up once
// a lap and never twice running, that the clock stops with a dialog open or on another screen, that a redraw
// in the middle of a fade leaves one clean picture, that buttons still work mid-fade, and no page errors.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'menutour');
fs.mkdirSync(SHOTS, { recursive: true });
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };

(async () => {
  const b = await chromium.launch();
  const errors = [];
  const open = async (motion, size = { width: 1920, height: 1080 }) => {
    const ctx = await b.newContext({ viewport: size, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    await page.addInitScript((motion) => {
      window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
      localStorage.clear();
      localStorage.setItem('rstcg-settings', JSON.stringify({ motion, music: 'off', sfx: 'off', tips: 'off' }));
    }, motion);
    await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
    await page.waitForFunction(() => window.__arena);
    await page.evaluate(() => window.__arena.act('tutskip'));
    await page.waitForTimeout(400);
    return page;
  };
  // what the menu is showing right now
  const look = (page) => page.evaluate(() => {
    const t = window.__arena.tour, AS = window.__arena.AS;
    const layers = [...document.querySelectorAll('#board #mscene .mlayer')];
    const last = layers[layers.length - 1];
    return { place: t.place, busy: t.busy, shown: t.shown, layers: layers.length, layerPlace: last && last.dataset.place,
      src: last && last.querySelector('img').getAttribute('src') === AS.backdrop[t.place],
      motes: document.querySelectorAll('#board #mmotes .motes').length, dots: document.querySelectorAll('#board #mmotes .mote').length,
      label: (document.querySelector('#board #mplace') || {}).textContent, screen: window.__arena.ui.screen, modal: window.__arena.ui.modal };
  });
  const settled = async (page) => { await page.waitForFunction(() => !window.__arena.tour.busy, null, { timeout: 15000 }); await page.waitForTimeout(900); };
  const nextPlace = async (page, from, ms = 20000) => {
    await page.waitForFunction((from) => window.__arena.tour.place !== from, from, { timeout: ms });
    return page.evaluate(() => window.__arena.tour.place);
  };

  // ---------- full motion ----------
  const page = await open('full');
  const places = await page.evaluate(() => window.__arena.tour.places);
  ok(places.length >= 5 && places.includes('Lumbridge'), `the places to tour: ${places.join(', ')}`);
  ok(await page.evaluate(() => window.__arena.tour.every) === 20000, 'a place stays for 20 seconds');
  let s = await look(page);
  ok(s.layers === 1 && s.layerPlace === s.place && s.src && s.label === s.place.toUpperCase() && s.motes === 1 && s.dots > 10, `the menu opens on one place: ${JSON.stringify(s)}`);

  // nothing moves on early
  await page.waitForTimeout(3000);
  ok((await look(page)).place === s.place, 'the place changed before its time was up');

  // a lap, with the clock turned up
  await page.evaluate(() => { window.__arena.tour.every = 2000; });
  const seen = [s.place];
  let midFade = null;
  for (let i = 0; i < places.length * 2; i++) {
    const p = await nextPlace(page, seen[seen.length - 1]);
    if (!midFade) {
      await page.waitForTimeout(900);
      midFade = await look(page);
      await page.screenshot({ path: path.join(SHOTS, '00_mid_fade.png') });
    }
    seen.push(p);
    await settled(page);
    const now = await look(page);
    ok(now.layers === 1 && now.layerPlace === p && now.src && now.label === p.toUpperCase() && now.motes === 1, `after the fade to ${p}, one clean picture: ${JSON.stringify(now)}`);
  }
  ok(midFade && midFade.layers === 2 && midFade.motes === 2 && midFade.busy, `mid-fade the new place lies over the old: ${JSON.stringify(midFade)}`);
  ok(seen.every((p, i) => !i || p !== seen[i - 1]), `never the same place twice running: ${seen.join(' > ')}`);
  const lap1 = seen.slice(0, places.length), lap2 = seen.slice(places.length, places.length * 2);
  ok(new Set(lap1).size === places.length && new Set(lap2).size === places.length, `every place once a lap: ${seen.join(' > ')}`);
  ok(lap1.join('|') === lap2.join('|'), `the laps go round in the same order: ${seen.join(' > ')}`);
  console.log('the tour went: ' + seen.join(' > '));

  // a dialog stops the clock; closing it starts it again
  await settled(page);
  let before = (await look(page)).place;
  await page.click('[data-do="settings"]');
  await page.waitForTimeout(6000);
  s = await look(page);
  ok(s.modal === 'settings' && s.place === before, `the tour moved on behind the Settings dialog: ${JSON.stringify(s)}`);
  await page.evaluate(() => window.__arena.act('closemodal'));
  ok(await nextPlace(page, before) !== before, 'the tour carries on once the dialog is closed');
  await settled(page);

  // another screen stops it too, and the menu comes back to the place it left
  await page.evaluate(() => { window.__arena.tour.every = 4000; });
  before = (await look(page)).place;
  await page.waitForTimeout(2200);                 // (half its time gone)
  await page.evaluate(() => window.__arena.act('decks'));
  await page.waitForTimeout(6000);
  ok(await page.evaluate(() => window.__arena.tour.place) === before && await page.evaluate(() => !document.querySelector('#board #mscene')), 'the tour ran on the Decks screen');
  await page.evaluate(() => window.__arena.act('menu'));
  await page.waitForTimeout(2800);                 // (more than the half that was left: its time started again)
  s = await look(page);
  ok(s.place === before && s.layers === 1 && s.label === before.toUpperCase() && !s.busy, `back on the menu, the place it left, for its full time: ${JSON.stringify(s)}`);
  ok(await nextPlace(page, before) !== before, 'and the tour carries on from there');
  await settled(page);

  // a redraw in the middle of a fade leaves one clean picture of the new place
  await page.evaluate(() => { window.__arena.tour.every = 600000; });
  before = (await look(page)).place;
  const target = places.find((p) => p !== before);
  ok(await page.evaluate((to) => window.__arena.tour.step(to), target) === true, 'asked for a place by name');
  await page.waitForFunction((to) => window.__arena.tour.place === to, target);
  await page.waitForTimeout(700);
  ok(await page.evaluate((to) => window.__arena.tour.step(to), before) === false, 'a second fade started on top of the first');
  await page.evaluate(() => window.__arena.render());
  s = await look(page);
  ok(s.layers === 1 && s.layerPlace === target && s.label === target.toUpperCase() && s.motes === 1, `a redraw mid-fade: ${JSON.stringify(s)}`);
  await settled(page);
  s = await look(page);
  ok(s.layers === 1 && s.layerPlace === target && s.motes === 1 && !s.busy, `and after the fade's tidy-up: ${JSON.stringify(s)}`);

  // buttons still work while a fade is running
  await page.evaluate((to) => window.__arena.tour.step(to), before);
  await page.waitForFunction((to) => window.__arena.tour.place === to, before);
  await page.waitForTimeout(500);
  await page.click('[data-do="how"]');
  s = await look(page);
  ok(s.modal === 'how', `How to Play opened mid-fade: ${JSON.stringify(s)}`);
  await page.evaluate(() => window.__arena.act('closemodal'));
  await settled(page);

  // a picture of every place, for looking at
  for (const p of places) {
    if ((await look(page)).place !== p) { await page.evaluate((to) => window.__arena.tour.step(to), p); await page.waitForFunction((to) => window.__arena.tour.place === to, p); }
    await settled(page);
    await page.waitForTimeout(700);
    await page.screenshot({ path: path.join(SHOTS, `place_${p.replace(/\W+/g, '_')}.png`) });
  }
  await page.context().close();

  // ---------- reduced motion, on a phone: the tour still goes round, as a plain fade ----------
  const calm = await open('reduced', { width: 892, height: 412 });
  await calm.evaluate(() => { window.__arena.tour.every = 2000; });
  s = await look(calm);
  const p2 = await nextPlace(calm, s.place);
  await settled(calm);
  const c2 = await look(calm);
  ok(p2 !== s.place && c2.layers === 1 && c2.label === p2.toUpperCase(), `reduced motion still tours: ${JSON.stringify(c2)}`);
  await calm.screenshot({ path: path.join(SHOTS, 'phone_reduced.png') });
  await calm.context().close();

  await b.close();
  console.log(errors.length ? `${errors.length} page errors\n  ${errors.join('\n  ')}` : 'no page errors');
  if (errors.length) failures += 1;
  console.log(failures ? `${failures} FAILED (of ${checks} checks)` : `all passed (${checks} checks)`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
