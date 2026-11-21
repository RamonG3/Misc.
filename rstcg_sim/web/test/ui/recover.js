// NPCs that take a card back from the discard pile into the hand (Vanaka, Banker, Raise Dead...): the card is
// seen flying from that pile, for you and for the other side (Ramon, 2026-11-20: when the other side did it,
// nothing could be seen). The bot is told its move, so each case is exactly one move.
// node web/test/ui/recover.js   ends "all passed"
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };
const DIST = path.join(__dirname, '..', '..', 'dist');
const site = require('http').createServer((req, res) => {
  fs.readFile(path.join(DIST, 'arena.html'), (e, d) => { res.writeHead(e ? 404 : 200, { 'Content-Type': 'text/html' }); res.end(d); });
});

(async () => {
  await new Promise((r) => site.listen(0, '127.0.0.1', r));
  const b = await chromium.launch();
  const page = await (await b.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(() => {
    window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
    localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'full', music: 'off', sfx: 'off', tips: 'off' }));
  });
  await page.goto(`http://127.0.0.1:${site.address().port}/arena.html`);
  await page.waitForFunction(() => window.__arena);
  await page.evaluate(() => window.__arena.act('tutskip'));
  const myTurn = async () => {
    for (let k = 0; k < 40; k++) {
      await page.waitForFunction(() => { const a = window.__arena, u = a.ui; return u.screen !== 'duel' || (u.pending && a.online.idle()); }, null, { timeout: 90000 });
      const type = await page.evaluate(() => { const u = window.__arena.ui; return u.screen !== 'duel' ? 'over' : u.pending.type; });
      if (type === 'choose' || type === 'over') return type;
      await page.evaluate(() => { const u = window.__arena.ui, p = u.pending; p.resolve(p.type === 'discard' ? u.g.players[u.you].hand[0] : null); });
      await page.waitForTimeout(250);
    }
    throw new Error('never got back to a choice');
  };
  await page.click('[data-do="play"]'); await page.click('[data-do="start"]');
  await page.waitForTimeout(700);
  if (await page.locator('[data-do="skipintro"]').count()) await page.click('[data-do="skipintro"]');
  await myTurn();
  await page.evaluate(() => {
    const u = window.__arena.ui, bot = u.g.agents[1 - u.you];
    window.__bot = { next: [] };
    bot.choose = async () => window.__bot.next.shift() || ['pass'];
    bot.react = async () => null;
    // watch for a flying card showing a given name
    window.__seen = [];
    new MutationObserver((ms) => { for (const m of ms) for (const n of m.addedNodes) if (n.classList && n.classList.contains('fly')) window.__seen.push(n.innerText || ''); })
      .observe(document.querySelector('#fx'), { childList: true });
  });

  // ----- the other side calls Vanaka to take back a Bronze Dagger -----
  const before = await page.evaluate(() => {
    const a = window.__arena, u = a.ui, g = u.g, them = g.players[1 - u.you];
    them.discard.push('Bronze Dagger'); them.hand.push('Vanaka');
    window.__bot.next = [['play', 'Vanaka', 'Bronze Dagger']];
    window.__seen.length = 0;
    a.resnap();
    u.pending.resolve(['pass']);
    return { hand: them.hand.length };
  });
  let shot = false;
  for (let k = 0; k < 60 && !shot; k++) {
    await page.waitForTimeout(100);
    const flying = await page.evaluate(() => [...document.querySelectorAll('#fx .fly')].map((e) => e.innerText).join('|'));
    if (/Bronze Dagger/i.test(flying)) { await page.screenshot({ path: path.join(__dirname, 'shots', 'recover-them.png') }); shot = true; }
  }
  await myTurn();
  const after = await page.evaluate(() => { const u = window.__arena.ui, them = u.g.players[1 - u.you]; return { hand: them.hand.includes('Bronze Dagger'), disc: them.discard.includes('Bronze Dagger'), seen: window.__seen }; });
  ok(after.hand && !after.disc, 'the engine moved the dagger to their hand');
  ok(after.seen.some((t) => /Bronze Dagger/i.test(t)), `the dagger is seen flying from their discard pile (${after.seen.length} flights)`);
  ok(shot, 'caught it mid-table');

  // ----- you call Vanaka for your own Bronze Dagger -----
  const mine = await page.evaluate(() => {
    const a = window.__arena, u = a.ui, g = u.g, me = g.players[u.you];
    me.discard.push('Bronze Dagger'); me.hand.push('Vanaka');
    window.__seen.length = 0;
    a.resnap();
    u.pending.resolve(['play', 'Vanaka', 'Bronze Dagger']);
    return true;
  });
  await page.waitForTimeout(2500);
  await myTurn();
  const after2 = await page.evaluate(() => { const u = window.__arena.ui, me = u.g.players[u.you]; return { hand: me.hand.includes('Bronze Dagger'), seen: window.__seen, hidden: [...document.querySelectorAll('#board .hc')].filter((e) => e.style.visibility === 'hidden').length }; });
  ok(mine && after2.hand, 'your dagger is back in your hand');
  ok(after2.seen.filter((t) => /Bronze Dagger/i.test(t)).length >= 1, 'your dagger is seen flying from your discard pile');
  ok(after2.hidden === 0, 'no card left hidden in your hand');
  ok(!errors.length, 'no page errors: ' + errors.join(' | '));
  await b.close(); site.close();
  console.log(`${checks} checks · ${failures ? failures + ' FAILED' : 'all passed'}`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
