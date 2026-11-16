// Every menu screen at several window shapes: node web/test/ui/layouts.js
// Phone held sideways, laptop, 16:9 and 16:10 monitors, a 4:3 tablet and a 21:9 ultrawide. On each, every
// button and card you can press (outside lists that scroll) must be fully on screen and not hidden behind
// something else, and there must be no page errors.
// Shots: web/test/ui/shots/layout_<screen>_<size>.png. Prints "no errors" when all is well.
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });
const SIZES = [[892, 412, 'phone'], [800, 360, 'phone-browser'], [1366, 650, 'laptop'], [1920, 1080, '16x9'], [1440, 900, '16x10'], [1024, 768, '4x3'], [2560, 1080, '21x9']];
const fails = [];

(async () => {
  const b = await chromium.launch();
  for (const [W, H, tag] of SIZES) {
    const phone = W < 1000;
    const ctx = await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, isMobile: phone, hasTouch: phone });
    let page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', (e) => errs.push(e.message));
    await page.addInitScript(() => { window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' }; if (!sessionStorage.getItem('init')) { localStorage.clear(); sessionStorage.setItem('init', '1'); } localStorage.setItem('rstcg-speed', '"fast"'); localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' })); });
    await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
    await page.waitForFunction(() => window.__arena);
    await page.waitForTimeout(300);
    const A = (w, v) => page.evaluate(([w, v]) => window.__arena.act(w, v), [w, v]);
    await A('tutskip');
    await page.evaluate(() => { window.__arena.profile.gp = 1000; });
    const look = async (name) => {
      await page.waitForTimeout(250);
      const bad = await page.evaluate(([W, H]) => {
        const out = [];
        const modal = document.querySelector('#board .over');             // with a dialog open, only it counts
        for (const el of (modal || document).querySelectorAll(modal ? '[data-do]:not(.over), input' : '#board [data-do], #board input')) {
          if (el.closest('.scrolly') || el.closest('#trans')) continue;
          const r = el.getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          const cs = getComputedStyle(el);
          if (cs.visibility === 'hidden' || +cs.opacity < 0.05) continue;
          const what = `${el.dataset.do || el.id}${el.dataset.v ? '=' + el.dataset.v : ''}`;
          if (r.left < -1 || r.top < -1 || r.right > W + 1 || r.bottom > H + 1) { out.push(`${what} off screen`); continue; }
          const x = Math.min(W - 1, Math.max(0, r.left + r.width / 2)), y = Math.min(H - 1, Math.max(0, r.top + r.height / 2));
          const top = document.elementFromPoint(x, y);
          if (top && !el.contains(top) && !top.contains(el) && !top.closest('[data-stop]') && !el.closest('.over')) out.push(`${what} covered by ${top.className || top.tagName}`);
        }
        const sets = document.querySelector('#board .modal:has(.setrow)');       // Settings shows every row at once
        if (sets && sets.scrollHeight > sets.clientHeight + 1) out.push(`the Settings dialog scrolls (${sets.scrollHeight - sets.clientHeight} px too tall)`);
        return out;
      }, [W, H]);
      await page.screenshot({ path: path.join(SHOTS, `layout_${name}_${tag}.png`) });
      for (const x of bad) fails.push(`${tag} ${name}: ${x}`);
    };
    await A('menu'); await look('menu');
    await A('settings'); await look('settings'); await A('closemodal');
    await A('play'); await page.waitForTimeout(400); await look('pick');
    await A('opp'); await look('pick-opponent'); await A('opp');
    await A('decks'); await page.waitForTimeout(400); await look('decks');
    await A('edit'); await page.waitForTimeout(400); await look('editor');
    await A('edfilt'); await look('editor-filters'); await A('edfilt');
    await page.evaluate(() => { window.__arena.ui.ed.dirty = false; });
    await A('menu'); await A('decks'); await A('wizard'); await page.waitForTimeout(400); await look('wizard');
    await A('menu'); await A('packs'); await page.waitForTimeout(400); await look('packs');
    await A('buypack'); await page.waitForTimeout(600); await A('revealall'); await page.waitForTimeout(1200); await look('pack-open');
    await A('packsum'); await page.waitForTimeout(400); await look('pack-summary');
    // The account screen, with its states set by hand (accounts.js signs in for real, against a pretend server).
    // Each is the tallest or widest that state gets: both ways to sign in, a long address with a complaint, a
    // code with a complaint, and the two saves side by side with a note.
    const acct = (state, more) => page.evaluate(([state, more]) => { const a = window.__arena; Object.assign(a.ui.acct, { ways: { google: true, email: true }, emailErr: '', codeErr: '', note: '', confirm: null }, more, { state }); a.render(); }, [state, more || {}]);
    await A('menu'); await A('account'); await page.waitForTimeout(700);
    await acct('out'); await look('account');
    await acct('email', { email: 'a.very.long.address.for.the.box@example.com', emailErr: "Emailed codes can't reach that address yet. Sign in with Google instead." }); await look('account-email');
    await acct('code', { code: '123456', codeErr: "That code isn't right, or it has run out. Check it, or send a new one." }); await look('account-code');
    await page.evaluate(() => { const a = window.__arena; a.cloud.state.ask = { data: Object.assign(a.cloud.progress(), { gp: 123456, wins: 250 }), rev: 3, at: new Date().toISOString() }; });
    await acct('choose', { note: 'Your account changed while you were choosing. Have another look.', confirm: 'account' }); await look('account-choose');
    await page.evaluate(() => { window.__arena.cloud.state.ask = null; });
    await A('menu');
    console.log(`${tag} ${W}x${H}: ${errs.length ? 'page ERRORS ' + errs.join(' | ') : 'ok'}`);
    for (const e of errs) fails.push(`${tag}: ${e}`);
    await ctx.close();

    // signed in, with the widest name a player can have, and the server out of reach: the menu's corner and the
    // account screen's longest line
    const ctx2 = await b.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, isMobile: phone, hasTouch: phone });
    const page2 = await ctx2.newPage();
    const errs2 = [];
    page2.on('pageerror', (e) => errs2.push(e.message));
    await page2.addInitScript(() => {
      window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
      if (!sessionStorage.getItem('init')) {
        localStorage.clear(); sessionStorage.setItem('init', '1');
        localStorage.setItem('rstcg-online-v1', JSON.stringify({ name: 'WWWWWWWWWWWW', auth: { access_token: 'x', refresh_token: 'y', expires_at: Math.floor(Date.now() / 1000) + 86400, uid: '00000000-0000-4000-8000-000000000001', guest: false, email: 'a.long.address.for.the.line@example.com', provider: 'google' } }));
      }
      localStorage.setItem('rstcg-settings', JSON.stringify({ motion: 'reduced', music: 'off', sfx: 'off', tips: 'off' }));
    });
    await page2.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
    await page2.waitForFunction(() => window.__arena);
    await page2.waitForTimeout(300);
    await page2.evaluate(() => { window.__arena.act('tutskip'); window.__arena.profile.gp = 123456; window.__arena.act('menu'); });
    await page2.waitForTimeout(700);
    const main = page;
    page = page2;
    await look('menu-signed-in');
    await A('settings'); await look('settings-signed-in'); await A('closemodal');
    await A('account'); await page.waitForTimeout(700); await look('account-in');
    page = main;
    for (const e of errs2) fails.push(`${tag} signed in: ${e}`);
    await ctx2.close();
  }
  await b.close();
  if (fails.length) console.log('problems:\n  ' + fails.join('\n  '));
  console.log(fails.length ? `${fails.length} problems` : 'no errors');
  process.exit(fails.length ? 1 : 0);
})();
