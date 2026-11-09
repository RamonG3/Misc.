// Group 2 UI check: put Varrock / Grand Exchange in play, hand the player a Smith's Hammer and teleports,
// and render the action labels, narration and card text for every new card.
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  await page.goto('file://' + path.join(__dirname, 'page.html'));
  await page.waitForTimeout(300);
  await page.click('.deckbtn[data-v="Varrock Melee"]');
  await page.selectOption('#botdeck', 'Water Mage');
  await page.click('button[data-do="seat"][data-v="second"]');
  await page.click('button[data-do="start"]');
  await page.waitForFunction(() => window.__rstcg.ui.pending && window.__rstcg.ui.pending.type === 'choose', null, { timeout: 30000 });
  const out = await page.evaluate(() => {
    const T = window.__rstcg, ui = T.ui, g = ui.g, i = ui.you, p = g.players[i];
    const R = window.RSTCG, C = R.CARDS;
    const res = { cards: {}, labels: [], said: [] };
    for (const n of ["Water Strike", "Mud Flurry", "Varrock Teleport", "Varrock Teleport Tablet", "Staff of Water", "Smith's Hammer",
      "Anti-dragon Shield", "Shark", "Horvik", "Brother Jered", "Thessalia", "Wise Old Man", "Lumbridge", "Varrock",
      "Mage Arena", "Duel Arena", "Grand Exchange", "Law Rune", "Mist Rune"]) {
      const ci = T.cardInfo(C[n]); res.cards[n] = `${ci.type} | ${ci.stat} | ${ci.text.join(' ')} | ${ci.req}`;
    }
    p.materials['Mithril Bar'] = 3; p.materials['Bronze Bar'] = 2;
    p.weapon = { name: 'Mithril Scimitar', dur: 2, energy: 0, played: -1 };
    p.shield = { name: 'Kiteshield', soak: 5 };
    p.hand.push("Smith's Hammer", 'Varrock Teleport Tablet');
    if (!p.deck.includes('Varrock')) p.deck.push('Varrock');
    for (const loc of ['Varrock', 'Grand Exchange']) {
      g.location = loc; g.location_owner = 1 - i;
      for (let k = 0; k < 6; k++) p.set_lp(p.lp - 1);
      const legal = g.legal_actions(i);
      for (const a of legal) if (['varrock', 'exchange', 'play'].includes(a[0]) && (a[0] !== 'play' || ["Smith's Hammer", 'Varrock Teleport Tablet'].includes(a[1]))) {
        res.labels.push(loc + ' :: ' + T.actLabel(g, i, a).join(' — '));
        res.said.push(T.describe(g, i, a));
      }
    }
    g.location = 'Duel Arena';
    ui.pending.legal = g.legal_actions(i);
    T.render();
    return res;
  });
  for (const [n, t] of Object.entries(out.cards)) console.log(n.padEnd(24), t);
  console.log('\n' + [...new Set(out.labels)].join('\n'));
  console.log('\n' + [...new Set(out.said)].join('\n'));
  await page.click(`.hand .card[data-v="Smith's Hammer"]`).catch(() => {});
  await page.waitForTimeout(150);
  await page.screenshot({ path: '/tmp/claude-0/-home-claude/9151da06-491f-5e14-a152-5d4aba553a1e/scratchpad/g2ui.png', fullPage: true });
  console.log('errors', errors);
  await browser.close();
})();
