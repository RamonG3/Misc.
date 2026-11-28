// Recorded sounds: a spell that has a recording makes it when it is cast, and a rune that has one (its
// runecrafting sound) makes it when it is played.
// node web/test/ui/spellsound.js      ends "all passed" (about a minute); needs a build with the sounds packed
//
// Checks that every packed sound decodes and plays; that your cast and the bot's both make the spell's own sound
// (and not the synthesized one on top); that a cast a Protect prayer turns away still sounds; that a spell with
// no recording, a spell discarded in Varrock and Sound effects off stay as they were; that a resumed duel doesn't
// cast its old spells again; and that reduced motion, which shows no flights, still plays a cast. Runes: yours
// and the bot's make their sound as they land (in place of the card's thud), a combination rune keeps the thud,
// and reduced motion still plays it. Prayers (2026-11-22): a buff prayer, yours or the bot's, makes its sound as it
// goes up (in place of the thud); a Protect prayer, yours or the bot's, makes its own as it blocks (in place of the
// synthesized block); a level-1 prayer plays the sound it borrows; reduced motion still plays both; and the
// prayers sit a little under the spells at the volume the game plays them (PRAYER_V). Summons (Necromancy, 2026-11-27):
// Conjuration.ogg, at the volume the game plays it (SUMMON_V, read from the page), sits a little under the spells too.
const path = require('path');
const fs = require('fs');
const http = require('http');
const { chromium } = require('playwright');
const SHOTS = path.join(__dirname, 'shots', 'spellsound');
fs.mkdirSync(SHOTS, { recursive: true });
let failures = 0, checks = 0;
const ok = (c, m) => { checks += 1; if (!c) { failures += 1; console.log('FAIL ' + m); } return c; };

