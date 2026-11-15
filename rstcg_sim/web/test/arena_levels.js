// The difficulty levels in the real page: node web/test/arena_levels.js [path/to/arena.html]
//
// Opens the built Arena the way the artifact host serves it and checks, in a real browser:
//   - the picker is on the pre-duel screen, Guard (normal) by default, and remembers what you pick
//   - a full duel finishes at every level (your seat is played by a script) and the result names the level
//   - Champion thinks on the wait-free engine, and how long its decisions take here
//   - leaving a hard duel and resuming it: instant with its answers saved, and the same position without them
//   - a duel saved before levels existed resumes as a normal one, on the same position
//   - the picker on a phone-shaped screen (892x412)
// Screenshots go to web/test/ui/shots/levels_*.png. Ends "no page errors" and "all passed", or exits 1.
const { chromium } = require('playwright');
const fs = require('fs');
const http = require('http');
const path = require('path');

const built = process.argv[2] || path.join(__dirname, '..', 'dist', 'arena.html');
const out = path.join(__dirname, 'ui', 'shots');
fs.mkdirSync(out, { recursive: true });
// The page is served from this machine over http, wrapped as the artifact host wraps it. Not opened as a
// file: on file:// pages a test's start-up script that touches localStorage can leave the page with empty
// storage after a reload (headless Chromium), which looks exactly like a lost save.
const html = '<!doctype html><html><head><meta charset=utf8><meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover">'
  + '<style>:root{color-scheme:light}body{margin:0;padding:0;font:14px sans-serif;background:#fff;color:#000}img{max-width:100%}</style></head><body>\n'
  + fs.readFileSync(built, 'utf8') + '\n</body></html>';
const SAVE = 'rstcg-arena-duel-v1';

const quiet = () => {          // fast, calm and silent, unless the test has already set something
  if (!localStorage.getItem('rstcg-settings')) localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' }));
  if (!localStorage.getItem('rstcg-speed')) localStorage.setItem('rstcg-speed', JSON.stringify('fast'));
};
const helpers = () => {
  window.__think = [];
  const H = window.RSTCGLevels.HardBot.prototype;
  const choose = H.choose;
  H.choose = async function (g, i, legal) { const t0 = performance.now(); const a = await choose.call(this, g, i, legal); window.__think.push(performance.now() - t0); return a; };
  // play your seat with the greedy bot until the duel ends, or until you have made `stopAfter` decisions
  window.__auto = async function (maxMs, stopAfter) {
    const A = window.__arena;
    const t0 = performance.now();
    let bot = null, game = null, n = 0;
    while (performance.now() - t0 < maxMs) {
      const ui = A.ui;
      if (ui.screen === 'result') return { done: true, n };
      const p = ui.pending;
      if (p && ui.g && !ui.replaying) {
        if (stopAfter && n >= stopAfter) return { done: false, n };
        if (game !== ui.g) { bot = new window.RSTCGBot.HeuristicBot(4242); bot.setup(ui.g); game = ui.g; }
        const v = p.type === 'choose' ? bot.choose(ui.g, ui.you, p.legal.filter((a) => a[0] !== 'pass'))
          : p.type === 'react' ? bot.react(ui.g, ui.you, p.amount, p.style, p.src, p.opts) : bot.discard_choice(ui.g, ui.you);
        n++;
        p.resolve(v);
      }
      await new Promise((r) => setTimeout(r, 10));
    }
    return { done: false, n, timedOut: true };
  };
  window.__position = () => { const g = window.__arena.ui.g; return g ? JSON.stringify([g.turn, g.active, g.winner, g.players.map((p) => [p.deck, p.hand, p.discard, p.materials, p.weapon, p.cape, p.cape_prog])]) : null; };
};

let failed = 0;
const check = (ok, text) => { console.log(`${ok ? 'ok  ' : 'FAIL'}  ${text}`); if (!ok) failed++; };

