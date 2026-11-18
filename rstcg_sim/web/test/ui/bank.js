// The bank in the page (phase 3b), against the stand-in for Supabase (server/fake_supabase.mjs).
// node web/test/ui/bank.js     ends "all passed" (about a minute); screenshots in web/test/ui/shots/bank/
//
// Pat has played offline for a while; going online opens her bank with that progress. Then packs come from the
// server (and need it: offline, nothing is spent), the tutorial's free pack once, a reset goes through the server,
// cards she hands herself in the browser are taken back, and her GP shows what the server says. Quinn's progress
// doesn't add up, so his bank starts from the starter decks and he is told so.
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const { chromium } = require('playwright');

const SHOTS = path.join(__dirname, 'shots', 'bank');
fs.rmSync(SHOTS, { recursive: true, force: true });
fs.mkdirSync(SHOTS, { recursive: true });
const PORT = 55500 + Math.floor(Math.random() * 400);
const SERVER = `http://127.0.0.1:${PORT}`;
const PAGE = `http://127.0.0.1:${PORT + 1}/arena.html`;
const site = require('http').createServer((req, res) => {
  if (!req.url.startsWith('/arena.html')) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(fs.readFileSync(path.join(__dirname, '..', '..', 'dist', 'arena.html')));
});
let failures = 0, checks = 0;
const fail = (m) => { failures += 1; console.log('FAIL', m); };
const ok = (c, m) => { checks += 1; if (!c) fail(m); return c; };
const post = (p, b) => fetch(SERVER + p, { method: 'POST', body: JSON.stringify(b || {}) }).then((r) => r.json());
const db = () => fetch(SERVER + '/__test/db').then((r) => r.json());
const bankOf = async (uid) => (await db()).bank.find((b) => b.user_id === uid);
const sum = (o) => Object.values(o || {}).reduce((s, k) => s + k, 0);