(async () => {
  // served over http: the resume check reloads the page, and a file:// page can lose its saved duel on reload
  const site = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(fs.readFileSync(path.join(__dirname, '..', '..', 'dist', 'arena.html')));
  });
  await new Promise((r) => site.listen(0, '127.0.0.1', r));
  const PAGE = `http://127.0.0.1:${site.address().port}/arena.html`;
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const errors = [];

  const open = async ({ motion = 'full', you = 'Air Mage', bot = 'Melee' } = {}) => {
    const ctx = await b.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error' && !/127\.0\.0\.1:9|ERR_CONNECTION_REFUSED/.test(m.text())) errors.push(m.text()); });
    await page.addInitScript(([motion, you, bot]) => {
      window.RSTCG_ONLINE_SERVER = { url: 'http://127.0.0.1:9', key: 'none' };
      if (!sessionStorage.getItem('init')) {
        localStorage.clear(); sessionStorage.setItem('init', '1');
        localStorage.setItem('rstcg-arena-setup', JSON.stringify({ youDeck: you, botDeck: bot, level: 'easy' }));
      }
      localStorage.setItem('rstcg-speed', '"fast"');
      localStorage.setItem('rstcg-settings', JSON.stringify({ motion, music: 'off', sfx: 'high', tips: 'off' }));
    }, [motion, you, bot]);
    await page.goto(PAGE);
    await page.waitForFunction(() => window.__arena);
    await page.waitForTimeout(200);
    if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
    await page.waitForTimeout(300);
    return page;
  };
  // the sounds the page asks for, by name: recorded clips (RSAudio.clipLog) and synthesized effects (window.__sfx)
  const listen = (page) => page.evaluate(() => {
    if (window.__sfx) return;
    window.__sfx = [];
    const was = window.RSAudio.sfx;
    window.RSAudio.sfx = (n, o) => { window.__sfx.push(n); return was(n, o); };
  });
  // (the log of clips keeps only the last 40, so each is numbered: `n` is how many had been started by then)
  const heard = (page) => page.evaluate(() => { const log = window.RSAudio.clipLog; return { clips: log.map((c) => c.name), n: log.length ? log[log.length - 1].n : 0, log, sfx: window.__sfx.slice() }; });
  // wait until it is yours to choose; a discard down to the hand limit or a reaction on the way is answered plainly
  const myTurn = async (page) => {
    for (let k = 0; k < 40; k++) {
      await page.waitForFunction(() => { const a = window.__arena, u = a.ui; return u.screen !== 'duel' || (u.pending && a.online.idle()); }, null, { timeout: 90000 });
      const type = await page.evaluate(() => { const u = window.__arena.ui; return u.screen !== 'duel' ? 'over' : u.pending.type; });
      if (type === 'choose' || type === 'over') return type;
      // (a reaction is taken only when a check asks for one: window.__youReact names the card)
      await page.evaluate(() => { const u = window.__arena.ui, p = u.pending; p.resolve(p.type === 'discard' ? u.g.players[u.you].hand[0] : p.type === 'react' && window.__youReact && p.opts.includes(window.__youReact) ? window.__youReact : null); });
      await page.waitForTimeout(250);
    }
    throw new Error('never got back to a choice');
  };
  const startDuel = async (page) => {
    await page.click('[data-do="play"]'); await page.click('[data-do="start"]');
    await page.waitForTimeout(700);
    if (await page.locator('[data-do="skipintro"]').count()) await page.click('[data-do="skipintro"]');
    await myTurn(page);
    await listen(page);
  };
  // do one thing on your turn (after `setup` has arranged the table), then report what was heard while it played out
  const act = async (page, setup, action) => {
    const before = await heard(page);
    await page.evaluate(([setup, action]) => {
      const a = window.__arena, u = a.ui, g = u.g, me = g.players[u.you], them = g.players[1 - u.you];
      const give = (p, cards, mats) => { for (const c of cards || []) p.hand.push(c); for (const [m, n] of Object.entries(mats || {})) p.materials[m] = (p.materials[m] || 0) + n; };
      give(me, setup.hand, setup.mats); give(them, setup.botHand, setup.botMats);
      if (setup.location) { g.location = setup.location; g.location_owner = u.you; }
      a.resnap();                                         // the animation starts from the table as just arranged
      u.pending.resolve(action);
    }, [setup, action]);
    await page.waitForTimeout(250);
    await myTurn(page);
    await page.waitForTimeout(400);                       // (a clip that had to be decoded starts a moment after it is asked for)
    const after = await heard(page);
    return { clips: after.log.filter((c) => c.n > before.n).map((c) => c.name), sfx: after.sfx.slice(before.sfx.length) };
  };

  // ---------- every packed sound decodes and plays ----------
  const page = await open();
  await page.mouse.click(1500, 600);                      // the first click starts the audio
  await page.waitForFunction(() => window.RSAudio.ready);
  const names = await page.evaluate(() => window.RSAudio.clips);
  // The sounds live in art/sounds on Ramon's PC, not in the repo. Where that folder is, a build without them is a
  // mistake; where it isn't, there is nothing to pack, and only the checks that need no recording are made.
  const folder = process.env.RSTCG_SOUNDS || path.join(__dirname, '..', '..', '..', 'art', 'sounds');
  if (!names.length && fs.existsSync(folder)) ok(false, `${folder} has sounds but this build has none packed (python web/arena_sounds.py, then build again)`);
  console.log(names.length ? `${names.length} sounds packed: ${names.join(', ')}` : 'no spell sounds are packed in this build (no art/sounds folder here): checking only that spells keep their synthesized sound');
  const cards = await page.evaluate(() => Object.keys(window.__arena.CARDS));
  ok(names.every((n) => cards.includes(n)), `a packed sound has no card: ${names.filter((n) => !cards.includes(n)).join(', ')}`);
  await page.evaluate(() => window.RSAudio.warm());
  const played = {};
  let log = [];
  for (let k = 0; k < names.length; k += 20) {           // in twenties: the clip log keeps only the last 40 (44 sounds since Necromancy)
    for (const n of names.slice(k, k + 20)) {
      await page.evaluate((n) => window.RSAudio.clip(n, { v: 0.2 }), n);
      await page.waitForTimeout(40);
    }
    await page.waitForTimeout(300);
    log = await page.evaluate(() => window.RSAudio.clipLog);
    for (const c of log) played[c.name] = c.secs;
  }
  const bad = names.filter((n) => !(played[n] > 0.15 && played[n] < 8));
  ok(!bad.length, `these sounds didn't decode, or are an odd length: ${bad.map((n) => `${n} (${played[n]})`).join(', ')}`);
  if (names.length) console.log('lengths, seconds: ' + names.map((n) => `${n} ${played[n]}`).join(', '));
  // none of them far too quiet or too loud next to the rest of the game (measured through the mixer, unheard;
  // the spells were made louder on 2026-11-18, so the ceiling went up from -16 dB to -12)
  if (names.length) {
    // a summon's sound is measured at the volume the game plays it (SUMMON_V): Conjuration.ogg is recorded far louder
    const lv = await page.evaluate(async () => { const A = window.RSAudio, C = window.__arena.CARDS, o = {}; for (const n of A.clips) o[n] = await A.measure(() => A.clip(n, C[n].wclass === 'summon' || C[n].kind === 'conduit' ? { v: window.__arena.clipV(n) } : undefined)); o.magic = await A.measure(() => A.sfx('magic')); o.hit = await A.measure(() => A.sfx('hit', { style: 'magic' })); return o; });
    const off = names.filter((n) => !(lv[n].rms > -40 && lv[n].rms < -12 && lv[n].peak < -1));
    ok(!off.length, `these sounds are far too quiet or too loud: ${off.map((n) => `${n} (${lv[n].rms} dB, peak ${lv[n].peak})`).join(', ')}`);
    const mean = names.reduce((s, n) => s + lv[n].rms, 0) / names.length;
    console.log(`loudness through the mixer: the recordings average ${mean.toFixed(1)} dB (from ${Math.min(...names.map((n) => lv[n].rms))} to ${Math.max(...names.map((n) => lv[n].rms))}); the synthesized spell sound ${lv.magic.rms} dB, a hit ${lv.hit.rms} dB`);
    ok((await page.evaluate(() => window.RSAudio.clipLog)).length === log.length, 'measuring a sound counted as playing it');
    // the prayers at the volume the game plays them (PRAYER_V in arena.src.html, 2.5): a little under the spells
    const kinds = await page.evaluate((ns) => Object.fromEntries(ns.map((n) => [n, window.__arena.CARDS[n].kind])), names);
    const prayers = names.filter((n) => kinds[n] === 'buff' || kinds[n] === 'prayer'), spells = names.filter((n) => kinds[n] === 'spell');
    if (prayers.length && spells.length) {
      const pl = await page.evaluate(async (ps) => { const A = window.RSAudio, o = {}; for (const n of ps) o[n] = await A.measure(() => A.clip(n, { v: 2.5 })); return o; }, prayers);
      const avg = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
      const pm = avg(prayers.map((n) => pl[n].rms)), sm = avg(spells.map((n) => lv[n].rms));
      console.log(`prayers as played: ${pm.toFixed(1)} dB on average (${prayers.map((n) => `${n} ${pl[n].rms}`).join(', ')}); spells ${sm.toFixed(1)} dB`);
      ok(pm < sm && pm > sm - 6, `the prayers should sit a little under the spells (prayers ${pm.toFixed(1)} dB, spells ${sm.toFixed(1)} dB)`);
      ok(prayers.every((n) => pl[n].peak < -1), `a prayer peaks too high: ${prayers.filter((n) => pl[n].peak >= -1).map((n) => `${n} ${pl[n].peak}`).join(', ')}`);
    }
    // the summons (Necromancy): one recording, Conjuration.ogg, at SUMMON_V, a little under the spells like the prayers
    const summons = await page.evaluate((ns) => ns.filter((n) => window.__arena.CARDS[n].wclass === 'summon' || window.__arena.CARDS[n].kind === 'conduit'), names);
    if (summons.length && spells.length) {
      const sm = spells.reduce((a, n) => a + lv[n].rms, 0) / spells.length, su = summons.reduce((a, n) => a + lv[n].rms, 0) / summons.length;
      console.log(`summons as played: ${su.toFixed(1)} dB on average (${summons.map((n) => `${n} ${lv[n].rms}`).join(', ')}); spells ${sm.toFixed(1)} dB`);
      ok(su < sm && su > sm - 6, `the summons should sit a little under the spells (summons ${su.toFixed(1)} dB, spells ${sm.toFixed(1)} dB)`);
    }
  }
  ok(await page.evaluate(() => window.RSAudio.clip('Bronze Dagger')) === false && await page.evaluate(() => window.RSAudio.clip('Smoke Flurry')) === false, 'a card with no recording claimed to have one');
  const have = (n) => names.includes(n);

  // ---------- in a duel: the bot is told what to do, so each case is exactly one move ----------
  await startDuel(page);
  await page.evaluate(() => {
    const u = window.__arena.ui, bot = u.g.agents[1 - u.you];
    window.__bot = { next: [], react: null };
    bot.choose = async (g, i, legal) => window.__bot.next.shift() || ['pass'];
    bot.react = async (g, j, amount, style, src, opts) => (window.__bot.react && opts.includes(window.__bot.react) ? window.__bot.react : null);
  });
  let h;
  if (have('Air Strike')) {
    h = await act(page, { hand: ['Air Strike'], mats: { 'Air Rune': 1 } }, ['play', 'Air Strike']);
    ok(h.clips.join() === 'Air Strike', `you cast Air Strike: its own sound, once (heard ${JSON.stringify(h)})`);
    ok(!h.sfx.includes('magic') && h.sfx.includes('hit'), `and not the synthesized spell sound on top, but still the hit (heard ${h.sfx.join(' ')})`);
    await page.screenshot({ path: path.join(SHOTS, 'after_cast.png') });
  }
  h = await act(page, { hand: ['Smoke Flurry'], mats: { 'Air Rune': 1, 'Fire Rune': 2 } }, ['play', 'Smoke Flurry']);
  ok(!h.clips.length && h.sfx.includes('magic'), `a spell with no recording keeps the synthesized sound (heard ${JSON.stringify(h)})`);
  if (have('Superheat')) {
    h = await act(page, { hand: ['Superheat'], mats: { 'Fire Rune': 2 } }, ['play', 'Superheat']);
    ok(h.clips.join() === 'Superheat' && !h.sfx.includes('magic'), `Superheat makes its own sound (heard ${JSON.stringify(h)})`);
  }
  if (have('Varrock Teleport')) {
    h = await act(page, { hand: ['Varrock Teleport'] }, ['play', 'Varrock Teleport']);
    ok(h.clips.join() === 'Varrock Teleport', `Varrock Teleport makes its own sound (heard ${JSON.stringify(h)})`);
  }
  if (have('Air Bolt')) {
    h = await act(page, { hand: ['Air Bolt'], location: 'Varrock' }, ['varrock', 'Air Bolt']);
    ok(!h.clips.length, `a spell discarded to rest in Varrock is not a cast (heard ${JSON.stringify(h)})`);
    // the bot casts: its sound plays as its card comes down
    await page.evaluate(() => { window.__bot.next = [['play', 'Air Bolt']]; });
    h = await act(page, { botHand: ['Air Bolt'], botMats: { 'Air Rune': 2, 'Chaos Rune': 1 } }, ['pass']);
    ok(h.clips.join() === 'Air Bolt' && !h.sfx.includes('magic'), `the bot casts Air Bolt: the same sound (heard ${JSON.stringify(h)})`);
  }
  if (have('Air Strike')) {
    // a cast that Protect from Magic turns away still sounds: the cast worked, the landing didn't
    await page.evaluate(() => { window.__bot.react = 'Protect from Magic'; });
    h = await act(page, { hand: ['Air Strike'], mats: { 'Air Rune': 1 }, botHand: ['Protect from Magic'] }, ['play', 'Air Strike']);
    if (have('Protect from Magic')) ok(h.clips.join() === 'Air Strike,Protect from Magic' && !h.sfx.includes('block') && !h.sfx.includes('hit'), `a blocked cast still sounds, then the bot's Protect prayer makes its own sound in place of the block (heard ${JSON.stringify(h)})`);
    else ok(h.clips.join() === 'Air Strike' && h.sfx.includes('block') && !h.sfx.includes('hit'), `a blocked cast still sounds, then the block (heard ${JSON.stringify(h)})`);
    await page.evaluate(() => { window.__bot.react = null; });
    // Sound effects off: nothing is played, and nothing breaks
    await page.evaluate(() => window.RSAudio.volumes({ sfx: 0 }));
    h = await act(page, { hand: ['Air Strike'], mats: { 'Air Rune': 1 } }, ['play', 'Air Strike']);
    ok(!h.clips.length, `with Sound effects off no recording is played (heard ${JSON.stringify(h)})`);
    await page.evaluate(() => window.RSAudio.volumes({ sfx: 0.8 }));
  }
  // ---------- runes: the runecrafting sound as the card lands, in place of its thud ----------
  if (have('Air Rune')) {
    h = await act(page, { hand: ['Air Rune'] }, ['play', 'Air Rune']);
    ok(h.clips.join() === 'Air Rune' && !h.sfx.includes('card'), `you play an Air Rune: its crafting sound, not the thud (heard ${JSON.stringify(h)})`);
    h = await act(page, { hand: ['Dust Rune'] }, ['play', 'Dust Rune']);
    ok(!h.clips.length && h.sfx.includes('card'), `a combination rune has no recording and keeps the thud (heard ${JSON.stringify(h)})`);
  }
  if (have('Fire Rune')) {
    await page.evaluate(() => { window.__bot.next = [['play', 'Fire Rune']]; });
    h = await act(page, { botHand: ['Fire Rune'] }, ['pass']);
    ok(h.clips.join() === 'Fire Rune', `the bot plays a Fire Rune: the same sound (heard ${JSON.stringify(h)})`);
  }
  // ---------- prayers: a buff prayer as it goes up, a Protect prayer as it blocks ----------
  if (have('Piety')) {
    h = await act(page, { hand: ['Piety'] }, ['play', 'Piety']);
    ok(h.clips.join() === 'Piety' && !h.sfx.includes('card'), `you pray Piety: its own sound, not the thud (heard ${JSON.stringify(h)})`);
  }
  if (have('Sharp Eye')) {
    // a level-1 prayer plays the sound it borrows (Eagle Eye's), under its own name; here the bot prays it
    await page.evaluate(() => { window.__bot.next = [['play', 'Sharp Eye']]; });
    h = await act(page, { botHand: ['Sharp Eye'] }, ['pass']);
    // (only the clip: the end of a turn sends other cards to the discard pile with a thud of their own, as in the rune case)
    ok(h.clips.join() === 'Sharp Eye', `the bot prays Sharp Eye: its (borrowed) sound (heard ${JSON.stringify(h)})`);
  }
  if (have('Protect from Magic') && have('Air Bolt')) {
    // the bot casts at you and your Protect from Magic blocks it: the cast's sound, then the prayer's
    await page.evaluate(() => { window.__bot.next = [['play', 'Air Bolt']]; window.__youReact = 'Protect from Magic'; });
    h = await act(page, { hand: ['Protect from Magic'], botHand: ['Air Bolt'], botMats: { 'Air Rune': 2, 'Chaos Rune': 1 } }, ['pass']);
    await page.evaluate(() => { window.__youReact = null; });
    ok(h.clips.join() === 'Air Bolt,Protect from Magic' && !h.sfx.includes('block') && !h.sfx.includes('hit'), `your Protect prayer blocks: its own sound in place of the block (heard ${JSON.stringify(h)})`);
    await page.screenshot({ path: path.join(SHOTS, 'after_your_block.png') });
  }
  await page.context().close();

  // ---------- reduced motion: no flights, but a cast still sounds ----------
  if (have('Air Strike')) {
    const calm = await open({ motion: 'reduced' });
    await calm.mouse.click(1500, 600);
    await calm.waitForFunction(() => window.RSAudio.ready);
    await startDuel(calm);
    // the bot only passes here too: left to play, it once put up a prayer (which has a sound) while a check listened
    await calm.evaluate(() => { const u = window.__arena.ui, bot = u.g.agents[1 - u.you]; bot.choose = async () => ['pass']; bot.react = async () => null; });
    h = await act(calm, { hand: ['Air Strike'], mats: { 'Air Rune': 1 } }, ['play', 'Air Strike']);
    ok(h.clips.join() === 'Air Strike', `reduced motion still plays a cast (heard ${JSON.stringify(h)})`);
    h = await act(calm, { hand: ['Air Bolt'], location: 'Varrock' }, ['varrock', 'Air Bolt']);
    ok(!h.clips.length, `reduced motion: a discard is still not a cast (heard ${JSON.stringify(h)})`);
    if (have('Water Rune')) {
      h = await act(calm, { hand: ['Water Rune'] }, ['play', 'Water Rune']);
      ok(h.clips.join() === 'Water Rune', `reduced motion still plays a rune's sound (heard ${JSON.stringify(h)})`);
    }
    if (have('Rigour')) {
      h = await act(calm, { hand: ['Rigour'] }, ['play', 'Rigour']);
      ok(h.clips.join() === 'Rigour', `reduced motion still plays a prayer going up (heard ${JSON.stringify(h)})`);
    }
    if (have('Protect from Magic')) {
      await calm.evaluate(() => {
        const u = window.__arena.ui, bot = u.g.agents[1 - u.you];
        bot.choose = async () => ['pass'];
        bot.react = async (g, j, amount, style, src, opts) => (opts.includes('Protect from Magic') ? 'Protect from Magic' : null);
      });
      h = await act(calm, { hand: ['Air Strike'], mats: { 'Air Rune': 1 }, botHand: ['Protect from Magic'] }, ['play', 'Air Strike']);
      ok(h.clips.join() === 'Air Strike,Protect from Magic', `reduced motion still plays a Protect prayer blocking (heard ${JSON.stringify(h)})`);
    }
    await calm.context().close();
  }

  // ---------- a real duel, played by its rules, then resumed ----------
  if (have('Air Strike') && have('Air Bolt') && have('Air Blast')) {
    const real = await open();
    await real.mouse.click(1500, 600);
    await real.waitForFunction(() => window.RSAudio.ready);
    await startDuel(real);
    // play runes, cast what can be cast, otherwise draw; stop after the first cast
    // (counts spells cast, not every recording: the runes played on the way have sounds of their own)
    const spellsCast = (pg) => pg.evaluate(() => { const C = window.__arena.CARDS; return window.RSAudio.clipLog.filter((c) => C[c.name].kind === 'spell').length; });
    const playOn = async (pg, casts) => {
      for (let k = 0; k < 80; k++) {
        const n = await spellsCast(pg);
        if (n >= casts) return n;
        if (await pg.evaluate(() => window.__arena.ui.screen) !== 'duel') return n;
        await pg.evaluate(() => {
          const a = window.__arena, u = a.ui, C = a.CARDS, L = u.pending.legal;
          const pick = L.find((x) => x[0] === 'play' && C[x[1]].kind === 'spell') || L.find((x) => x[0] === 'play' && C[x[1]].kind === 'material') || L.find((x) => x[0] === 'draw') || L.find((x) => x[0] === 'pass');
          u.pending.resolve(pick);
        });
        await pg.waitForTimeout(200);
        if (await myTurn(pg) === 'over') break;
        await pg.waitForTimeout(250);
      }
      return await spellsCast(pg);
    };
    const n1 = await playOn(real, 1);
    ok(n1 >= 1, 'a duel played by its rules never got to cast a spell');
    const first = (await heard(real)).clips;
    console.log('cast in a real duel: ' + first.join(', '));
    await real.waitForTimeout(600);
    await real.reload();
    await real.waitForFunction(() => window.__arena);
    await real.waitForTimeout(400);
    await real.mouse.click(1500, 80);                     // (starts the audio again; nothing is there to press)
    await real.waitForFunction(() => window.RSAudio.ready);
    await listen(real);
    await real.click('[data-do="resume"]');
    await myTurn(real);
    await real.waitForTimeout(600);
    h = await heard(real);
    // (the board catches up to where the duel was with one burst of the usual effects; a cast from before is not made again)
    ok(!h.clips.length, `resuming a duel doesn't cast its old spells again (heard ${JSON.stringify(h)})`);
    const n2 = await playOn(real, 1);
    ok(n2 >= 1 || await real.evaluate(() => window.__arena.ui.screen) !== 'duel', 'the resumed duel never cast again');
    await real.context().close();
  }

  await b.close();
  site.close();
  console.log(errors.length ? `${errors.length} page errors\n  ${errors.join('\n  ')}` : 'no page errors');
  if (errors.length) failures += 1;
  console.log(failures ? `${failures} FAILED (of ${checks} checks)` : `all passed (${checks} checks)`);
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