(async () => {
  const server = http.createServer((req, res) => { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html); }).listen(0, '127.0.0.1');
  await new Promise((ready) => server.once('listening', ready));
  const url = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch();
  const errors = [];
  const fresh = async (viewport, scale) => {
    const context = await browser.newContext({ viewport, deviceScaleFactor: scale || 1 });
    await context.addInitScript(quiet);
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
    return { context, page };
  };
  const boot = async (page) => {
    await page.waitForFunction(() => window.__arena && document.querySelector('#stage'));
    await page.waitForTimeout(500);
    const skip = await page.$('[data-do="tutskip"]');
    if (skip) await skip.click();
    await page.evaluate(helpers);
  };
  const load = async (page) => { await page.goto(url); await boot(page); };
  const reopen = async (page) => { await page.reload(); await boot(page); };      // close the page and come back
  const toPick = async (page) => { await page.evaluate(() => window.__arena.act('play')); await page.waitForTimeout(400); };
  const pressed = (page, level) => page.$eval(`[data-do="picklevel"][data-v="${level}"]`, (b) => b.getAttribute('aria-pressed'));
  const resumed = async (page) => {             // press Resume and wait until it is your move again
    if (!await page.evaluate((key) => !!localStorage.getItem(key), SAVE)) return false;
    await page.evaluate(() => window.__arena.act('resume'));
    return page.waitForFunction(() => { const u = window.__arena.ui; return u.g && !u.replaying && u.pending; }, null, { timeout: 120000 }).then(() => true, () => false);
  };
  const desktop = { width: 1920, height: 1080 };

  // the picker
  {
    const { context, page } = await fresh(desktop);
    await load(page); await toPick(page);
    check(await page.$$eval('[data-do="picklevel"]', (b) => b.length) === 3, 'the pre-duel screen has three difficulty tiles');
    check(await pressed(page, 'normal') === 'true', 'Guard (normal) is picked by default');
    await page.screenshot({ path: path.join(out, 'levels_picker.png') });
    await page.click('[data-do="picklevel"][data-v="hard"]');
    await page.waitForTimeout(150);
    check(await pressed(page, 'hard') === 'true' && await pressed(page, 'normal') === 'false', 'clicking Champion picks it');
    await page.screenshot({ path: path.join(out, 'levels_picker_hard.png') });
    await reopen(page); await toPick(page);
    check(await pressed(page, 'hard') === 'true', 'the choice is remembered after a reload');
    await context.close();
  }

  // a full duel at each level
  for (const level of ['easy', 'normal', 'hard']) {
    const { context, page } = await fresh(desktop);
    await load(page); await toPick(page);
    await page.click(`[data-do="picklevel"][data-v="${level}"]`);
    await page.click('[data-do="start"]');
    const t0 = Date.now();
    const r = await page.evaluate(() => window.__auto(300000));
    const info = await page.evaluate((key) => { const u = window.__arena.ui; return { level: u.result && u.result.config.level, won: u.result && u.result.won, turn: u.result && u.result.turn, think: window.__think, sync: window.RSTCGLevels.fastEngine().sync, save: localStorage.getItem(key) }; }, SAVE);
    await page.waitForTimeout(2600);
    await page.screenshot({ path: path.join(out, `levels_result_${level}.png`) });
    const label = await page.evaluate(() => { const el = [...document.querySelectorAll('.lbl')].find((e) => /^against /.test(e.textContent)); return el ? el.textContent : ''; });
    const name = { easy: 'Goblin', normal: 'Guard', hard: 'Champion' }[level];
    check(r.done && info.level === level, `${level}: a full duel finishes (${((Date.now() - t0) / 1000).toFixed(0)} s, turn ${info.turn}, ${r.n} of your decisions, you ${info.won ? 'won' : 'lost'})`);
    check(label.includes(name), `${level}: the result says "${label}"`);
    check(info.save === null, `${level}: the finished duel's save is cleared`);
    if (level === 'hard') {
      const th = info.think;
      check(info.sync, 'hard: playouts run on the wait-free copy of the engine');
      console.log(`      hard thought ${th.length} times here: ${(th.reduce((a, b) => a + b, 0) / Math.max(1, th.length)).toFixed(0)} ms on average, ${Math.max(0, ...th).toFixed(0)} ms at most (it thinks during its pause)`);
    }
    await context.close();
  }

  // leaving and resuming a hard duel
  {
    const { context, page } = await fresh(desktop);
    await load(page); await toPick(page);
    await page.click('[data-do="picklevel"][data-v="hard"]');
    await page.click('[data-do="start"]');
    const r = await page.evaluate(() => window.__auto(300000, 8));
    await page.waitForTimeout(400);
    const before = await page.evaluate((key) => ({ position: window.__position(), save: JSON.parse(localStorage.getItem(key)) }), SAVE);
    await page.screenshot({ path: path.join(out, 'levels_duel_hard.png') });
    const kept = before.save && before.save.bot ? before.save.bot.length : 0;
    check(!r.done && before.save && before.save.config.level === 'hard' && kept > 0, `hard: the save holds the level, ${before.save ? before.save.decisions.length : 0} of your decisions and ${kept} of the bot's answers`);
    await reopen(page);
    let t0 = Date.now();
    let back = await resumed(page);
    const a = await page.evaluate(() => ({ position: window.__position(), searches: window.__think.length }));
    check(back && a.position === before.position && a.searches === 0, `hard: resuming is instant (${Date.now() - t0} ms, no thinking redone) and lands on the same position`);
    // the same save without the bot's answers: it thinks them through again and must land in the same place
    await page.evaluate(([key, save]) => { delete save.bot; localStorage.setItem(key, JSON.stringify(save)); }, [SAVE, before.save]);
    await reopen(page);
    t0 = Date.now();
    back = await resumed(page);
    const b = await page.evaluate(() => ({ position: window.__position(), searches: window.__think.length }));
    check(back && b.position === before.position && b.searches > 0, `hard: without its saved answers it thinks again (${b.searches} decisions, ${Date.now() - t0} ms) and lands on the same position`);
    const fin = await page.evaluate(() => window.__auto(300000));
    check(fin.done, 'hard: the resumed duel plays on to the end');
    await context.close();
  }

  // a duel saved before there were levels
  {
    const { context, page } = await fresh(desktop);
    await load(page); await toPick(page);
    await page.click('[data-do="picklevel"][data-v="normal"]');
    await page.click('[data-do="start"]');
    await page.evaluate(() => window.__auto(300000, 6));
    await page.waitForTimeout(300);
    const before = await page.evaluate((key) => { const s = JSON.parse(localStorage.getItem(key)); delete s.config.level; localStorage.setItem(key, JSON.stringify(s)); return window.__position(); }, SAVE);
    await reopen(page);
    const back = await resumed(page);
    check(back && await page.evaluate(() => window.__position()) === before, 'a duel saved before levels existed resumes as a normal one, on the same position');
    await context.close();
  }

  // the picker on a phone
  {
    const { context, page } = await fresh({ width: 892, height: 412 }, 2);
    await load(page); await toPick(page);
    await page.screenshot({ path: path.join(out, 'levels_picker_phone.png') });
    const box = await page.$eval('[data-do="picklevel"][data-v="hard"]', (b) => { const r = b.getBoundingClientRect(); return [Math.round(r.width), Math.round(r.height)]; });
    await page.click('[data-do="picklevel"][data-v="easy"]');
    await page.waitForTimeout(150);
    check(await pressed(page, 'easy') === 'true', `phone (892x412): a tile is ${box[0]}x${box[1]} px and picks on a tap`);
    await context.close();
  }

  await browser.close();
  server.close();
  console.log(errors.length ? `PAGE ERRORS (${errors.length}):\n  ` + errors.slice(0, 10).join('\n  ') : 'no page errors');
  console.log(failed || errors.length ? `${failed} check(s) FAILED` : 'all passed');
  process.exit(failed || errors.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