(async () => {
  const fake = spawn('node', [path.join(__dirname, '..', '..', '..', 'server', 'fake_supabase.mjs'), String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((res, rej) => { fake.stdout.on('data', (d) => { if (/listening/.test(String(d))) res(); }); fake.on('exit', () => rej(new Error('the fake server stopped'))); });
  await new Promise((res) => site.listen(PORT + 1, '127.0.0.1', res));
  const browser = await chromium.launch();
  const errors = [];
  async function device(name) {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    await ctx.addInitScript((srv) => { window.RSTCG_ONLINE_SERVER = { url: srv, key: 'sb_publishable_fake' }; localStorage.setItem('rstcg-speed', '"fast"'); localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' })); }, SERVER);
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|net::ERR_/.test(m.text())) errors.push(`${name}: ${m.text()}`); });
    await page.goto(PAGE);
    await page.waitForFunction(() => window.__arena && window.__arena.ui.screen);
    if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
    const D = { name, ctx, page, A: (f, x) => page.evaluate(f, x) };
    D.shot = async (n) => { await page.waitForTimeout(900); await page.screenshot({ path: path.join(SHOTS, `${n}.png`) }); };
    D.until = async (f, x, what, ms = 15000) => { try { await page.waitForFunction(f, x, { timeout: ms }); return true; } catch (e) { fail(`${name}: waited for ${what}`); return false; } };
    D.p = () => D.A(() => { const p = window.__arena.profile; return { gp: p.gp, coll: p.coll, packs: p.packs, free: !!p.tutorialPack }; });
    D.settled = () => D.until(() => { const b = window.__arena.bank; return b.banked() && !b.state.busy && !(b.kept().claims || []).length; }, null, 'the bank to settle');
    D.online = async (n) => {
      await page.click('[data-do="online"]');
      await D.until(() => window.__arena.ui.on.state === 'name', null, 'the name box');
      await page.fill('#onname', n);
      await page.click('[data-do="onname"]');
      await D.until(() => window.__arena.ui.screen === 'pick' && window.__arena.ui.pickMode === 'online', null, 'the online deck picker');
      await D.A(() => window.__arena.act('menu'));
    };
    return D;
  }

  try {
    // ---------- Pat: honest offline progress goes up with her the first time she's online ----------
    const pat = await device('Pat');
    await pat.A(() => {                         // eight duels and a pack, the way the game would have saved them
      const A = window.__arena, p = A.profile;
      p.wins = 5; p.losses = 3; p.gp = 5 * 100 + 3 * 50 - 200; p.packs = 1;
      for (const n of ['Rune Scimitar', 'Mithril Mace', 'Lobster', 'Air Wave', 'Water Strike', 'Fire Strike']) p.coll[n] = (p.coll[n] || 0) + 1;
      A.act('tipsreset');
    });
    const before = await pat.p();
    ok(!(await pat.A(() => window.__arena.bank.banked())), 'Pat: banked before ever going online');
    await pat.online('Pat');
    await pat.settled();
    const uid = await pat.A(() => window.__arena.cloud.account().uid);
    let bank = await bankOf(uid);
    ok(bank && bank.gp === 450 && bank.packs === 1 && JSON.stringify(bank.coll) === JSON.stringify(before.coll), `Pat: her bank opened with her progress: ${JSON.stringify(bank).slice(0, 160)}`);
    ok((await pat.p()).gp === 450, 'Pat: her GP is unchanged by the bank opening');

    // a pack from the server
    await pat.page.click('[data-do="packs"]');
    await pat.until(() => window.__arena.ui.screen === 'packs', null, 'the packs screen');
    await pat.shot('01_pat_packs');
    await pat.page.click('[data-do="buypack"]');
    await pat.until(() => window.__arena.ui.screen === 'open' && window.__arena.ui.pack, null, 'the pack opening');
    const pack = await pat.A(() => window.__arena.ui.pack.cards.map((c) => c.name));
    bank = await bankOf(uid);
    let mine = await pat.p();
    ok(pack.length === 6 && bank.gp === 250 && bank.packs === 2 && sum(bank.coll) === sum(before.coll) + 6, `Pat: the server opened the pack: ${JSON.stringify(pack)}, ${bank.gp} GP`);
    ok(mine.gp === 250 && JSON.stringify(mine.coll) === JSON.stringify(bank.coll) && mine.packs === 2, 'Pat: the page shows the bank as it is now');
    await pat.A(() => window.__arena.act('revealall'));
    await pat.shot('02_pat_pack_open');
    await pat.A(() => window.__arena.act('packs'));

    // offline, a pack can't be bought, and nothing is spent
    await pat.ctx.setOffline(true);
    await pat.page.click('[data-do="buypack"]');
    await pat.until(() => !!document.querySelector('.odtoast'), null, 'being told packs need the server');
    ok(/game server/.test(await pat.page.textContent('.odtoast')) && (await pat.p()).gp === 250 && (await bankOf(uid)).gp === 250, 'Pat: offline, the pack is explained and nothing is spent');
    await pat.shot('03_pat_offline_pack');
    const ref = (await pat.A(() => window.__arena.bank.kept())).packRef;
    ok(/^pack:/.test(ref || ''), 'Pat: the pack she tried to buy is remembered, so asking again cannot buy two');
    await pat.ctx.setOffline(false);
    await pat.page.waitForTimeout(500);
    await pat.page.click('[data-do="buypack"]');
    await pat.until(() => window.__arena.ui.screen === 'open', null, 'the pack, back online');
    ok((await bankOf(uid)).gp === 50 && (await pat.A(() => window.__arena.bank.kept().packRef)) == null, 'Pat: back online, one pack bought');
    await pat.A(() => window.__arena.act('packs'));
    ok(await pat.page.locator('[data-do="buypack"]').count() === 0 && /Need 150 more/.test(await pat.page.textContent('#board')), 'Pat: 50 GP is not enough for a pack');

    // the tutorial's free pack, once
    await pat.A(() => window.__arena.act('tutpack'));
    await pat.until(() => window.__arena.ui.screen === 'open', null, 'the free pack');
    bank = await bankOf(uid);
    ok(bank.free_pack === true && bank.gp === 50 && bank.packs === 4 && (await pat.p()).free, `Pat: the free pack: ${JSON.stringify(bank).slice(-90)} ${JSON.stringify(await pat.p()).slice(-60)}`);
    await pat.A(() => { window.__arena.act('packtutok'); window.__arena.act('menu'); });

    // cards handed out in the browser are taken back; GP the same
    await pat.A(() => { const A = window.__arena; A.profile.coll['Dragon Dagger'] = 4; A.profile.gp = 99999; A.act('tipsreset'); });
    await pat.A(() => window.__arena.bank.sync(true));
    await pat.settled();
    mine = await pat.p();
    ok(!mine.coll['Dragon Dagger'] === !bank.coll['Dragon Dagger'] && mine.gp === 50, `Pat: the bank's numbers win over the browser's: ${mine.gp} GP`);
    ok(await pat.A(() => /\b50\b/.test(document.querySelector('.gp').textContent)), `Pat: the menu shows the bank's GP: ${await pat.A(() => window.__arena.ui.screen + ' ' + (document.querySelector('.gp') || {}).textContent)}`);

    // a bot duel pays at once and the bank hears of it
    await pat.A(() => window.__arena.bank.earn('patduel1', 'win', 100));
    await pat.settled();
    ok((await bankOf(uid)).gp === 150 && (await pat.p()).gp === 150, 'Pat: a bot duel reported and paid');

    // reset goes through the server
    await pat.page.click('[data-do="settings"]');
    await pat.page.click('[data-do="reset"]'); await pat.page.click('[data-do="reset"]');
    await pat.until(() => window.__arena.profile.gp === 0 && window.__arena.profile.packs === 0, null, 'the reset');
    bank = await bankOf(uid);
    ok(bank.gp === 0 && bank.packs === 0 && !bank.free_pack && !bank.coll['Rune Scimitar'], `Pat: the reset reached the bank: ${JSON.stringify(bank).slice(0, 120)}`);
    await pat.shot('04_pat_reset');

    // ---------- Quinn: progress that doesn't add up starts from the starter decks ----------
    const quinn = await device('Quinn');
    await quinn.A(() => { const A = window.__arena, p = A.profile; p.gp = 50000; for (const n of A.POOL) p.coll[n] = 4; A.act('tipsreset'); });
    await quinn.online('Quinn');
    await quinn.until(() => !!document.querySelector('.odtoast') && /didn't add up/.test(document.querySelector('.odtoast').textContent), null, 'being told');
    await quinn.shot('05_quinn_doubted');
    await quinn.settled();
    const qid = await quinn.A(() => window.__arena.cloud.account().uid);
    const qb = await bankOf(qid), qp = await quinn.p();
    ok(qb.gp === 0 && qp.gp === 0 && JSON.stringify(qp.coll) === JSON.stringify(qb.coll) && !qp.coll['Dragon Dagger'], `Quinn: started from the starter decks: ${qp.gp} GP`);

    ok(errors.length === 0, `page errors: ${errors.join(' | ')}`);
    console.log(errors.length ? `${errors.length} page errors` : 'no page errors');
  } catch (e) { fail(`the test stopped: ${e.stack || e}`); }
  await browser.close();
  fake.kill();
  site.close();
  console.log(failures ? `${failures} FAILED (of ${checks} checks)` : `all passed (${checks} checks)`);
  process.exit(failures ? 1 : 0);
})();
