// The installable app in web/dist/netlify (python web/build_netlify.py first): node web/test/ui/pwa.js
// Serves the folder on localhost, then checks that Chrome reads the manifest (landscape, full screen, icons)
// and finds nothing stopping an install, that the offline copy is saved, and that the page still opens with
// the network off. Prints "no errors" when all is well.
const fs = require('fs');
const http = require('http');
const path = require('path');
const { chromium } = require('playwright');
const DIR = path.join(__dirname, '..', '..', 'dist', 'netlify');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };
const fails = [];
const check = (ok, what) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`); if (!ok) fails.push(what); };

(async () => {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p.endsWith('/')) p += 'index.html';
    const f = path.join(DIR, p);
    if (!f.startsWith(DIR) || !fs.existsSync(f)) { res.statusCode = 404; res.end('not found'); return; }
    res.setHeader('Content-Type', TYPES[path.extname(f)] || 'application/octet-stream');
    res.end(fs.readFileSync(f));
  }).listen(0, '127.0.0.1');
  await new Promise((r) => server.once('listening', r));
  const url = `http://localhost:${server.address().port}/`;
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 892, height: 412 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));
  await page.goto(url);
  await page.waitForFunction(() => window.__arena);
  const cdp = await ctx.newCDPSession(page);
  const man = await cdp.send('Page.getAppManifest');
  check(!man.errors.length, `manifest reads cleanly${man.errors.length ? ': ' + JSON.stringify(man.errors) : ''}`);
  const m = man.parsed || {};
  const data = JSON.parse(fs.readFileSync(path.join(DIR, 'manifest.webmanifest'), 'utf8'));
  check(data.orientation === 'landscape' && data.display === 'fullscreen', 'landscape, full screen');
  check(data.icons.some((i) => i.sizes === '512x512' && i.purpose === 'maskable'), 'has a maskable icon');
  const ready = await page.evaluate(() => navigator.serviceWorker.ready.then((r) => !!r.active));
  check(ready, 'the offline worker is running');
  await page.waitForTimeout(1500);
  const cached = await page.evaluate(() => caches.keys().then((ks) => Promise.all(ks.map((k) => caches.open(k).then((c) => c.keys())))).then((l) => l.flat().map((r) => new URL(r.url).pathname)));
  check(cached.includes('/index.html') && cached.includes('/icon-512.png'), `the game is saved for offline (${cached.length} files)`);
  try {
    const inst = await cdp.send('Page.getInstallabilityErrors');
    check(!inst.installabilityErrors.length, `nothing stops an install${inst.installabilityErrors.length ? ': ' + inst.installabilityErrors.map((e) => e.errorId).join(', ') : ''}`);
  } catch (e) { console.log('     (this Chrome cannot report installability)'); }
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForFunction(() => window.__arena, null, { timeout: 15000 }).catch(() => {});
  check(await page.evaluate(() => !!window.__arena).catch(() => false), 'opens with the network off');
  console.log(errs.length ? 'page ERRORS:\n  ' + errs.join('\n  ') : fails.length ? `${fails.length} checks failed` : 'no errors');
  await b.close(); server.close();
  process.exit(errs.length || fails.length ? 1 : 0);
})();
