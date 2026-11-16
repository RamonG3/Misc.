// Online duels end to end, in two browsers, against a stand-in for Supabase (server/fake_supabase.mjs).
// node web/test/ui/online.js [decisions=50]     ends "all passed"; screenshots in web/test/ui/shots/online/
//
// Ann challenges, Bo joins with the link, they play (clicking and choosing as the page offers), Ann reloads
// in the middle and goes back to the duel, Ann's clock runs out once, the live connection drops and the pages
// poll instead, Bo concedes; then a rematch, conceded at once; then the things that should be refused
// (a taken name, a bad code). Checks GP, records, the log, no page errors and the board redrawn in place.
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const { chromium } = require('playwright');

const SHOTS = path.join(__dirname, 'shots', 'online');
fs.rmSync(SHOTS, { recursive: true, force: true });
fs.mkdirSync(SHOTS, { recursive: true });
const PORT = 54400 + Math.floor(Math.random() * 500);
const SERVER = `http://127.0.0.1:${PORT}`;
// The page comes from its own little web server, on another port: like the real app (Netlify) talking to Supabase,
// that's another site, so the browser's cross-site rules apply. (A file:// page loses its saved data on reload in
// these test browsers now and then.)
const PAGE = `http://127.0.0.1:${PORT + 1}/arena.html`;
const site = require('http').createServer((req, res) => {
  if (!req.url.startsWith('/arena.html')) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(fs.readFileSync(path.join(__dirname, '..', '..', 'dist', 'arena.html')));
});
const DECISIONS = +(process.argv[2] || 50);
let failures = 0;
const fail = (m) => { failures += 1; console.log('FAIL', m); };
const ok = (c, m) => { if (!c) fail(m); return c; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const post = (p, b) => fetch(SERVER + p, { method: 'POST', body: JSON.stringify(b || {}) }).then((r) => r.json());

(async () => {
  const fake = spawn('node', [path.join(__dirname, '..', '..', '..', 'server', 'fake_supabase.mjs'), String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((res, rej) => { fake.stdout.on('data', (d) => { if (/listening/.test(String(d))) res(); }); fake.on('exit', () => rej(new Error('the fake server stopped'))); });
  await new Promise((res) => site.listen(PORT + 1, '127.0.0.1', res));
  const browser = await chromium.launch();
  const errors = [];
  async function player(name, viewport, hash = '', phone = false) {
    const ctx = await browser.newContext(phone ? { viewport, isMobile: true, hasTouch: true, deviceScaleFactor: 1 } : { viewport });
    await ctx.addInitScript((srv) => {
      window.RSTCG_ONLINE_SERVER = { url: srv, key: 'sb_publishable_fake' };      // (each player is a fresh browser profile)
      localStorage.setItem('rstcg-speed', '"fast"');
    }, SERVER);
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
    // (Chrome logs a refused request or a dropped live connection as an error; those are expected here)
    page.on('console', (m) => { if (m.type() === 'error' && !/WebSocket connection to .* failed|Failed to load resource/.test(m.text())) errors.push(`${name}: ${m.text()}`); });
    await page.goto(PAGE + hash);
    await page.waitForFunction(() => window.__arena);
    await page.waitForTimeout(300);
    if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
    const P = { name, page, A: (f, x) => page.evaluate(f, x), shot: async (n) => { await page.waitForTimeout(700); await page.screenshot({ path: path.join(SHOTS, `${n}.png`) }); } };
    return P;
  }
  const ui = (P) => P.A(() => { const u = window.__arena.ui; return { screen: u.screen, on: u.on.state, pending: u.pending && u.pending.type, g: !!u.g, result: u.result && !!u.result.online }; });
  const until = async (P, f, x, what, ms = 20000) => { try { await P.page.waitForFunction(f, x, { timeout: ms }); return true; } catch (e) { fail(`${P.name}: waited for ${what}: ${JSON.stringify(await ui(P))}`); return false; } };
  const idle = (P) => P.A(() => window.__arena.online.idle());
  async function setName(P, name) {
    await P.page.click('[data-do="online"]');
    await until(P, () => window.__arena.ui.on.state === 'name', null, 'the name box');
    await P.page.fill('#onname', name);
    await P.page.click('[data-do="onname"]');
  }

  // ---------- a challenge, and a friend joining with the link ----------
  const ann = await player('Ann', { width: 1920, height: 1080 });
  await setName(ann, 'Ann');
  await until(ann, () => window.__arena.ui.screen === 'pick' && window.__arena.ui.pickMode === 'online', null, 'the online deck picker');
  await ann.shot('1_ann_pick');
  await ann.page.click('[data-do="onchallenge"]');
  await until(ann, () => window.__arena.ui.on.state === 'waiting', null, 'the waiting room');
  const code = await ann.A(() => [...document.querySelectorAll('.oncode b')].map((b) => b.textContent).join(''));
  ok(/^[A-Z2-9]{5}$/.test(code), `the code on screen: ${code}`);
  await ann.shot('2_ann_waiting');

  const bo = await player('Bob', { width: 1280, height: 720 }, `#duel-${code}`);
  await until(bo, () => window.__arena.ui.on.state === 'name', null, "Bob's name box (straight from the link)");
  ok(!(await bo.A(() => window.__arena.ui.modal)), 'the welcome box got in the way of the link');
  await bo.page.fill('#onname', 'Bob');
  await bo.page.click('[data-do="onname"]');
  await until(bo, () => window.__arena.ui.screen === 'pick' && window.__arena.ui.on.join && window.__arena.ui.on.join.host === 'Ann', null, 'Ann\'s challenge on Bo\'s screen');
  // Bob brings a deck full of answers to Melee (Protect from Melee, Mirror Shield), so reactions come up
  const legalDeck = await bo.A(() => {
    const A = window.__arena, p = A.profile;
    p.coll['Protect from Melee'] = 4; p.coll['Mirror Shield'] = 4;
    const r = p.decks.find((d) => d.id === 'st-ranged');
    const cards = { ...r.cards, 'Normal Logs': 6, 'Willow Logs': 9, 'Protect from Melee': 4, 'Mirror Shield': 4 };
    p.decks.push({ id: 'd-answers', name: 'Answers', cards, cape: 'Defence Cape' });
    A.act('pickdeck', 'd-answers');
    return A.deckRules(cards).ok;
  });
  ok(legalDeck, "Bob's test deck isn't legal");
  await bo.shot('3_bo_accept');
  await bo.page.click('[data-do="onaccept"]');
  for (const P of [ann, bo]) await until(P, () => window.__arena.ui.screen === 'duel' && window.__arena.online.od, null, 'the duel');
  await sleep(1200);
  for (const P of [ann, bo]) await P.shot(`4_${P.name}_intro`);
  for (const P of [ann, bo]) if (await P.page.locator('[data-do="skipintro"]').count()) await P.page.click('[data-do="skipintro"]').catch(() => {});
  const names = await ann.A(() => window.__arena.ui.config.names);
  ok(names.includes('Ann') && names.includes('Bob'), `names at the table: ${names}`);

  // ---------- playing: whoever is asked decides, as a player would ----------
  async function decide(P, k) {
    return P.A(async (k) => {
      const A = window.__arena, u = A.ui, p = u.pending;
      if (!p) return null;
      const r = ((k * 2654435761) >>> 0) / 4294967296;
      const click = (sel) => { const el = document.querySelector(sel); if (el && !el.disabled) { el.click(); return true; } return false; };
      if (p.type === 'react') { A.act('react', p.opts.length && r < 0.6 ? p.opts[0] : ''); return 'react'; }
      if (p.type === 'discard') { A.act('toss', '0'); return 'discard'; }
      const legal = p.legal.map((a, j) => [a, j]);
      const attack = legal.filter(([a]) => ['swing', 'free_swing', 'punch', 'spec', 'claim'].includes(a[0]));
      const plays = legal.filter(([a]) => ['play', 'quest', 'varrock', 'exchange'].includes(a[0]));
      const draw = legal.filter(([a]) => a[0] === 'draw');
      if (r < 0.12 || (!attack.length && !plays.length && !draw.length)) { if (!click('.endturn:not([disabled])')) A.act('core', 'pass'); return 'pass'; }
      const pool = attack.length && r < 0.5 ? attack : plays.length ? plays : draw.length ? draw : attack;
      const [a, j] = pool[Math.floor(r * 1000) % pool.length];
      if (a[0] === 'draw' && click('[data-do="core"][data-v="draw"]:not([disabled])')) return 'draw';
      A.act('act', String(j));
      return a[0];
    }, k);
  }
  const seen = { decisions: 0, react: 0, discard: 0, kinds: {} };
  let k = 0, reloaded = false, timedOut = false, polled = false;
  async function step() {
    for (const P of [ann, bo]) {
      if (!(await idle(P))) continue;
      if (!seen.react && (await P.A(() => window.__arena.ui.pending && window.__arena.ui.pending.type === 'react'))) {
        await post('/__test/clock', { add: 9e3 });              // a reaction with its clock showing (about 10 seconds left)
        await P.A(() => window.__arena.online.sync());
        await P.page.waitForTimeout(600);
        await P.page.screenshot({ path: path.join(SHOTS, `5_${P.name}_react_clock.png`) });
      }
      const d = await decide(P, k++);
      if (!d) continue;
      seen.decisions += 1;
      seen.kinds[d] = (seen.kinds[d] || 0) + 1;
      if (d === 'react') seen.react += 1;
      if (d === 'discard') seen.discard += 1;
      await sleep(60);
    }
  }
  async function morph(P) {
    if (!(await idle(P))) return;
    const m = await P.A(() => window.__arena.morphCheck());
    if (m !== true && m !== null) fail(`${P.name}: the board redrawn in place differs: ${JSON.stringify(m).slice(0, 300)}`);
  }
  const both = async (f) => { for (const P of [ann, bo]) await f(P); };
  const t0 = Date.now();
  while (seen.decisions < DECISIONS && Date.now() - t0 < 300e3) {
    // Ann's time runs out once
    if (!timedOut && seen.decisions >= 20 && (await ann.A(() => { const od = window.__arena.online.od; return !!(od && od.pending && od.pending.seat === window.__arena.ui.you && window.__arena.ui.pending); }))) {
      timedOut = true;
      await post('/__test/clock', { add: 90e3 });
      await bo.A(() => window.__arena.online.sync());
      await until(ann, () => window.__arena.ui.g.log.some((e) => /Your time ran out/.test(e.text)), null, 'Ann told her time ran out');
      await until(bo, () => window.__arena.ui.g.log.some((e) => /Ann ran out of time/.test(e.text)), null, 'Bob told Ann ran out of time');
      await sleep(400);
      await ann.shot('7_ann_timeout');
      continue;
    }
    await step();
    if (seen.decisions && seen.decisions % 9 === 0) await both(morph);
    const over = await ann.A(() => !!(window.__arena.online.od && window.__arena.online.od.result) || window.__arena.ui.screen === 'result');
    if (over) break;
    // part way: Ann's page reloads, and she goes back to the duel from the menu
    if (!reloaded && seen.decisions >= 12) {
      reloaded = true;
      await until(ann, () => window.__arena.online.idle(), null, 'Ann idle before the reload');
      const preReload = await ann.A(() => { const o = JSON.parse(localStorage.getItem('rstcg-online-v1') || '{}'); return { keys: Object.keys(localStorage), duel: o.duel ? { match: o.duel.match, seq: o.duel.seq } : o.duel, screen: window.__arena.ui.screen, od: !!window.__arena.online.od }; });
      await ann.page.reload();
      await ann.page.waitForFunction(() => window.__arena);
      if (!(await until(ann, () => !!document.querySelector('[data-do="onresume"]'), null, 'Resume online duel on the menu'))) {
        console.log('storage after the reload:', await ann.A(() => { const o = JSON.parse(localStorage.getItem('rstcg-online-v1') || '{}'); return JSON.stringify({ keys: Object.keys(localStorage), online: Object.keys(o), duel: o.duel ? { match: o.duel.match, seq: o.duel.seq } : o.duel, modal: window.__arena.ui.modal }); }));
        console.log('before:', JSON.stringify(preReload));
        await ann.shot('x_menu');
      }
      for (let n = 0; n < 8; n++) { if (await idle(bo)) { const d = await decide(bo, k++); if (d) seen.decisions += 1; } await sleep(250); }   // Bob plays on meanwhile
      await ann.shot('6_ann_menu_resume');
      await ann.page.click('[data-do="onresume"]');
      await until(ann, () => window.__arena.ui.screen === 'duel' && window.__arena.online.idle(), null, 'the duel again after the reload');
      const logged = await ann.A(() => window.__arena.ui.g.log.filter((e) => e.action).length);
      ok(logged >= 4, `the log after the reload has ${logged} moves in it`);
    }
    // the live connection drops: the pages poll instead, for a few decisions
    if (!polled && seen.decisions >= 28) {
      polled = seen.decisions;
      await post('/__test/realtime', { on: false });
    }
    if (polled && polled !== true && seen.decisions >= polled + 6) { polled = true; await post('/__test/realtime', { on: true }); }
    await sleep(80);
  }
  ok(seen.decisions >= Math.min(DECISIONS, 20), `only ${seen.decisions} decisions were made`);
  if (!seen.react) console.log('(no reactions came up this time)');
  ok(reloaded && timedOut, `reload ${reloaded}, timeout ${timedOut}`);
  console.log(`played: ${seen.decisions} decisions ${JSON.stringify(seen.kinds)} in ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  await both((P) => P.shot(`8_${P.name}_board`));

  // ---------- Bo concedes ----------
  const stillOn = await ann.A(() => window.__arena.ui.screen === 'duel' && window.__arena.ui.g.winner === null);
  if (stillOn) {
    await until(bo, () => window.__arena.online.idle(), null, 'Bo idle');
    await bo.page.click('[data-do="duelmenu"]');
    await bo.shot('9_bo_duelmenu');
    await bo.page.click('[data-do="concede"]');
  }
  for (const P of [ann, bo]) await until(P, () => window.__arena.ui.screen === 'result', null, 'the result screen', 30000);
  await sleep(2600);
  await both((P) => P.shot(`10_${P.name}_result`));
  const res = async (P) => P.A(() => { const r = window.__arena.ui.result, p = window.__arena.profile; return { won: r.won, reason: r.reason, pay: r.pay, gp: p.gp, online: p.online }; });
  const ra = await res(ann), rb = await res(bo);
  if (stillOn) {
    ok(ra.won && ra.reason === 'concede' && ra.pay === 100 && ra.gp === 100, `Ann's result: ${JSON.stringify(ra)}`);
    ok(!rb.won && rb.reason === 'concede' && rb.pay === 0 && rb.gp === 0, `Bo's result: ${JSON.stringify(rb)}`);
  } else console.log('(the duel ended on its own:', JSON.stringify(ra), JSON.stringify(rb), ')');
  ok(!(await ann.A(() => JSON.parse(localStorage.getItem('rstcg-online-v1') || '{}').duel)), 'the finished duel is still saved to resume');

  // ---------- a rematch: Ann offers, Bo takes it up ----------
  await ann.page.click('[data-do="rematch"]');
  await until(ann, () => window.__arena.ui.result.online.again === 'waiting', null, 'Ann waiting for a rematch');
  await until(bo, () => window.__arena.ui.result.online.offered, null, 'Bo seeing the rematch offer');
  await both((P) => P.shot(`11_${P.name}_rematch`));
  await bo.page.click('[data-do="rematch"]');
  for (const P of [ann, bo]) await until(P, () => window.__arena.ui.screen === 'duel' && window.__arena.online.od && window.__arena.online.od.seq === 0, null, 'the rematch');
  for (const P of [ann, bo]) if (await P.page.locator('[data-do="skipintro"]').count()) await P.page.click('[data-do="skipintro"]').catch(() => {});
  await until(ann, () => window.__arena.online.idle(), null, 'the rematch settled');
  await ann.page.click('[data-do="duelmenu"]');
  await ann.page.click('[data-do="concede"]');
  for (const P of [ann, bo]) await until(P, () => window.__arena.ui.screen === 'result', null, 'the rematch result', 30000);
  const ra2 = await res(ann), rb2 = await res(bo);
  ok(!ra2.won && rb2.won && rb2.pay === 100, `rematch: ${JSON.stringify(ra2)} ${JSON.stringify(rb2)}`);
  ok(stillOn ? ra2.online.wins === 1 && ra2.online.losses === 1 && rb2.online.wins === 1 && rb2.online.losses === 1 : true, `records: ${JSON.stringify(ra2.online)} ${JSON.stringify(rb2.online)}`);
  await ann.page.click('[data-do="menu"]');
  await ann.shot('12_ann_menu');

  // ---------- refused: a name that's taken, a code that isn't there ----------
  const cy = await player('Cyd', { width: 892, height: 412 }, '', true);      // a phone, held sideways
  await setName(cy, 'ann');
  await until(cy, () => /taken/.test(window.__arena.ui.on.nameErr), null, 'the name is taken');
  await cy.shot('13_cy_taken');
  await cy.page.fill('#onname', 'Cyd');
  await cy.page.click('[data-do="onname"]');
  await until(cy, () => window.__arena.ui.screen === 'pick', null, 'the picker after a good name');
  await cy.shot('13b_cy_pick');
  await cy.page.click('[data-do="oncode"]');
  await cy.page.fill('#oncode', 'zz9zz');
  await cy.page.press('#oncode', 'Enter');
  await until(cy, () => /No duel/.test(window.__arena.ui.on.codeErr), null, 'a bad code refused');
  await cy.shot('14_cy_badcode');

  const db = await fetch(SERVER + '/__test/db').then((r) => r.json());
  console.log(`server: ${db.profiles.length} names, ${db.matches.length} rooms and duels, statuses ${JSON.stringify(db.matches.map((m) => m.status))}`);
  if (errors.length) { fail('page errors'); console.log(errors.slice(0, 10).join('\n')); }
  await browser.close();
  fake.kill();
  site.close();
  if (!errors.length) console.log('no page errors');
  console.log(failures ? `${failures} FAILED` : 'all passed');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
