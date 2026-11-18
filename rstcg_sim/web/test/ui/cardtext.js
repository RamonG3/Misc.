// Settings → Card text: Large makes the words on every card face bigger and bolder (for a TV across the room).
// Every card's words must still fit its face (no text cut off, no name cut short) in both sizes, the setting must
// stick after a reload, and Settings must still fit on a phone.
// Run from anywhere: node web/test/ui/cardtext.js   (screenshots go to web/test/ui/shots/cardtext/)
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'cardtext');
fs.mkdirSync(SHOTS, { recursive: true });
const PAGE = 'file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html');
(async () => {
  const b = await chromium.launch();
  const errs = [];
  let checks = 0;
  const ok = (c, what) => { checks += 1; if (!c) errs.push(what); };
  const open = async (vp) => {
    const page = await b.newPage({ viewport: vp });
    page.on('pageerror', (e) => errs.push(e.message));
    await page.goto(PAGE);
    await page.waitForFunction(() => window.__arena);
    await page.waitForTimeout(200);
    if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
    return page;
  };
  const page = await open({ width: 1920, height: 1080 });
  await page.evaluate(() => localStorage.removeItem('rstcg-settings'));
  ok(!(await page.evaluate(() => document.documentElement.classList.contains('bigtext'))), 'normal text to begin with');
  // every card, both sizes
  for (const big of [false, true]) {
    const bad = await page.evaluate((big) => {
      document.documentElement.classList.toggle('bigtext', big);
      const A = window.__arena, host = document.createElement('div');
      host.style.cssText = 'position:absolute;left:0;top:0;z-index:99999';
      document.body.appendChild(host);
      const out = [];
      for (const n of Object.keys(A.CARDS)) {
        host.innerHTML = A.cardFace(n);
        const bd = host.querySelector('.cf-bd'), nm = host.querySelector('.cf-nm');
        if (bd.scrollHeight > bd.clientHeight + 1) out.push(`${n} (text ${bd.scrollHeight - bd.clientHeight}px over)`);
        if (nm.scrollWidth > nm.clientWidth + 1) out.push(`${n} (name ${nm.scrollWidth - nm.clientWidth}px over)`);
      }
      host.remove();
      return out;
    }, big);
    ok(!bad.length, `${big ? 'Large' : 'Normal'} card text doesn't fit: ${bad.join(', ')}`);
  }
  await page.evaluate(() => document.documentElement.classList.remove('bigtext'));
  // the setting
  await page.click('[data-do="settings"]');
  await page.click('[data-do="set"][data-v="cardtext:large"]');
  ok(await page.evaluate(() => document.documentElement.classList.contains('bigtext')), 'Large turns it on');
  ok((await page.evaluate(() => JSON.parse(localStorage.getItem('rstcg-settings')).cardtext)) === 'large', 'and saves it');
  await page.screenshot({ path: path.join(SHOTS, 'settings.png') });
  await page.reload(); await page.waitForFunction(() => window.__arena);
  ok(await page.evaluate(() => document.documentElement.classList.contains('bigtext')), 'still on after a reload');
  await page.evaluate(() => { const z = document.createElement('div'); z.style.cssText = 'position:fixed;left:20px;top:20px;z-index:99999;display:flex;gap:20px'; z.innerHTML = ['Vampyre Slayer', 'Protect from Missiles', 'Fire Wave'].map((n) => window.__arena.cardFace(n)).join(''); document.body.appendChild(z); });
  await page.screenshot({ path: path.join(SHOTS, 'large-faces.png'), clip: { x: 0, y: 0, width: 1240, height: 570 } });
  await page.close();
  // Settings on a phone, with the extra row: every button still on screen
  const ph = await open({ width: 892, height: 412 });
  await ph.click('[data-do="settings"]');
  await ph.waitForTimeout(300);
  const fits = await ph.evaluate(() => {
    const m = document.querySelector('.modal'), r = m.getBoundingClientRect();
    const btn = document.querySelector('[data-do="set"][data-v="cardtext:large"]');
    if (btn) btn.scrollIntoView({ block: 'nearest' });
    const br = btn && btn.getBoundingClientRect();
    return { modal: r.bottom <= innerHeight + 1 && r.top >= -1, btn: !!br && br.bottom <= innerHeight && br.top >= 0 };
  });
  ok(fits.modal && fits.btn, 'Settings fits a phone, and Card text can be reached: ' + JSON.stringify(fits));
  await ph.screenshot({ path: path.join(SHOTS, 'settings-phone.png') });
  await ph.close();
  console.log(`${checks} checks · ${errs.length ? 'FAILED\n  ' + errs.join('\n  ') : 'all passed'}`);
  await b.close();
  process.exit(errs.length ? 1 : 0);
})();
