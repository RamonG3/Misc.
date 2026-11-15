// Fingers on a phone-sized screen (892x412, touch events through Chrome's DevTools protocol):
//   node web/test/ui/touch.js
// Checks: html.touch is set; the rotate screen shows in portrait; in the deck editor a tap adds a card,
// press-and-hold zooms it without adding it, holding a deck row doesn't remove it, a finger drag adds one
// and the − button removes one; in a duel, holding a hand card zooms it without opening its options, and a
// finger drag plays a card. Shots: web/test/ui/shots/touch_*.png. Prints "no errors" when all is well.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });
const fails = [];
const check = (ok, what) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); if (!ok) fails.push(what); };

(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 892, height: 412 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));
  await page.addInitScript(() => { if (!sessionStorage.getItem('init')) { localStorage.clear(); sessionStorage.setItem('init', '1'); } localStorage.setItem('rstcg-speed', '"fast"'); localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' })); });
  await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(400);
  const A = (w, v) => page.evaluate(([w, v]) => window.__arena.act(w, v), [w, v]);
  await A('tutskip');
  const cdp = await ctx.newCDPSession(page);
  const T = (type, x, y) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
  const centre = async (sel) => { const r = await page.locator(sel).first().boundingBox(); return r && { x: r.x + r.width / 2, y: r.y + r.height / 2 }; };
  const tap = async (p) => { await T('touchStart', p.x, p.y); await page.waitForTimeout(60); await T('touchEnd'); await page.waitForTimeout(250); };
  const hold = async (p, ms = 700) => { await T('touchStart', p.x, p.y); await page.waitForTimeout(ms); };
  const swipe = async (a, z, steps = 12) => {
    await T('touchStart', a.x, a.y); await page.waitForTimeout(40);
    for (let k = 1; k <= steps; k++) { await T('touchMove', a.x + (z.x - a.x) * k / steps, a.y + (z.y - a.y) * k / steps); await page.waitForTimeout(16); }
    await T('touchEnd'); await page.waitForTimeout(400);
  };
  const zoomOn = () => page.evaluate(() => document.querySelector('#zoom').classList.contains('big'));

  check(await page.evaluate(() => document.documentElement.classList.contains('touch')), 'the page knows it is a touch screen');
  await page.setViewportSize({ width: 412, height: 892 }); await page.waitForTimeout(300);
  check(await page.evaluate(() => getComputedStyle(document.querySelector('#rotate')).display === 'flex'), 'portrait shows "Turn your phone sideways"');
  await page.setViewportSize({ width: 892, height: 412 }); await page.waitForTimeout(300);
  check(await page.evaluate(() => getComputedStyle(document.querySelector('#rotate')).display === 'none'), 'landscape hides it');

  // ---- deck editor ----
  await A('menu'); await A('decks'); await A('edit'); await page.waitForTimeout(500);
  const total = () => page.evaluate(() => Object.values(window.__arena.ui.ed.cards).reduce((a, b) => a + b, 0));
  const first = '.ccg .cc:not(.cc-no)';
  await tap(await centre(first));
  check(await total() === 1, 'a tap on a card adds it to the deck');
  await hold(await centre(first));
  check(await zoomOn(), 'holding a card shows it full size');
  await page.screenshot({ path: path.join(SHOTS, 'touch_editor_hold.png') });
  await T('touchEnd'); await page.waitForTimeout(300);
  check(!(await zoomOn()) && await total() === 1, 'letting go hides it and adds nothing');
  await hold(await centre('.dl'));
  await T('touchEnd'); await page.waitForTimeout(300);
  check(await total() === 1, 'holding a deck row does not remove the card');
  const deckBox = await centre('.dropdeck');
  await swipe(await centre(`${first} >> nth=2`), deckBox);
  check(await total() === 2, 'a finger drag into the deck adds a card');
  const minus = await page.locator('.dl [data-do="edrem"]').first().boundingBox();
  check(minus && minus.width >= 17, `the − button on a deck row is big enough to tap (${minus ? minus.width.toFixed(0) : 0} px)`);
  await page.waitForTimeout(700);
  if (process.env.DBG) { page.on('console', (m) => console.log('  >', m.text())); await page.evaluate(() => { for (const t of ['pointerdown', 'pointerup', 'click', 'touchstart']) document.addEventListener(t, (e) => console.log(t, e.target.tagName, e.target.dataset && e.target.dataset.do, e.defaultPrevented), true); }); }
  await tap({ x: minus.x + minus.width / 2, y: minus.y + minus.height / 2 });
  if (process.env.DBG) console.log(await page.evaluate(([x, y]) => { const e = document.elementFromPoint(x, y); return e && e.outerHTML.slice(0, 120); }, [minus.x + minus.width / 2, minus.y + minus.height / 2]), JSON.stringify(await page.evaluate(() => window.__arena.ui.ed.cards)));
  check(await total() === 1, 'the − button removes a card');
  const plus = await centre('.dl [data-do="edadd"]');
  for (let k = 0; k < 3; k++) await tap(plus);
  check(await total() === 4, `quick taps on + add one each (${await total()} of 4)`);
  await page.screenshot({ path: path.join(SHOTS, 'touch_editor.png') });

  // ---- a duel ----
  await page.evaluate(() => { window.__arena.ui.ed.dirty = false; });
  await A('menu'); await A('play'); await A('start'); await page.waitForTimeout(400);
  await page.evaluate(() => { if (document.querySelector('[data-do="skipintro"]')) window.__arena.act('skipintro'); });
  await page.waitForFunction(() => { const u = window.__arena.ui; return u.pending && u.pending.type === 'choose' && document.querySelector('.hc.ok'); }, null, { timeout: 60000 });
  await page.waitForTimeout(800);
  const hc = await centre('.hc.ok');
  await hold(hc);
  check(await zoomOn(), 'holding a hand card shows it full size');
  await page.screenshot({ path: path.join(SHOTS, 'touch_duel_hold.png') });
  await T('touchEnd'); await page.waitForTimeout(400);
  check(await page.evaluate(() => window.__arena.ui.selected === null && !document.querySelector('.pop')), 'letting go does not open its options');
  await page.evaluate(() => { window.__marked = [...document.querySelectorAll('#board img, #board .plane, #board .scene')]; window.__marked.forEach((n) => { n.__mark = 1; }); });
  const before = await page.evaluate(() => { const u = window.__arena.ui; return u.g.players[u.you].hand.length; });
  const mat = await centre('[data-zone="mymat"]');
  await swipe(hc, { x: mat.x, y: mat.y }, 16);
  await page.waitForTimeout(1500);
  if (await page.$('.pop .opt:not(.cancel)')) { const o = await centre('.pop .opt:not(.cancel)'); await tap(o); await page.waitForTimeout(1200); }
  const after = await page.evaluate(() => { const u = window.__arena.ui; return u.g.players[u.you].hand.length; });
  check(after < before, `a finger drag plays a card (hand ${before} -> ${after})`);
  // phones blink if the board is rebuilt from scratch: the table and most pictures must be the same elements as before
  const kept = await page.evaluate(() => { const live = window.__marked.filter((n) => n.isConnected); return { plane: !!(document.querySelector('#board .plane') || {}).__mark, n: window.__marked.length, live: live.length }; });
  check(kept.plane && kept.live >= kept.n * 0.6, `the table is redrawn in place, not rebuilt (${kept.live} of ${kept.n} pictures kept)`);
  const same = await page.evaluate(() => window.__arena.morphCheck());
  check(same === true || same === null, 'the redrawn board matches one drawn from scratch');
  const broken = await page.evaluate(() => { const el = document.querySelector('#board .plane'); el.setAttribute('data-test', '1'); const r = window.__arena.morphCheck(); el.removeAttribute('data-test'); return r; });
  check(Array.isArray(broken), 'and that check does notice a difference');
  await page.screenshot({ path: path.join(SHOTS, 'touch_duel.png') });

  console.log(errs.length ? 'page ERRORS:\n  ' + errs.join('\n  ') : fails.length ? `${fails.length} checks failed` : 'no errors');
  await b.close();
  process.exit(errs.length || fails.length ? 1 : 0);
})();
