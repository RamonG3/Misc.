// Scripted playthrough: node play.js [youDeck] [botDeck] [maxSteps]
const { chromium } = require('playwright');
const path = require('path');
const shots = '/tmp/claude-0/-home-claude/2339ca93-15f1-5a8b-a447-6e62a8e6cf7c/scratchpad/shots';
require('fs').mkdirSync(shots, { recursive: true });
(async () => {
  const youDeck = process.argv[2] || 'Lean Melee';
  const botDeck = process.argv[3] || 'Lean Mage';
  const maxSteps = +(process.argv[4] || 400);
  const mode = process.argv[5] || 'random';
  const tag = (process.argv[6] || 'run');
  const seen = {};
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  await page.goto('file://' + path.join(__dirname, 'page.html'));
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${shots}/setup.png`, fullPage: true });
  await page.click(`.deckbtn[data-v="${youDeck}"]`);
  await page.selectOption('#botdeck', botDeck);
  await page.click('button[data-do="seat"][data-v="first"]');
  await page.click('button[data-do="start"]');
  await page.waitForTimeout(200);
  await page.click('button[data-do="speed"]');
  let steps = 0, plays = 0, reacts = 0, discards = 0, shot = false;
  const counts = {};
  while (steps < maxSteps) {
    steps++;
    if (await page.$('.result-title')) break;
    const react = await page.$$('button[data-do="react"]');
    if (react.length) {
      if (!seen.react) { seen.react = 1; await page.screenshot({ path: `${shots}/${tag}-react.png` }); }
      await react[0].click(); reacts++; await page.waitForTimeout(50); continue;
    }
    const disc = await page.$$('.card.discardable');
    if (disc.length) {
      if (!seen.disc) { seen.disc = 1; await page.screenshot({ path: `${shots}/${tag}-discard.png` }); }
      await disc[0].click(); discards++; await page.waitForTimeout(50); continue;
    }
    const acts = await page.$$('button[data-do="act"]');
    if (!acts.length) { await page.waitForTimeout(120); continue; }
    if (!shot && plays >= 14) {
      await page.screenshot({ path: `${shots}/midgame.png`, fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.waitForTimeout(150);
      await page.screenshot({ path: `${shots}/phone.png`, fullPage: true });
      const wide = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      console.log('phone horizontal overflow px:', wide);
      await page.setViewportSize({ width: 1280, height: 900 });
      shot = true;
    }
    // Prefer a playable card, then an attack, else end the turn.
    const cards = await page.$$('.card.playable');
    const labels0 = await Promise.all(acts.map((a) => a.innerText()));
    if (mode === 'draw') {
      const d = labels0.findIndex((l) => /^Draw/.test(l));
      if (d >= 0 && Math.random() < 0.8) { await acts[d].click(); plays++; await page.waitForTimeout(40); continue; }
    }
    if (mode === 'quest') {
      const q = await page.$('button[data-do="questpick"]');
      if (q) {
        await q.click(); await page.waitForTimeout(30);
        if (!seen.quest) { seen.quest = 1; await page.screenshot({ path: `${shots}/${tag}-quest.png` }); }
        const qs = await page.$$('button[data-do="act"]'); await qs[0].click(); plays++; counts.questStep = (counts.questStep || 0) + 1; continue;
      }
      const qi = labels0.findIndex((l) => /^Quest/.test(l));
      if (qi >= 0) { await acts[qi].click(); plays++; counts.questStep = (counts.questStep || 0) + 1; continue; }
      const qc = await page.$$('.card.playable');
      for (const c of qc) {
        const t = await c.innerText();
        if (/QUEST/.test(t)) { await c.click(); const o = await page.$$('button[data-do="act"]'); await o[0].click(); plays++; counts.questStart = (counts.questStart || 0) + 1; break; }
      }
      if (counts.questStart && !seen.qs) { seen.qs = 1; continue; }
    }
    if (cards.length && Math.random() < 0.75) {
      await cards[Math.floor(Math.random() * cards.length)].click();
      await page.waitForTimeout(30);
      const opts = await page.$$('button[data-do="act"]');
      if (opts.length) {
        const label = await opts[0].innerText();
        counts[label.split('\n')[0].split(' ')[0]] = (counts[label.split('\n')[0].split(' ')[0]] || 0) + 1;
        await opts[0].click(); plays++; await page.waitForTimeout(40); continue;
      }
    }
    const labels = await Promise.all(acts.map((a) => a.innerText()));
    let k = labels.findIndex((l) => /^Claim/.test(l));
    if (k < 0) k = labels.findIndex((l) => /^(Swing|Iban|Favour|Sunder|Snapshot|Soulshot|Saradomin)/.test(l));
    if (k < 0) k = labels.findIndex((l) => /^Quest/.test(l));
    if (k < 0) k = labels.findIndex((l) => /^Punch/.test(l));
    if (k < 0) k = labels.findIndex((l) => /^End turn/.test(l));
    if (k < 0) k = 0;
    await acts[k].click(); plays++;
    await page.waitForTimeout(40);
  }
  await page.waitForTimeout(300);
  const result = await page.$eval('.dialog', (d) => d.innerText).catch(() => '(no result dialog)');
  await page.screenshot({ path: `${shots}/end.png`, fullPage: false });
  const logLines = await page.$$eval('#log > div', (els) => els.length).catch(() => 0);
  console.log(JSON.stringify({ steps, plays, reacts, discards, logLines, counts }));
  console.log('result:', result.replace(/\n+/g, ' | '));
  console.log('errors:', errors.length ? errors.slice(0, 10) : 'none');
  await browser.close();
})();
