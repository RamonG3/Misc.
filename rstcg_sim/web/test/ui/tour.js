// Screenshots of every screen at one window size: node web/test/ui/tour.js [width=892] [height=412] [tag]
// Not a pass/fail test: it is for looking at a layout. Shots go to web/test/ui/shots/tour_<tag>/.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const W = +(process.argv[2] || 892), H = +(process.argv[3] || 412), tag = process.argv[4] || `${W}x${H}`;
const SHOTS = path.join(__dirname, 'shots', 'tour_' + tag);
fs.mkdirSync(SHOTS, { recursive: true });
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 2, hasTouch: W < 1200, isMobile: W < 1200 });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));
  await page.addInitScript(() => { if (!sessionStorage.getItem('init')) { localStorage.clear(); sessionStorage.setItem('init', '1'); } localStorage.setItem('rstcg-speed', '"fast"'); localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' })); });
  await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(300);
  await page.screenshot({ path: SHOTS + '/00_welcome.png' });
  await page.evaluate(() => window.__arena.act('tutskip'));
  const A = (what, v) => page.evaluate(([w, x]) => window.__arena.act(w, x), [what, v]);
  const shot = async (n, wait = 350) => { await page.waitForTimeout(wait); await page.screenshot({ path: `${SHOTS}/${n}.png` }); };
  await page.evaluate(() => { window.__arena.profile.gp = 1000; });
  await A('menu'); await shot('01_menu');
  await A('settings'); await shot('02_settings'); await A('closemodal');
  await A('how'); await shot('03_how'); await A('closemodal');
  await A('play'); await shot('04_pick');
  await A('opp'); await shot('05_pick_opp'); await A('opp');
  await A('decks'); await shot('06_decks');
  await A('edit'); await shot('07_editor');
  await A('edback'); await A('wizard'); await shot('08_wizard');
  const firstPick = await page.evaluate(() => { const el = document.querySelector('[data-do="wizpick"]'); return el && el.dataset.v; });
  if (firstPick) { await A('wizpick', firstPick); await shot('09_wizard_pick'); }
  await A('wizback'); await A('menu'); await A('packs'); await shot('10_packs');
  await A('buypack'); await shot('11_open', 1500);
  await A('revealall'); await shot('12_open_all', 2500);
  await A('packsum'); await shot('13_summary', 800);
  await A('menu'); await A('account');
  await page.evaluate(() => { const a = window.__arena; a.ui.acct.ways = { google: true, email: true }; a.render(); });
  await shot('13b_account', 800);
  await A('menu'); await A('play'); await A('start');
  await page.waitForTimeout(500);
  if (await page.$('[data-do="skipintro"]')) await page.evaluate(() => window.__arena.act('skipintro'));
  await page.waitForFunction(() => { const u = window.__arena.ui; return u.pending && u.pending.type === 'choose'; }, null, { timeout: 60000 });
  await shot('14_duel', 600);
  console.log(errs.length ? errs : 'no page errors');
  await b.close();
})();
