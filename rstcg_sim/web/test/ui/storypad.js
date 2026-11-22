// Story Mode with a controller (Ramon, 2026-11-22): the ring starts on the dialogue box (or its options, the map's
// dot, the duel), never on the back arrow, so pressing only A plays the story from New Game to the first duel.
// node web/test/ui/storypad.js   ends "all passed"
const path = require('path');
const { chromium } = require('playwright');
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));
  await page.addInitScript(() => {
    localStorage.clear();
    localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'full', music: 'off', sfx: 'off', tips: 'off' }));
    window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
    window.__pad = { id: 'Xbox Wireless Controller (STANDARD GAMEPAD)', index: 0, connected: true, mapping: 'standard', axes: [0, 0, 0, 0], buttons: Array.from({ length: 17 }, () => ({ pressed: false, value: 0 })), timestamp: 0 };
    navigator.getGamepads = () => [window.__pad];
  });
  await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(300);
  const B = { A: 0, DOWN: 13 };
  if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
  const press = async (n) => { await page.evaluate((i) => { window.__pad.buttons[i] = { pressed: true, value: 1 }; }, B[n]); await page.waitForTimeout(140); await page.evaluate((i) => { window.__pad.buttons[i] = { pressed: false, value: 0 }; }, B[n]); await page.waitForTimeout(160); };
  const scr = () => page.evaluate(() => window.__arena.ui.screen);
  const focus = () => page.evaluate(() => { const f = document.querySelector('.gp-focus'); return f ? f.dataset.do || '' : null; });
  // the controller only: wake it on the menu, walk to Story, then New Game, then nothing but A
  await press('DOWN');
  for (let k = 0; k < 10 && (await focus()) !== 'story'; k++) await press('DOWN');
  await press('A'); await page.waitForTimeout(1500);
  for (let k = 0; k < 6 && (await focus()) !== 'stnew'; k++) await press('DOWN');
  await press('A'); await page.waitForTimeout(2500);
  const seen = new Set();
  let left = null, presses = 0;
  for (; presses < 200; presses++) {
    const s = await scr();
    if (s === 'pick') break;
    if (s !== 'scene' && s !== 'map') { left = s; break; }
    const f = await focus();
    if (f) seen.add(`${s}:${f}`);
    if (f === 'stmap' || f === 'story') { left = `the ring on the back arrow (${s})`; break; }
    await press('A');
    await page.waitForTimeout(s === 'map' ? 1200 : 900);
  }
  ok(!left, `pressing A stayed in the story (left for: ${left})`);
  ok(await scr() === 'pick', `pressing only A reached the first duel's deck picker (${await scr()}, ${presses} presses)`);
  ok([...seen].some((k) => k.endsWith(':stnext')) && [...seen].some((k) => k.endsWith(':stpick')) && [...seen].some((k) => k === 'map:stdot'),
    `the ring sat on lines, options and the map's dot: ${[...seen].join(', ')}`);
  ok(!errs.length, 'no page errors: ' + errs.join(' | '));
  await b.close();
  console.log(`${checks} checks · ${failures ? failures + ' FAILED' : 'all passed'}`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
