// Story Mode's music, music turned off, and how loud the new tracks and the spell recordings are.
// - The Story screens play the story's own tracks (dark ones in the manor), a boss gets its own fight music.
// - Music turned off plays nothing at all, so nothing (not even an echo) can be heard (2026-11-18: the reverb
//   came straight off the master, so the echo of the music played on with the music turned off).
// - The new tracks are about as loud as the old ones; the spell recordings are well above a hit.
// Run from anywhere: node web/test/ui/storyaudio.js
const path = require('path');
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const page = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = [];
  let checks = 0;
  const ok = (c, what) => { checks += 1; if (!c) errs.push(what); };
  page.on('pageerror', (e) => errs.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });
  await page.addInitScript(() => { localStorage.clear(); localStorage.setItem('rstcg-speed', '"fast"'); localStorage.setItem('rstcg-tut-v1', '"done"'); });
  await page.goto('file://' + path.join(__dirname, '..', '..', 'dist', 'arena.html'));
  await page.waitForFunction(() => window.__arena);
  await page.waitForTimeout(200);
  if (await page.locator('[data-do="tutskip"]').count()) await page.click('[data-do="tutskip"]');
  await page.mouse.click(1500, 600); await page.waitForTimeout(500);
  const playing = () => page.evaluate(() => RSAudio.playing);

  // levels: render each song offline and compare its loudness with the old ones
  const lv = await page.evaluate(async () => {
    const r = {};
    for (const n of ['meadow', 'study', 'steel', 'story', 'storydark', 'boss']) r[n] = await RSAudio.analyse(n, 16);
    r.bossIntense = await RSAudio.analyse('boss', 16, true);
    r.steelIntense = await RSAudio.analyse('steel', 16, true);
    return r;
  });
  console.log('songs', JSON.stringify(lv));
  const db = (x) => 20 * Math.log10(x);
  for (const [n, ref] of [['story', 'meadow'], ['storydark', 'study'], ['boss', 'steel'], ['bossIntense', 'steelIntense']]) {
    ok(!lv[n].nan && lv[n].peak < 1, `${n}: clean (peak ${lv[n].peak})`);
    ok(Math.abs(db(lv[n].rms) - db(lv[ref].rms)) < 5, `${n} within 5 dB of ${ref} (${db(lv[n].rms).toFixed(1)} vs ${db(lv[ref].rms).toFixed(1)})`);
  }
  // the spell recordings against a hit
  const sp = await page.evaluate(async () => {
    const names = RSAudio.clips.filter((n) => /Strike|Bolt|Blast|Wave|Surge|Claws|Fire/.test(n)).slice(0, 6);
    await RSAudio.warm(names);
    const out = {};
    for (const n of names) out[n] = await RSAudio.measure(() => RSAudio.clip(n), 3);
    out._hit = await RSAudio.measure(() => RSAudio.sfx('hit', { n: 6 }), 2);
    return out;
  });
  console.log('spells', JSON.stringify(sp));
  const spells = Object.entries(sp).filter(([k]) => k !== '_hit').map(([, v]) => v.rms);
  const avg = spells.reduce((a, x) => a + x, 0) / spells.length;
  ok(spells.length >= 3, 'some spell recordings to measure');
  ok(avg > sp._hit.rms + 2, `spells (${avg.toFixed(1)} dB) clearly louder than a hit (${sp._hit.rms} dB)`);
  ok(Object.values(sp).every((v) => v.peak <= -1), 'no spell clip reaches full scale');

  // Story screens play the story's tracks
  await page.click('[data-do="story"]'); await page.waitForTimeout(600);
  ok(await playing() === 'Tales of Gielinor', 'the Story screen plays Tales of Gielinor: ' + await playing());
  await page.click('#board [data-do="stnew"]'); await page.waitForTimeout(500);
  ok(await page.evaluate(() => window.__arena.ui.screen) === 'scene', 'a scene');
  ok(await playing() === 'Tales of Gielinor', 'the dialogue keeps it');
  // the manor: the dark one
  await page.evaluate(() => { const u = window.__arena.ui; u.sc.scene = { scene: 'Draynor Manor cellar' }; window.__arena.render(); });
  await page.waitForTimeout(300);
  ok(await playing() === 'Shadows over Draynor', 'the cellar plays Shadows over Draynor: ' + await playing());
  // a boss duel's music
  await page.evaluate(() => RSAudio.want('boss'));
  await page.waitForTimeout(300);
  ok(await playing() === 'The Ancient Dark', 'a boss gets The Ancient Dark');
  await page.evaluate(() => { RSAudio.intense(true); RSAudio.want('story'); });
  await page.waitForTimeout(300);
  ok(await playing() === 'Tales of Gielinor', 'and back to the story');

  // music off: nothing plays (so nothing echoes); back on: it comes back
  await page.evaluate(() => RSAudio.volumes({ music: 0 }));
  await page.waitForTimeout(2200);
  ok(await playing() === null, 'music off: nothing is playing');
  await page.evaluate(() => { RSAudio.want('menu'); RSAudio.want('story'); });
  await page.waitForTimeout(300);
  ok(await playing() === null, 'music off: changing screens starts nothing');
  await page.evaluate(() => RSAudio.volumes({ music: 0.55 }));
  await page.waitForTimeout(300);
  ok(await playing() === 'Tales of Gielinor', 'music back on: the story plays again');

  console.log(`${checks} checks · ${errs.length ? 'FAILED\n  ' + errs.join('\n  ') : 'all passed'}`);
  await b.close();
  process.exit(errs.length ? 1 : 0);
})();
