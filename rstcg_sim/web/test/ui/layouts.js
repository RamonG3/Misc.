// Every menu screen at several window shapes: node web/test/ui/layouts.js
// Phone held sideways, laptop, 16:9 and 16:10 monitors, a 4:3 tablet and a 21:9 ultrawide. On each, every
// button and card you can press (outside lists that scroll) must be fully on screen and not hidden behind
// something else, and there must be no page errors.
// Shots: web/test/ui/shots/layout_<screen>_<size>.png. Prints "no errors" when all is well.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });
const SIZES = [[892, 412, 'phone'], [800, 360, 'phone-browser'], [1366, 650, 'laptop'], [1920, 1080, '16x9'], [1440, 900, '16x10'], [1024, 768, '4x3'], [2560, 1080, '21x9']];
const fails = [];

(async () => {
  const b = await chromium.launch();
  for (const [W, H, tag] of SIZES) {
    const phone = W < 1000;
    const ctx = await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, isMobile: phone, hasTouch: phone });
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(e.message));
    await page.addInitScript(() => { if (!sessionStorage.getItem('init')) { localStorage.clear(); sessionStorage.setItem('init', '1'); } localStorage.setItem('rstcg-speed', '"fast"'); localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' })); });
    await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
    await page.waitForFunction(() => window.__arena);
    await page.waitForTimeout(300);
    const A = (w, v) => page.evaluate(([w, v]) => window.__arena.act(w, v), [w, v]);
    await A('tutskip');
    await page.evaluate(() => { window.__arena.profile.gp = 1000; });
    const look = async (name) => {
      await page.waitForTimeout(250);
      const bad = await page.evaluate(([W, H]) => {
        const out = [];
        const modal = document.querySelector('#board .over');             // with a dialog open, only it counts
        for (const el of (modal || document).querySelectorAll(modal ? '[data-do]:not(.over), input' : '#board [data-do], #board input')) {
          if (el.closest('.scrolly') || el.closest('#trans')) continue;
          const r = el.getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          const cs = getComputedStyle(el);
          if (cs.visibility === 'hidden' || +cs.opacity < 0.05) continue;
          const what = `${el.dataset.do || el.id}${el.dataset.v ? '=' + el.dataset.v : ''}`;
          if (r.left < -1 || r.top < -1 || r.right > W + 1 || r.bottom > H + 1) { out.push(`${what} off screen`); continue; }
          const x = Math.min(W - 1, Math.max(0, r.left + r.width / 2)), y = Math.min(H - 1, Math.max(0, r.top + r.height / 2));
          const top = document.elementFromPoint(x, y);
          if (top && !el.contains(top) && !top.contains(el) && !top.closest('[data-stop]') && !el.closest('.over')) out.push(`${what} covered by ${top.className || top.tagName}`);
        }
        return out;
      }, [W, H]);
      await page.screenshot({ path: path.join(SHOTS, `layout_${name}_${tag}.png`) });
      for (const x of bad) fails.push(`${tag} ${name}: ${x}`);
    };
    await A('menu'); await look('menu');
    await A('settings'); await look('settings'); await A('closemodal');
    await A('play'); await page.waitForTimeout(400); await look('pick');
    await A('opp'); await look('pick-opponent'); await A('opp');
    await A('decks'); await page.waitForTimeout(400); await look('decks');
    await A('edit'); await page.waitForTimeout(400); await look('editor');
    await A('edfilt'); await look('editor-filters'); await A('edfilt');
    await page.evaluate(() => { window.__arena.ui.ed.dirty = false; });
    await A('menu'); await A('decks'); await A('wizard'); await page.waitForTimeout(400); await look('wizard');
    await A('menu'); await A('packs'); await page.waitForTimeout(400); await look('packs');
    await A('buypack'); await page.waitForTimeout(600); await A('revealall'); await page.waitForTimeout(1200); await look('pack-open');
    await A('packsum'); await page.waitForTimeout(400); await look('pack-summary');
    console.log(`${tag} ${W}x${H}: ${errs.length ? 'page ERRORS ' + errs.join(' | ') : 'ok'}`);
    for (const e of errs) fails.push(`${tag}: ${e}`);
    await ctx.close();
  }
  await b.close();
  if (fails.length) console.log('problems:\n  ' + fails.join('\n  '));
  console.log(fails.length ? `${fails.length} problems` : 'no errors');
  process.exit(fails.length ? 1 : 0);
})();
