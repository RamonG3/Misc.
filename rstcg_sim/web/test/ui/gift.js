// A starter deck added after a player began (web/packs.js GIFTS: Necromancy, 2026-11-27, Ramon) is given once:
// the deck and its cards. Against the stand-in for Supabase (server/fake_supabase.mjs).
// node web/test/ui/gift.js     ends "all passed" (about a minute)
//
// Old Ola played before Necromancy and never went online: her save gets the Necromancy deck and its cards, once,
// and when she goes online her bank opens with them (logged as kept, not given again). Ben is new: his starter decks
// hold Necromancy already, so nothing is added. Bea's bank is from before Necromancy: her device adds the deck, the
// bank gives the cards, once.
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const { chromium } = require('playwright');

const PORT = 55900 + Math.floor(Math.random() * 400);
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
const J = JSON.stringify;
const same = (a, b) => J(Object.keys(a || {}).sort().map((n) => [n, a[n]])) === J(Object.keys(b || {}).sort().map((n) => [n, b[n]]));

const CARDS = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'cards.json'), 'utf8'));
const PK = require(path.join(__dirname, '..', '..', 'packs.js'));
const rare = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', '..', 'server', 'rarity.json'), 'utf8'));
const names = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', '..', 'server', 'set1.json'), 'utf8'));
const P = PK.make(CARDS.cards, Object.fromEntries(names.map((n) => [n, { rarity: rare[n] || 'C' }])), CARDS.decks);
const START = P.starter().coll, GIFT = P.gift('Necromancy');
const plus = (a, b) => { const o = { ...a }; for (const [n, k] of Object.entries(b)) o[n] = (o[n] || 0) + k; return o; };
const minus = (a, b) => { const o = { ...a }; for (const [n, k] of Object.entries(b)) { o[n] = (o[n] || 0) - k; if (!(o[n] > 0)) delete o[n]; } return o; };
const OLD_START = minus(START, GIFT);
const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-');
// a save from before Necromancy: the three starter decks, a few cards from a pack, some duels
const oldDecks = CARDS.decks.filter((d) => d.group === 'Starter decks' && d.name !== 'Necromancy')
  .map((d) => ({ id: `st-${slug(d.name)}`, name: d.name, cards: { ...d.cards }, cape: d.capes[0], starter: true }));
const oldColl = plus(OLD_START, { 'Rune Scimitar': 1, Lobster: 1, 'Air Wave': 1 });
const OLD_SAVE = { gp: 300, wins: 4, losses: 2, paid: null, decks: oldDecks, coll: oldColl, packs: 1, lastPack: null, tutorial: 'skipped' };

