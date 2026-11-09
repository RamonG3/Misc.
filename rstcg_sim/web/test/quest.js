// Quest flow + resume: play Quest Melee, start quests and do steps, reload mid-game, resume, compare state.
const { chromium } = require('playwright');
const path = require('path');
const shots = '/tmp/claude-0/-home-claude/9151da06-491f-5e14-a152-5d4aba553a1e/scratchpad/shots';
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  await page.goto('file://' + path.join(__dirname, 'page.html'));
  await page.click('.deckbtn[data-v="Quest Melee"]');
  await page.selectOption('#botdeck', 'Lean Mage');
  await page.click('button[data-do="seat"][data-v="first"]');
  await page.click('button[data-do="start"]');
  await page.click('button[data-do="speed"]');
  const clickText = (sel, re) => page.evaluate(([sel, src]) => {
    const r = new RegExp(src);
    const el = [...document.querySelectorAll(sel)].find((e) => r.test(e.innerText));
    if (el) { el.click(); return el.innerText.split('\n')[0]; }
    return null;
  }, [sel, re.source]);
  const counts = { start: 0, step: 0, other: 0 }; let shot = false; let reloaded = false; let before = null;
  for (let step = 0; step < 500; step++) {
    if (await page.$('.result-title')) break;
    if (await page.$('button[data-do="react"]')) { await clickText('button[data-do="react"]', /Take it/); continue; }
    if (await page.$('.card.discardable')) { await page.click('.card.discardable'); continue; }
    if (!(await page.$('button[data-do="act"]'))) { await page.waitForTimeout(100); continue; }
    // reload once mid-game and resume
    if (!reloaded && counts.step >= 3) {
      before = await page.evaluate(() => ({ text: document.querySelector('.zone-you .zone-head').innerText, hand: document.querySelector('#hand').innerText, log: document.querySelectorAll('#log > div').length }));
      await page.reload();
      await page.click('button[data-do="resume"]');
      await page.waitForTimeout(400);
      const after = await page.evaluate(() => ({ text: document.querySelector('.zone-you .zone-head').innerText, hand: document.querySelector('#hand').innerText, log: document.querySelectorAll('#log > div').length }));
      console.log('resume matches:', before.text === after.text && before.hand === after.hand, 'log lines', before.log, '->', after.log);
      reloaded = true;
      continue;
    }
    if (await page.$('button[data-do="questpick"]')) {
      await page.click('button[data-do="questpick"]');
      if (!shot) { shot = true; await page.screenshot({ path: `${shots}/quest-pick.png` }); }
      await page.click('button[data-do="act"]');
      counts.step++; continue;
    }
    if (await clickText('button[data-do="act"]', /^Quest/)) { counts.step++; continue; }
    if (await clickText('button[data-do="act"]', /^Claim/)) { counts.claim = 1; continue; }
    const qcard = await page.evaluate(() => {
      const el = [...document.querySelectorAll('.card.playable')].find((c) => /QUEST/.test(c.innerText));
      if (el) { el.click(); return true; } return false;
    });
    if (qcard) { await clickText('button[data-do="act"]', /^Start/); counts.start++; continue; }
    if (await clickText('button[data-do="act"]', /^(Swing|Play|Equip)/)) { counts.other++; continue; }
    const card = await page.evaluate(() => { const el = document.querySelector('.card.playable'); if (el) { el.click(); return true; } return false; });
    if (card) { const t = await clickText('button[data-do="act"]', /.*/); if (t) { counts.other++; continue; } }
    await clickText('button[data-do="act"]', /^(Punch|End turn)/);
  }
  const result = await page.$eval('.dialog', (d) => d.innerText).catch(() => '(none)');
  const qlog = await page.$$eval('#log > div', (els) => els.map((e) => e.innerText).filter((t) => /Quest|quest|completes|QP|Romeo|Goblin|Doric/.test(t)).slice(0, 12));
  console.log(JSON.stringify(counts));
  console.log('quest log:', qlog);
  console.log('result:', result.replace(/\n+/g, ' | '));
  console.log('errors:', errors.length ? errors : 'none');
  await browser.close();
})();
