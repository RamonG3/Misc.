// Accounts and the cloud save end to end, in several browsers, against a stand-in for Supabase (server/fake_supabase.mjs).
// node web/test/ui/accounts.js     ends "all passed" (about a minute); screenshots in web/test/ui/shots/accounts/
//
// One player, Ann, on a PC, a phone and a tablet. On the PC she plays online as a guest, then signs in with Google
// (the trip to a pretend Google page and back) and is still the same player. The phone, a new game, signs in and
// simply has her progress. Both devices play, one of them offline for a while, and end up the same. The tablet has
// progress of its own, so it is asked which to keep: one answer each way, and "decide later". Then emailed codes
// (a new player, a guest, a wrong code, the mailer saying no), signing out and back in, the server signing a
// device out, a second tab, and the sign-in ways being switched off. Checks no page errors, and that every button
// on the account screens is on screen at each size.
//
// Since the bank (phase 3b), a device that has been online earns as the game does: each bot duel's pay is reported
// to the bank (100 a win, 50 a loss), and cards come from the server (here put straight in the bank, as if from a
// pack). GP, cards and packs are the bank's, so they're checked there, and they no longer travel in the cloud save.
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const { chromium } = require('playwright');

const SHOTS = path.join(__dirname, 'shots', 'accounts');
fs.rmSync(SHOTS, { recursive: true, force: true });
fs.mkdirSync(SHOTS, { recursive: true });
const PORT = 54900 + Math.floor(Math.random() * 500);
const SERVER = `http://127.0.0.1:${PORT}`;
// the page comes from its own little web server on another port (another site, as Netlify is to Supabase)
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
const get = (p) => fetch(SERVER + p).then((r) => r.json());
const db = () => get('/__test/db');
const saved = async (uid) => (await db()).progress.find((p) => p.user_id === uid);
const bankOf = async (uid) => (await db()).bank.find((b) => b.user_id === uid);
const lastMail = async (to) => { const m = await get('/__test/mail?to=' + encodeURIComponent(to)); return m[m.length - 1]; };

