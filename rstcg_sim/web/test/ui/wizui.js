// Deck Wizard: pick cards, build, open in editor, save. Args: [width height].
// Run from anywhere: node web/test/ui/wizui.js   (screenshots go to web/test/ui/shots/wizui/, which is not part of the test)
const { chromium } = require('playwright');
const path = require('path');
const F = 'file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html');
const OUT = path.join(__dirname, 'shots', 'wizui'); require('fs').mkdirSync(OUT, { recursive: true });
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: +(process.argv[2]||1600), height: +(process.argv[3]||900) } });
  const errs = []; p.on('pageerror', (e) => errs.push(e.message)); p.on('console', (m) => m.type() === 'error' && errs.push(m.text()));
  await p.goto(F); await p.waitForTimeout(800);
  await p.click('[data-do="tutskip"]').catch(() => {});
  // go to decks
  await p.evaluate(() => { const a = window.__arena; });
  const decksBtn = await p.$('[data-do="decks"]'); if (decksBtn) await decksBtn.click(); await p.waitForTimeout(700);
  await p.click('[data-do="wizard"]'); await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + '/1pick.png' });
  const tiles = await p.$$('[data-do="wizpick"]');
  console.log('tiles', tiles.length);
  await tiles[0].click(); await p.waitForTimeout(300);
  await p.fill('#wizq', 'shortbow'); await p.waitForTimeout(400);
  const t2 = await p.$$('.cc[data-do="wizpick"]'); console.log('after search', t2.length);
  if (t2.length) await t2[0].click(); await p.waitForTimeout(300);
  await p.screenshot({ path: OUT + '/2picked.png' });
  const t0 = Date.now();
  await p.click('[data-do="wizbuild"]'); await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + '/3building.png' });
  await p.waitForSelector('[data-do="wizsave"]', { timeout: 60000 });
  console.log('build ms', Date.now() - t0);
  await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + '/4result.png' });
  const info = await p.evaluate(() => { const t = document.body.innerText; return t.match(/Won \d+ of \d+[^\n]*/)?.[0]; });
  console.log(info);
  await p.click('[data-do="wizedit"]'); await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + '/5editor.png' });
  const edtxt = await p.evaluate(() => document.body.innerText.match(/\d+\s*\/\s*60/)?.[0]);
  console.log('editor count', edtxt);
  // back to decks, then wizard again, save path
  await p.click('[data-do="edback"]').catch(()=>{}); await p.waitForTimeout(700);
  await p.screenshot({ path: OUT + '/5b-leave.png' }); await p.click('[data-do="edback"]'); await p.waitForTimeout(700);
  if (await p.$('[data-do="wizard"]') === null) { const btns = await p.$$eval('[data-do]', (a) => a.map((x) => x.dataset.do)); console.log('btns', btns.join(',')); }
  await p.click('[data-do="wizard"]'); await p.waitForTimeout(500);
  await (await p.$$('[data-do="wizpick"]'))[3].click();
  await p.click('[data-do="wizbuild"]');
  await p.waitForSelector('[data-do="wizsave"]', { timeout: 60000 });
  const before = await p.evaluate(() => JSON.parse(localStorage.getItem(Object.keys(localStorage).find((k) => /prof/i.test(k)) || 'null'))?.decks?.length);
  await p.click('[data-do="wizsave"]'); await p.waitForTimeout(700);
  const after = await p.evaluate(() => JSON.parse(localStorage.getItem(Object.keys(localStorage).find((k) => /prof/i.test(k)) || 'null'))?.decks?.length);
  console.log('decks', before, '->', after);
  await p.screenshot({ path: OUT + '/6decks.png' });
  console.log(errs.length ? 'ERRORS ' + errs.join(' | ') : 'no page errors');
  await b.close();
})();
