// Plays whole duels in dist/arena.html through the page itself and fails on any page error.
//   node web/test/arena_play.js [games=3] [outdir=web/test/arena_shots] [youDeck=Melee] [botDeck=Air Mage]
// Plays by real mouse drags most of the time (hand card -> its board zone, reactions up to the panel,
// hand-limit discards onto the discard pile), by clicks otherwise; each game also makes one drop on
// nothing and checks the card springs back. Screenshots a few moments of the first game.
const path = require('path');
const fs = require('fs');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const { chromium } = require('playwright');

const games = +(process.argv[2] || 3);
const outdir = process.argv[3] || path.join(__dirname, 'arena_shots');
const youDeck = process.argv[4] || 'Melee';
const botDeck = process.argv[5] || 'Air Mage';
const page_url = 'file://' + path.join(__dirname, '..', 'dist', 'arena.html');
fs.mkdirSync(outdir, { recursive: true });

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(() => { localStorage.setItem('rstcg-speed', '"fast"'); });
  await page.goto(page_url);
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(200);
  if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');   // first launch offers the tutorial
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(outdir, '00_start.png') });

  // A point inside a zone that a real pointer would hit (topmost element there is in, or is, the zone).
  async function zonePoint(zone) {
    return page.evaluate((z) => {
      const els = [...document.querySelectorAll('[data-zone]')].filter((n) => n.dataset.zone === z);
      for (const el of els) {
        const r = el.getBoundingClientRect();
        if (r.width < 4 || r.height < 4) continue;
        for (const fy of [0.5, 0.3, 0.7, 0.15, 0.85]) for (const fx of [0.5, 0.3, 0.7, 0.15, 0.85]) {
          const x = r.left + r.width * fx, y = r.top + r.height * fy;
          let n = document.elementFromPoint(x, y);
          while (n && n !== el) n = n.parentElement;
          if (n === el) return { x, y };
        }
      }
      return null;
    }, zone);
  }
  async function cardPoint(sel) {
    const b = await page.locator(sel).first().boundingBox();
    return b && { x: b.x + b.width / 2, y: b.y + b.height * 0.45 };
  }
  // Press on a card, carry it to (x,y) in steps, optionally screenshot mid-air, release.
  async function dragTo(from, to, shot) {
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    await page.mouse.move(from.x + 14, from.y - 14, { steps: 2 });
    await page.mouse.move(to.x, to.y, { steps: 12 });
    if (shot) { await sleep(80); await page.screenshot({ path: shot }); }
    await page.mouse.up();
    await sleep(230);                 // land / spring-back animation
  }
  // Somewhere no candidate zone covers: the drop should spring back
  async function deadPoint() {
    return page.evaluate(() => {
      for (const [x, y] of [[1450, 900], [1480, 1040], [1300, 1060], [400, 1060]]) {      // below the board line, off the hand
        let n = document.elementFromPoint(x, y), hit = false;
        while (n && n !== document.body) { if (n.classList && (n.classList.contains('dz-on') || n.classList.contains('dz-alt') || n.classList.contains('hc'))) hit = true; n = n.parentElement; }
        if (!hit) return { x, y };
      }
      return null;
    });
  }
  const shots = new Set();
  const stats = { drags: 0, clicks: 0, springs: 0, menus: 0 };
  const results = [];
  for (let gi = 0; gi < games; gi++) {
    await page.click('[data-do="play"]');                         // menu -> deck + cape pick
    await page.evaluate(([y, b]) => { const a = window.__arena; a.act('pickdeck', y); a.act('pickbot', b); }, [youDeck, botDeck]);
    if (gi === 0) await page.screenshot({ path: path.join(outdir, '00_pick.png') });
    await page.click('[data-do="start"]');
    if (gi % 2 === 1) { await page.waitForTimeout(800); await page.click('[data-do="skipintro"]'); }   // every other game skips the intro
    let steps = 0, sprung = false;
    while (steps < 3000) {
      steps++;
      const st = await page.evaluate(() => {
        const u = window.__arena.ui; const g = u.g;
        if (!g) return { none: true };
        const p = u.pending;
        return { winner: g.winner, turn: g.turn, active: g.active, you: u.you, type: p && p.type,
          legal: p && p.legal ? p.legal.map((a) => a) : null, opts: p && p.opts, hand: g.players[u.you].hand.length,
          loc: g.location, claimedAny: Object.keys(g.cape_table).length && Object.keys(g.cape_claimed).length };
      });
      if (st.none) break;
      if (st.winner !== null) break;
      if (!st.type) { await page.waitForTimeout(60); continue; }
      const tag = (n) => (gi === 0 && !shots.has(n) ? (shots.add(n), true) : false);
      if (st.type === 'react') {
        if (tag('react')) await page.screenshot({ path: path.join(outdir, '03_react.png') });
        const use = Math.random() < 0.6 && st.opts.length;
        if (use && Math.random() < 0.7) {
          const from = await cardPoint('.hc.react'); const to = await zonePoint('react');
          if (!to) throw new Error('reaction panel not reachable by pointer');
          const once = (n) => (!shots.has(n) ? (shots.add(n), true) : false);
          await dragTo(from, to, once('reactdrag') ? path.join(outdir, '11_react_drag.png') : null); stats.drags++;
        } else if (use) { await page.click('.hc.react >> nth=0'); stats.clicks++; }
        else await page.click('button[data-do="react"][data-v=""]');
        continue;
      }
      if (st.type === 'discard') {
        if (tag('discard')) await page.screenshot({ path: path.join(outdir, '04_discard.png') });
        if (Math.random() < 0.7) {
          const to = await zonePoint('mydiscard');
          if (!to) throw new Error('discard pile not reachable by pointer');
          await dragTo(await cardPoint('.hc.toss'), to); stats.drags++;
        } else { await page.click('.hc.toss >> nth=0'); stats.clicks++; }
        continue;
      }
      // choose: weighted random over the legal actions, carried out by clicking
      if (st.turn >= 6 && tag('mid')) await page.screenshot({ path: path.join(outdir, '01_mid.png') });
      if (st.loc && tag('loc')) await page.screenshot({ path: path.join(outdir, '02_location.png') });
      if (st.claimedAny && tag('cape')) await page.screenshot({ path: path.join(outdir, '05_cape.png') });
      const legal = st.legal;
      const nonPass = legal.map((a, k) => [a, k]).filter(([a]) => a[0] !== 'pass' && a[0] !== 'draw');
      let pick;
      if (!nonPass.length || Math.random() < 0.12) pick = legal.findIndex((a) => a[0] === (legal.some((x) => x[0] === 'draw') && Math.random() < 0.5 ? 'draw' : 'pass'));
      else pick = nonPass[Math.floor(Math.random() * nonPass.length)][1];
      if (pick < 0) pick = legal.length - 1;
      const a = legal[pick];
      if (a[0] === 'play' || a[0] === 'varrock' || a[0] === 'exchange') {
        const k = await page.evaluate((name) => window.__arena.ui.g.players[window.__arena.ui.you].hand.indexOf(name), a[1]);
        const sel = `.hc[data-v="${k}"]`;
        if (!sprung) {                    // once a game: drop it on nothing; it must come back unplayed
          sprung = true;
          const before = await page.evaluate(() => window.__arena.ui.g.players[window.__arena.ui.you].hand.length);
          await page.mouse.move(0, 0);
          const from = await cardPoint(sel);
          await page.mouse.move(from.x, from.y); await page.mouse.down();
          await page.mouse.move(from.x + 20, from.y - 30, { steps: 3 });
          const dead = await deadPoint();
          await page.mouse.move(dead.x, dead.y, { steps: 8 });
          if (gi === 0) await page.screenshot({ path: path.join(outdir, '09_nozone.png') });
          await page.mouse.up(); await sleep(260);
          const after = await page.evaluate(() => ({ n: window.__arena.ui.g.players[window.__arena.ui.you].hand.length, ghost: !!document.querySelector('#drag .ghost'), lifted: !!document.querySelector('.hc.lifted'), type: window.__arena.ui.pending && window.__arena.ui.pending.type }));
          if (after.ghost || after.lifted) {            // the spring-back takes ~0.2s; on a busy machine give it longer before calling it stuck
            const t0 = Date.now();
            await page.waitForFunction(() => !document.querySelector('#drag .ghost') && !document.querySelector('.hc.lifted'), null, { timeout: 3000 }).catch(() => {});
            const late = await page.evaluate(() => ({ ghost: !!document.querySelector('#drag .ghost'), lifted: !!document.querySelector('.hc.lifted') }));
            console.log(`spring-back slow: cleared=${!late.ghost && !late.lifted} after ${Date.now() - t0}ms more`);
            Object.assign(after, late);
          }
          if (after.n !== before || after.ghost || after.lifted || after.type !== 'choose') throw new Error('spring-back failed ' + JSON.stringify(after));
          stats.springs++;
          continue;
        }
        if (Math.random() < 0.75) {
          const exact = await page.evaluate((a) => window.__arena.zoneFor(a), a);
          const zone = a[0] === 'play' && Math.random() < 0.65 ? 'mymat' : exact;   // mostly the play area, sometimes the exact spot
          const to = await zonePoint(zone);
          if (!to) throw new Error(`zone ${zone} for ${a.join(' ')} not reachable by pointer`);
          await page.mouse.move(0, 0);
          const shot = gi === 0 && tag('drag') ? path.join(outdir, '08_drag.png') : null;
          await dragTo(await cardPoint(sel), to, shot);
          stats.drags++;
          const menu = await page.$(`.pop button[data-do="act"][data-v="${pick}"]`);
          if (menu) { if (!shots.has('dropmenu') && shots.add('dropmenu')) await page.screenshot({ path: path.join(outdir, '10_dropmenu.png') }); await menu.click(); stats.menus++; }
        } else {
          await page.click(sel, { force: true });
          if (gi === 0 && tag('popover')) await page.screenshot({ path: path.join(outdir, '06_popover.png') });
          await page.click(`.pop button[data-do="act"][data-v="${pick}"]`);
          stats.clicks++;
        }
      } else {
        const key = { punch: 'punch', swing: 'swing', free_swing: 'swing', spec: 'spec', quest: 'quest', claim: 'claim', pass: 'pass', draw: 'draw' }[a[0]];
        await page.click(`[data-do="core"][data-v="${key}"]`);
        const menu = await page.$('.pop button[data-do="act"]');
        if (menu) await page.click(`.pop button[data-do="act"][data-v="${pick}"]`);
      }
    }
    const end = await page.evaluate(() => { const g = window.__arena.ui.g; return { winner: g.winner, you: window.__arena.ui.you, reason: g.end_reason, turn: g.turn }; });
    await page.waitForFunction(() => window.__arena.ui.screen === 'result', null, { timeout: 15000 });   // then the results and the GP
    end.gp = await page.evaluate(() => { const r = window.__arena.ui.result; return [r.before, r.pay, r.after]; });
    results.push(end);
    if (gi === 0) { await page.waitForTimeout(2600); await page.screenshot({ path: path.join(outdir, '07_over.png') }); }
    await page.click('[data-do="menu"]');
  }
  await browser.close();
  console.log(JSON.stringify(results));
  console.log(JSON.stringify(stats));
  if (errors.length) { console.log('ERRORS:\n' + [...new Set(errors)].slice(0, 20).join('\n')); process.exit(1); }
  console.log('no page errors');
})();
