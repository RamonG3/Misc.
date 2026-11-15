// Champion's thinking time in the real page: node web/test/bot_speed.js [cpu-slowdown=1] [path/to/arena.html]
//
// Runs Settings > "Champion's thinking time" the way a player would, on a phone-shaped screen, and prints
// what it reports. The slowdown is Chrome's CPU throttling (DevTools' "4x slowdown" is 4): a rough stand-in
// for a phone. The real number comes from pressing Test on the phone itself.
// Screenshot: web/test/ui/shots/bench_settings.png. Exits 1 on a page error or if no result appears.
const { chromium } = require('playwright');
const fs = require('fs');
const http = require('http');
const path = require('path');

const slow = +(process.argv[2] || 1);
const built = process.argv[3] || path.join(__dirname, '..', 'dist', 'arena.html');
const out = path.join(__dirname, 'ui', 'shots');
fs.mkdirSync(out, { recursive: true });
const html = '<!doctype html><html><head><meta charset=utf8><meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover"></head><body style="margin:0">\n'
  + fs.readFileSync(built, 'utf8') + '\n</body></html>';

(async () => {
  const server = http.createServer((req, res) => { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html); }).listen(0, '127.0.0.1');
  await new Promise((ready) => server.once('listening', ready));
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 892, height: 412 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
  await context.addInitScript(() => {
    localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' }));
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => window.__arena && document.querySelector('#stage'));
  await page.waitForTimeout(400);
  if (await page.$('[data-do="tutskip"]')) await page.click('[data-do="tutskip"]');
  await page.evaluate(() => window.__arena.act('settings'));
  await page.waitForSelector('[data-do="benchbot"]');
  if (slow > 1) {
    const cdp = await context.newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: slow });
  }
  const t0 = Date.now();
  await page.click('[data-do="benchbot"]');
  await page.waitForFunction(() => { const b = document.querySelector('[data-do="benchbot"]'); return b && !b.disabled && /at most/.test(document.getElementById('benchOut').textContent); }, null, { timeout: 600000 });
  const text = await page.textContent('#benchOut');
  await page.screenshot({ path: path.join(out, 'bench_settings.png') });
  console.log(`CPU slowdown ${slow}x: ${text}  (whole test ${((Date.now() - t0) / 1000).toFixed(0)} s)`);
  if (errors.length) console.log('page errors:\n  ' + errors.join('\n  '));
  await browser.close();
  server.close();
  process.exit(errors.length || !/at most/.test(text) ? 1 : 0);
})();