(async () => {
  const fake = spawn('node', [path.join(__dirname, '..', '..', '..', 'server', 'fake_supabase.mjs'), String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((res, rej) => { fake.stdout.on('data', (d) => { if (/listening/.test(String(d))) res(); }); fake.on('exit', () => rej(new Error('the fake server stopped'))); });
  await new Promise((res) => site.listen(PORT + 1, '127.0.0.1', res));
  const browser = await chromium.launch();
  const errors = [];
  const ready = async (page) => { await page.waitForFunction(() => window.__arena && window.__arena.ui.screen); await page.waitForTimeout(250); };
  async function device(name, viewport, phone = false) {
    const ctx = await browser.newContext(phone ? { viewport, isMobile: true, hasTouch: true, deviceScaleFactor: 1 } : { viewport });
    await ctx.addInitScript((srv) => {
      window.RSTCG_ONLINE_SERVER = { url: srv, key: 'sb_publishable_fake' };      // (each device is a fresh browser profile)
      localStorage.setItem('rstcg-speed', '"fast"');
    }, SERVER);
    const D = { name, ctx };
    D.open = async () => {
      const page = await ctx.newPage();
      page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
      // (Chrome logs a refused or failed request as an error; the offline and signed-out parts of this test cause some)
      page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|net::ERR_/.test(m.text())) errors.push(`${name}: ${m.text()}`); });
      await page.goto(PAGE);
      await ready(page);
      if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
      return page;
    };
    D.page = await D.open();
    D.A = (f, x) => D.page.evaluate(f, x);
    D.shot = async (n) => { await D.page.waitForTimeout(1100); await D.page.screenshot({ path: path.join(SHOTS, `${n}.png`) }); };
    D.state = () => D.A(() => { const A = window.__arena, c = A.cloud.state, a = A.cloud.account(); return { screen: A.ui.screen, acct: A.ui.acct.state, cloud: c.state, ask: !!c.ask, who: a, gp: A.profile.gp, mark: A.profile.cloud ? A.profile.cloud.rev : null }; });
    D.until = async (f, x, what, ms = 15000) => { try { await D.page.waitForFunction(f, x, { timeout: ms }); return true; } catch (e) { fail(`${name}: waited for ${what}: ${JSON.stringify(await D.state().catch(() => 'no page'))}`); return false; } };
    // Change the progress the way play does (the game saves, and the cloud save follows). A device with a bank earns
    // through it: gp as bot duels reported (wins of 100, then a loss of 50), cards put in the bank at the server.
    // A device that has never been online keeps it all to itself (with a pack opened for any cards, so it adds up).
    let seeds = 0;
    D.play = async (c) => {
      const k = await D.A(() => window.__arena.bank.kept());
      if (k && k.uid && k.bank) {
        if ((c.gp || 0) % 50) throw new Error(`${name}: a banked device earns in fifties: ${c.gp}`);
        if (c.cards) await post('/__test/bank', { uid: k.uid, cards: c.cards });
        await D.A(([c, base]) => {
          const A = window.__arena, p = A.profile;
          let left = c.gp || 0, n = 0;
          while (left >= 100) { A.bank.earn(`${base}${n++}`, 'win', 100); left -= 100; }
          if (left) A.bank.earn(`${base}${n++}`, 'loss', 50);
          p.wins += Math.max(0, (c.wins || 0) - Math.floor((c.gp || 0) / 100)); p.losses += Math.max(0, (c.losses || 0) - ((c.gp || 0) % 100 ? 1 : 0));
          if (c.deck) p.decks.push({ id: c.deck, name: c.deck, cards: { ...p.decks[0].cards }, cape: p.decks[0].cape });
          A.act('tipsreset');
        }, [c, `${name}${seeds++}x`]);
        return;
      }
      await D.A((c) => {
        const A = window.__arena, p = A.profile;
        p.gp += c.gp || 0; p.wins += c.wins || 0; p.losses += c.losses || 0;
        for (const [n, k] of Object.entries(c.cards || {})) p.coll[n] = (p.coll[n] || 0) + k;
        if (c.cards) p.packs = (p.packs || 0) + 1;
        if (c.deck) p.decks.push({ id: c.deck, name: c.deck, cards: { ...p.decks[0].cards }, cape: p.decks[0].cape });
        A.act('tipsreset');                                                      // any action that saves the profile
      }, c);
    };
    D.banked = (what = 'the bank to settle') => D.until(() => { const b = window.__arena.bank, k = b.kept(); return b.banked() && !b.state.busy && !(k.claims || []).length && !k.replace; }, null, what);
    D.synced = (what = 'the cloud save to settle') => D.until(() => { const c = window.__arena.cloud.state; return !c.busy && !c.timer && c.state === 'idle' && window.__arena.cloud.signedIn(); }, null, what);
    D.sync = async () => { await D.A(() => window.__arena.cloud.sync()); await D.synced(); await D.A(() => window.__arena.bank.sync(true)); await D.banked(); };
    D.openAccount = async () => {
      if (await D.A(() => window.__arena.ui.screen) !== 'account') await D.page.click('#acctchip');
      await D.until(() => window.__arena.ui.screen === 'account', null, 'the account screen');
    };
    // every button and box on screen must be inside the window, with nothing on top of it
    D.fits = async (what) => {
      await D.page.waitForTimeout(1100);             // (a screen slides in: wait for it to stand still)
      const bad = await D.A(() => [...document.querySelectorAll('#board [data-do], #board input')].filter((el) => {
        const r = el.getBoundingClientRect();
        if (r.width < 4 || r.height < 4) return false;
        if (r.left < -1 || r.top < -1 || r.right > innerWidth + 1 || r.bottom > innerHeight + 1) return true;
        const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return !(top && (el === top || el.contains(top) || top.contains(el)));
      }).map((el) => `${el.dataset.do || el.id}`));
      ok(!bad.length, `${name}: ${what}: off screen or covered: ${bad.join(', ')}`);
    };
    // to the pretend Google page and back as `who` (null: press Cancel there)
    // `twice`: Google is tied to another player already, so the game comes back, finds that out and goes straight
    // off to Google again to sign in as that player
    D.google = async (who, twice) => {
      await D.page.click('[data-do="acgoogle"]');
      await D.pickGoogle(who, twice);
    };
    D.pickGoogle = async (who, twice) => {
      await D.page.waitForURL(/__fake\/google/, { timeout: 15000 });
      await D.page.click(who ? `a[data-as="${who}"]` : 'a[data-as=""]');
      if (twice) {
        await D.page.waitForURL(/arena\.html/, { timeout: 15000 });
        await D.page.waitForURL(/__fake\/google/, { timeout: 15000 });
        await D.page.click(`a[data-as="${who}"]`);
      }
      await D.page.waitForURL(/arena\.html/, { timeout: 15000 });
      await ready(D.page);
    };
    return D;
  }
  const playOnlineAs = async (D, name) => {
    await D.page.click('[data-do="online"]');
    await D.until(() => window.__arena.ui.on.state === 'name', null, 'the name box');
    await D.page.fill('#onname', name);
    await D.page.click('[data-do="onname"]');
    await D.until(() => window.__arena.ui.screen === 'pick' && window.__arena.ui.pickMode === 'online', null, 'the online deck picker');
    await D.A(() => window.__arena.act('menu'));
  };

  try {
    // =====================================================================================
    // The PC: a guest with a name and some progress signs in with Google
    // =====================================================================================
    const pc = await device('PC', { width: 1920, height: 1080 });
    await pc.play({ gp: 450, wins: 3, losses: 1, cards: { 'Rune Scimitar': 2 }, deck: 'd-pc' });
    await playOnlineAs(pc, 'Ann');
    const guest = await pc.A(() => window.__arena.cloud.account());
    ok(guest && guest.guest === true, 'PC: playing online makes a guest');
    ok((await db()).progress.length === 0, 'a guest saved progress to the server');
    ok(/Sign in/.test(await pc.page.textContent('#acctchip')), 'PC: the menu offers "Sign in"');
    await pc.shot('01_pc_menu_guest');
    await pc.openAccount();
    await pc.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    ok(await pc.page.locator('[data-do="acgoogle"]').count() === 1 && await pc.page.locator('[data-do="acemail"]').count() === 1, 'PC: both ways to sign in are offered');
    ok(/Ann, comes with you/.test(await pc.page.textContent('.acctp')), 'PC: the guest is told their name comes along');
    await pc.fits('the sign-in screen');
    await pc.shot('02_pc_sign_in');

    // pressing Cancel at Google comes back with nothing changed
    await pc.google(null);
    await pc.until(() => window.__arena.ui.acct.state === 'error', null, 'the "came back" message');
    ok(/before signing in/.test(await pc.page.textContent('.acctp')), 'PC: cancelling at Google is explained');
    ok(!/[?#]/.test(pc.page.url().replace(/^[^/]*\/\/[^/]*\//, '')), `PC: the address is clean after cancelling: ${pc.page.url()}`);
    ok((await pc.A(() => window.__arena.cloud.account())).uid === guest.uid && (await pc.A(() => window.__arena.profile.gp)) === 450, 'PC: cancelling changed something');
    await pc.shot('03_pc_cancelled');
    await pc.page.click('[data-do="account"]');
    await pc.until(() => window.__arena.ui.acct.state === 'out' && window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the sign-in screen again');

    await pc.page.click('[data-do="acgoogle"]');
    await pc.page.waitForURL(/__fake\/google/);
    await pc.page.screenshot({ path: path.join(SHOTS, '04_pretend_google.png') });
    await pc.pickGoogle('ann@gmail.test');
    await pc.until(() => window.__arena.ui.acct.state === 'in', null, 'being signed in');
    await pc.synced();
    const ann = await pc.A(() => window.__arena.cloud.account());
    ok(ann.uid === guest.uid && ann.guest === false && ann.email === 'ann@gmail.test', `PC: the guest became the account: ${JSON.stringify(ann)}`);
    ok(pc.page.url() === PAGE, `PC: the code is out of the address: ${pc.page.url()}`);
    ok(!(await pc.A(() => window.__arena.ui.modal)), 'PC: a dialog got in the way of coming back');
    let row = await saved(ann.uid);
    await pc.banked();
    let bank = await bankOf(ann.uid);
    ok(bank && bank.gp === 450 && bank.coll['Rune Scimitar'] === 2 && bank.packs === 1, `PC: the guest's bank (opened online) is the account's: ${JSON.stringify(bank).slice(0, 120)}`);
    ok(row && row.rev === 1 && !('gp' in row.data) && !('coll' in row.data) && row.data.wins === 3 && row.data.decks.some((d) => d.id === 'd-pc'), `PC: the progress went up, without what the bank keeps: ${row && JSON.stringify(row.data).slice(0, 120)}`);
    ok(row && !('cloud' in row.data), "the device's own mark was sent to the server");
    ok(/ann@gmail\.test/.test(await pc.page.textContent('.acctp')) && /Ann/.test(await pc.page.textContent('.acctp')), 'PC: the account screen says who is signed in');
    ok(/saved to your account/.test(await pc.page.textContent('#acstatus')), `PC: the status line: ${await pc.page.textContent('#acstatus')}`);
    await pc.fits('the signed-in screen');
    await pc.shot('05_pc_signed_in');
    await pc.page.click('[data-do="menu"]');
    ok(/Ann/.test(await pc.page.textContent('#acctchip')), `PC: the menu shows the account: ${await pc.page.textContent('#acctchip')}`);
    await pc.shot('06_pc_menu_account');
    // online, she is still Ann, in the same seat
    ok((await db()).profiles.find((p) => p.id === ann.uid).name === 'Ann', 'the name stayed with the player');

    // a reload keeps everything
    await pc.page.reload(); await ready(pc.page);
    ok((await pc.A(() => window.__arena.cloud.account())).guest === false && /Ann/.test(await pc.page.textContent('#acctchip')), 'PC: a reload forgot the sign-in');
    await pc.synced();
    ok((await saved(ann.uid)).rev === 1, 'PC: a reload with nothing new saved again');

    // play on: the save follows by itself
    await pc.play({ gp: 100, wins: 1 });
    await pc.until(() => window.__arena.profile.cloud && window.__arena.profile.cloud.rev === 2, null, 'the save after a duel');
    await pc.banked();
    row = await saved(ann.uid);
    ok(row.rev === 2 && row.data.wins === 4 && (await bankOf(ann.uid)).gp === 550, `PC: play is saved, and the duel paid at the bank: ${(await bankOf(ann.uid)).gp} GP`);

    // =====================================================================================
    // The phone: a new game signs in, and has Ann's progress
    // =====================================================================================
    const phone = await device('Phone', { width: 892, height: 412 }, true);
    await phone.shot('07_phone_menu_guest');
    await phone.openAccount();
    await phone.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    await phone.fits('the sign-in screen');
    await phone.shot('08_phone_sign_in');
    await phone.page.tap('[data-do="acgoogle"]');
    await phone.pickGoogle('ann@gmail.test');
    await phone.until(() => window.__arena.ui.acct.state === 'in', null, 'being signed in');
    let st = await phone.state();
    ok(st.who.uid === ann.uid && !st.ask && st.gp === 550 && st.mark === 2, `Phone: a new game takes the account's progress without asking: ${JSON.stringify(st)}`);
    ok(await phone.A(() => window.__arena.profile.decks.some((d) => d.id === 'd-pc') && window.__arena.profile.coll['Rune Scimitar'] === 2 && window.__arena.profile.wins === 4), "Phone: the PC's decks and cards are here");
    ok((await saved(ann.uid)).rev === 2, 'Phone: signing in saved something');
    await phone.until(() => window.__arena.ui.on.name === 'Ann', null, 'the online name');
    await phone.fits('the signed-in screen');
    await phone.shot('09_phone_signed_in');
    await phone.page.tap('[data-do="menu"]');
    await phone.shot('10_phone_menu_account');
    ok(await phone.A(() => /COLLECTED/.test(document.body.textContent) && window.__arena.ui.screen === 'menu'), 'Phone: back at the menu');

    // the phone plays; the PC hears of it the next time it looks
    await phone.play({ gp: 100, wins: 1, cards: { 'Mithril Mace': 1 } });
    await phone.until(() => window.__arena.profile.cloud.rev === 3, null, 'the phone\'s save');
    await pc.sync();
    ok(await pc.A(() => window.__arena.profile.gp === 650 && window.__arena.profile.wins === 5 && window.__arena.profile.coll['Mithril Mace'] === 1 && window.__arena.profile.cloud.rev === 3), `PC: picked up the phone's play: ${JSON.stringify(await pc.state())}`);
    ok(/650/.test(await pc.page.textContent('.gp')), "PC: the menu shows the new GP without a reload");

    // the PC goes offline and plays; the phone plays too; back online they join up
    await pc.ctx.setOffline(true);
    await pc.play({ gp: 50, losses: 1, deck: 'd-offline' });
    await pc.until(() => window.__arena.cloud.state.state === 'offline', null, 'noticing it is offline');
    await pc.openAccount();
    await pc.page.waitForTimeout(600);
    ok(/safe on this device/.test(await pc.page.textContent('#acstatus')), `PC: offline is explained: ${await pc.page.textContent('#acstatus')}`);
    await pc.shot('11_pc_offline');
    await pc.page.click('[data-do="menu"]');
    await phone.play({ gp: 200, wins: 2, cards: { 'Rune Scimitar': 1 } });
    await phone.until(() => window.__arena.profile.cloud.rev === 4, null, 'the phone\'s second save');
    await pc.ctx.setOffline(false);
    await pc.sync();
    const want = { gp: 900, wins: 7, losses: 2 };
    st = await pc.A(() => { const p = window.__arena.profile; return { gp: p.gp, wins: p.wins, losses: p.losses, scim: p.coll['Rune Scimitar'], deck: p.decks.some((d) => d.id === 'd-offline'), rev: p.cloud.rev }; });
    ok(st.gp === want.gp && st.wins === want.wins && st.losses === want.losses && st.scim === 3 && st.deck && st.rev === 5, `PC: offline play and the phone's play are joined: ${JSON.stringify(st)}`);
    await phone.sync();
    ok(await phone.A((w) => { const p = window.__arena.profile; return p.gp === w.gp && p.wins === w.wins && p.losses === w.losses && p.decks.some((d) => d.id === 'd-offline'); }, want), `Phone: ends the same as the PC: ${JSON.stringify(await phone.state())}`);
    row = await saved(ann.uid);
    ok(row.rev === 5 && (await bankOf(ann.uid)).gp === 900, `the server ends the same: rev ${row.rev}, ${(await bankOf(ann.uid)).gp} GP`);

    // =====================================================================================
    // The tablet has progress of its own (and a guest of its own): which do you keep?
    // =====================================================================================
    const tablet = await device('Tablet', { width: 1280, height: 800 });
    await tablet.play({ gp: 75, wins: 2, deck: 'd-tablet' });
    await playOnlineAs(tablet, 'AnnTab');
    const tabGuest = await tablet.A(() => window.__arena.cloud.account());
    await tablet.openAccount();
    await tablet.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    await tablet.google('ann@gmail.test', true);       // Google is Ann's already: the game goes back for the account itself
    await tablet.until(() => window.__arena.ui.acct.state === 'choose', null, 'the question');
    st = await tablet.state();
    ok(st.who.uid === ann.uid && st.who.uid !== tabGuest.uid && st.ask && st.gp === 75 && st.mark === null, `Tablet: asked, with nothing changed yet: ${JSON.stringify(st)}`);
    ok((await saved(ann.uid)).rev === 5, 'Tablet: asking changed the account');
    await tablet.until(() => window.__arena.cloud.state.askBank && /GP\s*900/.test(document.querySelectorAll('.acsave')[1].textContent), null, "the account's bank on the question");
    const cards = await tablet.A(() => [...document.querySelectorAll('.acsave')].map((c) => c.textContent.replace(/\s+/g, ' ')));
    ok(cards.length === 2 && /This device/.test(cards[0]) && /GP\s*75/.test(cards[0]) && /Your account/.test(cards[1]) && /GP\s*900/.test(cards[1]), `Tablet: both saves are shown: ${cards.join(' | ')}`);
    await tablet.fits('the question');
    await tablet.shot('12_tablet_which_progress');
    ok(await tablet.A(() => window.__arena.ui.on.name !== 'AnnTab'), "Tablet: the guest's name was kept for the account");
    // one press asks "sure?", the second does it
    await tablet.page.click('[data-do="ackeep"][data-v="account"]');
    ok(/again to keep it/.test(await tablet.page.textContent('[data-do="ackeep"][data-v="account"]')) && (await tablet.state()).gp === 75, 'Tablet: one press was enough to replace progress');
    await tablet.shot('13_tablet_sure');
    await tablet.page.click('[data-do="ackeep"][data-v="account"]');
    await tablet.until(() => window.__arena.ui.acct.state === 'in', null, 'the answer taking effect');
    st = await tablet.state();
    ok(st.gp === 900 && st.mark === 5 && !st.ask && (await saved(ann.uid)).rev === 5, `Tablet: kept the account's: ${JSON.stringify(st)}`);
    const backup = await tablet.A(() => JSON.parse(localStorage.getItem('rstcg-backup-v1')));
    ok(backup && backup.data.gp === 75 && backup.data.decks.some((d) => d.id === 'd-tablet') && !('cloud' in backup.data), "Tablet: what it had is put aside");
    ok(/AnnTab/.test(JSON.stringify((await db()).profiles)) && await tablet.until(() => window.__arena.ui.on.name === 'Ann', null, "the account's name"), 'Tablet: online it is Ann now');

    // "keep this device's" replaces the account's, and the other devices follow
    const laptop = await device('Laptop', { width: 1366, height: 768 });
    await laptop.play({ gp: 2000, wins: 20, cards: { 'Dragon Dagger': 1 }, deck: 'd-laptop' });
    await laptop.openAccount();
    await laptop.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    await laptop.google('ann@gmail.test');             // no guest here: straight to the account
    await laptop.until(() => window.__arena.ui.acct.state === 'choose', null, 'the question');
    await laptop.fits('the question');
    await laptop.shot('14_laptop_which_progress');
    // the account moves on while she thinks about it
    await phone.play({ gp: 50, losses: 1 });
    await phone.until(() => window.__arena.profile.cloud.rev === 6, null, "the phone's save");
    await phone.banked();
    await laptop.page.click('[data-do="ackeep"][data-v="device"]');
    await laptop.page.click('[data-do="ackeep"][data-v="device"]');
    await laptop.until(() => window.__arena.ui.acct.state === 'choose' && /changed while you were choosing/.test(document.querySelector('.acctp').textContent), null, 'being asked again');
    await laptop.until(() => /GP\s*950/.test(document.querySelectorAll('.acsave')[1].textContent), null, "the account's newer bank");
    ok((await bankOf(ann.uid)).gp === 950 && /GP\s*950/.test(await laptop.page.textContent('.acsave:nth-child(2)')), 'Laptop: shown the newer copy before replacing it');
    await laptop.page.click('[data-do="ackeep"][data-v="device"]');
    await laptop.page.click('[data-do="ackeep"][data-v="device"]');
    await laptop.until(() => window.__arena.ui.acct.state === 'in', null, 'the answer taking effect');
    await laptop.banked();
    row = await saved(ann.uid); bank = await bankOf(ann.uid);
    ok(row.rev === 7 && bank.gp === 2000 && bank.coll['Dragon Dagger'] === 1 && row.data.decks.some((d) => d.id === 'd-laptop') && !row.data.decks.some((d) => d.id === 'd-pc'), `Laptop: its progress is the account's now: rev ${row.rev}, ${bank.gp} GP`);
    ok((await laptop.A(() => JSON.parse(localStorage.getItem('rstcg-backup-v1')))).data.gp === 950, "Laptop: the account's old progress is put aside");
    await pc.sync(); await phone.sync(); await tablet.sync();
    for (const D of [pc, phone, tablet]) ok(await D.A(() => window.__arena.profile.gp === 2000 && window.__arena.profile.decks.some((d) => d.id === 'd-laptop') && window.__arena.profile.coll['Dragon Dagger'] === 1), `${D.name}: follows the choice: ${JSON.stringify(await D.state())}`);
    ok(await pc.A(() => window.__arena.ui.screen === 'menu' && !!document.querySelector('#acctchip') && /2,000/.test(document.querySelector('.gp').textContent)), 'PC: the menu redrew with the new progress');

    // "decide later" signs out and leaves both alone
    const other = await device('Other', { width: 1600, height: 900 });
    await other.play({ gp: 5, wins: 1 });
    await other.openAccount();
    await other.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    await other.google('ann@gmail.test');
    await other.until(() => window.__arena.ui.acct.state === 'choose', null, 'the question');
    await other.page.click('[data-do="menu"]', { trial: true }).catch(() => {});
    await other.A(() => window.__arena.act('acback'));                       // leave without answering: the menu says there is a question waiting
    ok(await other.A(() => window.__arena.ui.screen === 'menu' && document.querySelector('#acctchip').classList.contains('warn')), 'Other: an unanswered question shows on the menu');
    await other.play({ gp: 10 });
    await other.page.waitForTimeout(1800);
    ok((await saved(ann.uid)).rev === 7 && (await other.state()).gp === 15, 'Other: played on unanswered, and nothing went to the account');
    await other.shot('15_other_menu_question');
    await other.openAccount();
    ok((await other.state()).acct === 'choose', 'Other: the chip leads back to the question');
    await other.page.click('[data-do="aclater"]');
    await other.until(() => window.__arena.ui.acct.state === 'out', null, 'being signed out');
    st = await other.state();
    ok(st.who === null && st.gp === 15 && !st.ask && (await saved(ann.uid)).rev === 7, `Other: "decide later" left both alone: ${JSON.stringify(st)}`);

    // =====================================================================================
    // Emailed codes
    // =====================================================================================
    const cat = await device('Cat', { width: 1920, height: 1080 });
    await cat.play({ gp: 300 });
    await cat.openAccount();
    await cat.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.email, null, 'the ways to sign in');
    await cat.page.click('[data-do="acemail"]');
    await cat.page.fill('#acemail', 'not an address');
    await cat.page.click('[data-do="acsend"]');
    ok(/doesn't look like an email/.test(await cat.page.textContent('.acctp')) && (await get('/__test/mail')).length === 0, 'Cat: nonsense was sent off as an address');
    await cat.shot('16_not_an_address');
    await cat.page.fill('#acemail', 'cat@mail.test');
    ok(!/doesn't look like an email/.test(await cat.page.textContent('.acctp')) && await cat.A(() => document.activeElement && document.activeElement.id) === 'acemail', 'Cat: typing again did not clear the complaint, or lost the box');
    await cat.shot('16_email');
    await cat.page.press('#acemail', 'Enter');
    await cat.until(() => window.__arena.ui.acct.state === 'code', null, 'the code box');
    let mail = await lastMail('cat@mail.test');
    ok(mail && mail.kind === 'signup', `Cat: a new player's code was emailed: ${JSON.stringify(mail)}`);
    await cat.page.fill('#accode', mail.code === '123456' ? '654321' : '123456');
    await cat.page.click('[data-do="acverify"]');
    await cat.until(() => window.__arena.ui.acct.state === 'code' && window.__arena.ui.acct.codeErr, null, 'the wrong code being refused');
    ok(/isn't right/.test(await cat.page.textContent('.acctp')) && (await cat.state()).who === null, 'Cat: a wrong code is explained');
    await cat.fits('the code screen');
    await cat.shot('17_wrong_code');
    await cat.page.click('[data-do="acresend"]');
    await cat.until(() => /Give it a minute/.test(document.querySelector('.acctp').textContent), null, 'being told to wait for a new code');
    await cat.page.fill('#accode', mail.code);
    await cat.page.press('#accode', 'Enter');
    await cat.until(() => window.__arena.ui.acct.state === 'in', null, 'being signed in');
    await cat.synced();
    const catWho = await cat.A(() => window.__arena.cloud.account());
    await cat.banked();
    ok(catWho.guest === false && catWho.email === 'cat@mail.test' && (await saved(catWho.uid)).data.wins === 0 && (await bankOf(catWho.uid)).gp === 300, `Cat: signed in by email, saved, and her bank opened from it: ${JSON.stringify(catWho)}`);
    ok(/code by email/.test(await cat.page.textContent('.acctp')), 'Cat: the account screen says how she signed in');
    await cat.shot('18_email_signed_in');

    // a guest with a name signs in by email and keeps both
    await post('/__test/clock', { add: 61e3 });
    const dan = await device('Dan', { width: 892, height: 412 }, true);
    await playOnlineAs(dan, 'Dan');
    const danGuest = await dan.A(() => window.__arena.cloud.account());
    await dan.openAccount();
    await dan.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.email, null, 'the ways to sign in');
    await dan.page.tap('[data-do="acemail"]');
    await dan.page.fill('#acemail', 'dan@mail.test');
    await dan.fits('the email screen');
    await dan.page.tap('[data-do="acsend"]');
    await dan.until(() => window.__arena.ui.acct.state === 'code', null, 'the code box');
    await dan.fits('the code screen');
    await dan.shot('19_phone_code');
    mail = await lastMail('dan@mail.test');
    ok(mail.kind === 'email_change', `Dan: a guest's code is for their own id: ${mail.kind}`);
    await dan.page.fill('#accode', mail.code);
    await dan.page.tap('[data-do="acverify"]');
    await dan.until(() => window.__arena.ui.acct.state === 'in', null, 'being signed in');
    const danWho = await dan.A(() => window.__arena.cloud.account());
    ok(danWho.uid === danGuest.uid && danWho.guest === false && await dan.A(() => window.__arena.ui.on.name === 'Dan'), `Dan: the guest became the account and kept the name: ${JSON.stringify(danWho)}`);

    // the built-in mailer only writes to the team: a friend is told to use Google
    await post('/__test/auth', { team: ['ramon@team.test'] });
    const eve = await device('Eve', { width: 1280, height: 720 });
    await eve.openAccount();
    await eve.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.email, null, 'the ways to sign in');
    await eve.page.click('[data-do="acemail"]');
    await eve.page.fill('#acemail', 'eve@mail.test');
    await eve.page.click('[data-do="acsend"]');
    await eve.until(() => window.__arena.ui.acct.state === 'email' && window.__arena.ui.acct.emailErr, null, 'the mailer saying no');
    ok(/can't reach that address yet/.test(await eve.page.textContent('.acctp')) && (await eve.state()).who === null, 'Eve: the mailer refusing is explained');
    await eve.shot('20_mailer_says_no');
    await post('/__test/auth', { team: null });

    // =====================================================================================
    // Signing out, and coming back
    // =====================================================================================
    await pc.openAccount();
    await pc.page.click('[data-do="acout"]');
    ok(/again to sign out/.test(await pc.page.textContent('[data-do="acout"]')) && (await pc.state()).who, 'PC: one press signed out');
    await pc.page.click('[data-do="acout"]');
    await pc.until(() => window.__arena.ui.acct.state === 'out', null, 'being signed out');
    st = await pc.state();
    ok(st.who === null && st.gp === 2000 && st.mark === 7, `PC: signed out, with its progress still here: ${JSON.stringify(st)}`);
    ok(await pc.A(() => !JSON.parse(localStorage.getItem('rstcg-online-v1')).auth && !window.__arena.ui.on.name), 'PC: the sign-in and the name are forgotten');
    await pc.play({ gp: 50, losses: 1 });            // played signed out: the duel waits to be reported to her bank
    await pc.page.waitForTimeout(1600);
    ok((await saved(ann.uid)).rev === 7 && (await bankOf(ann.uid)).gp === 2000, 'PC: a signed-out device saved to the account');
    await pc.A(() => window.__arena.act('account'));
    await pc.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    await pc.google('ann@gmail.test');
    await pc.until(() => window.__arena.ui.acct.state === 'in', null, 'being signed in again');
    await pc.synced(); await pc.banked();
    st = await pc.state();
    row = await saved(ann.uid);
    ok(!st.ask && st.gp === 2050 && row.rev === 8 && row.data.losses === 1 && (await bankOf(ann.uid)).gp === 2050, `PC: back in its own account without a question, signed-out play included: ${JSON.stringify(st)} rev ${row.rev}`);

    // a second window of the game on the same device notices
    const pc2 = await pc.open();
    ok(await pc2.evaluate(() => window.__arena.cloud.signedIn()), 'PC: a second window is signed in too');
    await pc.openAccount();
    await pc.page.click('[data-do="acout"]'); await pc.page.click('[data-do="acout"]');
    await pc.until(() => window.__arena.ui.acct.state === 'out', null, 'being signed out');
    await pc2.waitForFunction(() => window.__arena && window.__arena.cloud && !window.__arena.cloud.signedIn() && /Sign in/.test((document.querySelector('#acctchip') || {}).textContent || ''), null, { timeout: 15000 }).catch(() => fail('PC: the second window never noticed the sign-out'));
    await pc2.close();

    // the server signs a device out (her sign-ins were ended somewhere else)
    await post('/__test/revoke', { uid: ann.uid });
    await post('/__test/clock', { add: 3700e3 });
    const phoneGp = (await phone.state()).gp;
    await phone.play({ gp: 50, losses: 1 });
    await phone.until(() => !window.__arena.cloud.signedIn(), null, 'the phone noticing it was signed out');
    await phone.shot('21_phone_signed_out_by_server');
    ok(/Sign in/.test(await phone.page.textContent('#acctchip')) && (await saved(ann.uid)).rev === 8 && (await bankOf(ann.uid)).gp === 2050, 'Phone: signed out by the server, nothing saved');
    ok((await phone.state()).gp === phoneGp + 50 && (await phone.A(() => window.__arena.bank.kept().claims.length)) === 1, 'Phone: its progress is still on the device, the duel waiting to be reported');

    // the ways to sign in are the server's to switch on and off
    await post('/__test/auth', { google: false });
    const zed = await device('Zed', { width: 1280, height: 720 });
    await zed.openAccount();
    await zed.until(() => window.__arena.ui.acct.ways && 'google' in window.__arena.ui.acct.ways, null, 'the ways to sign in');
    ok(await zed.page.locator('[data-do="acgoogle"]').count() === 0 && await zed.page.locator('[data-do="acemail"]').count() === 1, 'Zed: with Google off, only email is offered');
    await zed.shot('22_email_only');
    await post('/__test/auth', { email: false });
    const yan = await device('Yan', { width: 1280, height: 720 });
    await yan.openAccount();
    await yan.until(() => window.__arena.ui.acct.ways && 'google' in window.__arena.ui.acct.ways, null, 'the ways to sign in');
    ok(/isn't switched on yet/.test(await yan.page.textContent('.acctp')) && await yan.page.locator('[data-do="acgoogle"], [data-do="acemail"]').count() === 0, 'Yan: with both off, the screen says so');
    await post('/__test/auth', { google: true, email: true });

    // a player in the middle of an online duel is asked to finish it first
    const will = await device('Will', { width: 1280, height: 720 });
    await will.A(() => { const o = JSON.parse(localStorage.getItem('rstcg-online-v1') || '{}'); o.duel = { match: 'x', opp: 'Bob' }; localStorage.setItem('rstcg-online-v1', JSON.stringify(o)); window.__arena.act('account'); });
    await will.until(() => window.__arena.ui.acct.ways && window.__arena.ui.acct.ways.google, null, 'the ways to sign in');
    ok(await will.page.locator('[data-do="acgoogle"][disabled]').count() === 1 && await will.page.locator('[data-do="acemail"][disabled]').count() === 1 && /Finish it first/.test(await will.page.textContent('.acctp')), 'Will: signing in is held back during an online duel');
    await will.page.waitForTimeout(1100);
    await will.shot('23_in_a_duel');

    const end = await db();
    console.log(`server: ${end.users.filter((u) => !u.is_anonymous).length} accounts, ${end.users.filter((u) => u.is_anonymous).length} guests, ${end.progress.length} saves; Ann's at revision ${(await saved(ann.uid)).rev}`);
    ok(errors.length === 0, `page errors: ${errors.join(' | ')}`);
    console.log(errors.length ? `${errors.length} page errors` : 'no page errors');
  } catch (e) { fail(`the test stopped: ${e.stack || e}`); }
  await browser.close();
  fake.kill();
  site.close();
  console.log(failures ? `${failures} FAILED (of ${checks} checks)` : `all passed (${checks} checks)`);
  process.exit(failures ? 1 : 0);
})();