(async () => {
  const fake = spawn('node', [path.join(__dirname, '..', '..', '..', 'server', 'fake_supabase.mjs'), String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((res, rej) => { fake.stdout.on('data', (d) => { if (/listening/.test(String(d))) res(); }); fake.on('exit', () => rej(new Error('the fake server stopped'))); });
  await new Promise((res) => site.listen(PORT + 1, '127.0.0.1', res));
  const browser = await chromium.launch();
  const errors = [];
  async function device(name, save) {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    await ctx.addInitScript(([srv, save]) => {
      window.RSTCG_ONLINE_SERVER = { url: srv, key: 'sb_publishable_fake' };
      localStorage.setItem('rstcg-speed', '"fast"');
      localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' }));
      if (save && !localStorage.getItem('rstcg-profile-v1')) localStorage.setItem('rstcg-profile-v1', JSON.stringify(save));   // the first visit only
    }, [SERVER, save || null]);
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|net::ERR_/.test(m.text())) errors.push(`${name}: ${m.text()}`); });
    const D = { name, ctx, page, A: (f, x) => page.evaluate(f, x) };
    D.open = async () => {
      await page.goto(PAGE);
      await page.waitForFunction(() => window.__arena && window.__arena.ui.screen);
      if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
    };
    D.until = async (f, x, what, ms = 15000) => { try { await page.waitForFunction(f, x, { timeout: ms }); return true; } catch (e) { fail(`${name}: waited for ${what}`); return false; } };
    D.p = () => D.A(() => { const p = window.__arena.profile; return { coll: p.coll, decks: p.decks.map((d) => d.id), gifts: p.gifts, gp: p.gp,
      legal: p.decks.filter((d) => d.id === 'st-necromancy').map((d) => window.__arena.deckRules(d.cards).ok) }; });
    D.saved = () => D.A(() => JSON.parse(localStorage.getItem('rstcg-profile-v1')));
    D.settled = () => D.until(() => { const b = window.__arena.bank; return b.banked() && !b.state.busy && !(b.kept().claims || []).length; }, null, 'the bank to settle');
    D.online = async (n) => {
      await page.click('[data-do="online"]');
      await D.until(() => window.__arena.ui.on.state === 'name', null, 'the name box');
      await page.fill('#onname', n);
      await page.click('[data-do="onname"]');
      await D.until(() => window.__arena.ui.screen === 'pick' && window.__arena.ui.pickMode === 'online', null, 'the online deck picker');
      await D.A(() => window.__arena.act('menu'));
    };
    await D.open();
    return D;
  }

  try {
    // ---------- Ola: an old save, never online ----------
    const ola = await device('Ola', OLD_SAVE);
    let p = await ola.p();
    ok(p.decks.join() === 'st-melee,st-ranged,st-air-mage,st-necromancy', `Ola: the Necromancy deck added after hers: ${p.decks.join()}`);
    ok(same(p.coll, plus(oldColl, GIFT)), 'Ola: its cards added to what she had');
    ok(J(p.gifts) === '["Necromancy"]' && p.legal[0] === true && p.gp === 300, `Ola: marked as given, the deck legal, nothing else touched: ${J([p.gifts, p.legal, p.gp])}`);
    ok(same((await ola.saved()).coll, p.coll), 'Ola: saved');
    await ola.open();
    p = await ola.p();
    ok(same(p.coll, plus(oldColl, GIFT)) && p.decks.length === 4, 'Ola: opening the game again gives nothing more');
    await ola.A(() => { const A = window.__arena; A.profile.decks = A.profile.decks.filter((d) => d.id !== 'st-necromancy'); A.act('menu'); localStorage.setItem('rstcg-profile-v1', JSON.stringify(A.profile)); });
    await ola.open();
    ok((await ola.p()).decks.length === 3, 'Ola: a gift deck she deleted stays deleted');
    // ...and goes online: her bank opens with her cards, the gift among them, once
    await ola.online('Ola');
    await ola.settled();
    let d = await db();
    const uO = await ola.A(() => window.__arena.cloud.account().uid);
    const bO = d.bank.find((b) => b.user_id === uO);
    ok(bO && same(bO.coll, plus(oldColl, GIFT)), `Ola: her bank has the gift once: ${J(bO && minus(bO.coll, plus(oldColl, GIFT)))}`);
    ok(d.bankLog[uO] && d.bankLog[uO]['gift:Necromancy'] === 'gift-kept', `Ola: the bank logged it as hers already: ${J(d.bankLog[uO])}`);
    ok(same((await ola.p()).coll, plus(oldColl, GIFT)), 'Ola: and her device shows the same');

    // ---------- Ben: new, never online ----------
    const ben = await device('Ben', null);
    p = await ben.p();
    ok(same(p.coll, START) && p.decks.length === 4 && J(p.gifts) === '["Necromancy"]', `Ben: the four starter decks, nothing twice: ${J(minus(p.coll, START))}`);
    await ben.open();
    ok(same((await ben.p()).coll, START), 'Ben: and again after opening the game again');

    // ---------- Bea: her bank is from before Necromancy ----------
    const bea = await device('Bea', null);
    await bea.online('Bea');
    await bea.settled();
    const uB = await bea.A(() => window.__arena.cloud.account().uid);
    // as if her bank had opened before the gift existed, and her device's save were from then
    await post('/__test/bank', { uid: uB, cards: Object.fromEntries(Object.entries(GIFT).map(([n, k]) => [n, -k])) });
    ok((await post('/__test/unlog', { uid: uB, ref: 'gift:Necromancy' })).gone, 'Bea: the gift line taken out of her log');
    d = await db();
    ok(same(d.bank.find((b) => b.user_id === uB).coll, OLD_START), 'Bea: her bank is the old starter decks');
    await bea.A(() => {
      const A = window.__arena;
      A.profile.decks = A.profile.decks.filter((x) => x.id !== 'st-necromancy'); delete A.profile.gifts;
      localStorage.setItem('rstcg-profile-v1', JSON.stringify(A.profile));
    });
    await bea.A((g) => { const k = JSON.parse(localStorage.getItem('rstcg-bank-v1')); for (const [n, x] of Object.entries(g)) { k.bank.coll[n] -= x; if (!(k.bank.coll[n] > 0)) delete k.bank.coll[n]; } localStorage.setItem('rstcg-bank-v1', JSON.stringify(k)); }, GIFT);
    await bea.open();
    p = await bea.p();
    ok(p.decks.includes('st-necromancy') && J(p.gifts) === '["Necromancy"]', `Bea: the deck added on her device: ${p.decks.join()}`);
    await bea.A(() => window.__arena.bank.sync(true));
    await bea.settled();
    await bea.until((s) => { const c = window.__arena.profile.coll; return (c['Touch of Death'] || 0) === s; }, GIFT['Touch of Death'], 'the bank to give her the cards');
    d = await db();
    ok(same(d.bank.find((b) => b.user_id === uB).coll, START) && d.bankLog[uB]['gift:Necromancy'] === 'gift', 'Bea: the bank gave the cards, logged as a gift');
    ok(same((await bea.p()).coll, START), 'Bea: her device shows the same');
    await bea.A(() => window.__arena.bank.sync(true));
    await bea.settled();
    await bea.open();
    await bea.A(() => window.__arena.bank.sync(true));
    await bea.settled();
    ok(same((await db()).bank.find((b) => b.user_id === uB).coll, START) && same((await bea.p()).coll, START), 'Bea: and only once');
  } catch (e) {
    fail(`the test stopped: ${e && e.stack ? e.stack.split('\n').slice(0, 3).join(' ') : e}`);
  }
  ok(!errors.length, `page errors: ${errors.join(' | ')}`);
  await browser.close();
  fake.kill(); site.close();
  if (!errors.length) console.log('no page errors');
  console.log(failures ? `${checks} checks · ${failures} FAILED` : `all passed (${checks} checks)`);
  process.exit(failures ? 1 : 0);
})();
