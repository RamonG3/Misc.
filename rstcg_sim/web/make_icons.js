// The installed app's icons, drawn from the card back's medallion (the Crest) in the built page:
//   python web/build_arena.py && node web/make_icons.js
// Writes web/app/icon-192.png, icon-512.png, icon-maskable-512.png and apple-touch-icon.png. They are kept in
// the repo, so this only needs running again if the Crest changes.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

(async () => {
  const out = path.join(__dirname, 'app');
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  const page = await b.newPage();
  await page.goto('file://' + path.join(__dirname, 'dist', 'arena.html'));
  await page.waitForFunction(() => window.__arena && window.__arena.EMBLEM, null, { timeout: 30000 });
  const icons = await page.evaluate(async () => {
    const img = new Image();
    img.src = window.__arena.EMBLEM;
    await img.decode();
    // size, how much of it the medallion fills, rounded corners (maskable icons are cut to shape by the phone)
    const draw = (size, fill, round) => {
      const c = document.createElement('canvas'); c.width = c.height = size;
      const x = c.getContext('2d');
      if (round) { const r = size * 0.2; x.beginPath(); x.moveTo(r, 0); x.arcTo(size, 0, size, size, r); x.arcTo(size, size, 0, size, r); x.arcTo(0, size, 0, 0, r); x.arcTo(0, 0, size, 0, r); x.clip(); }
      const g = x.createRadialGradient(size / 2, size * 0.45, 0, size / 2, size / 2, size * 0.72);
      g.addColorStop(0, '#3a2c1c'); g.addColorStop(1, '#0d0a07');
      x.fillStyle = g; x.fillRect(0, 0, size, size);
      const d = size * fill;
      x.shadowColor = 'rgba(243,210,122,.45)'; x.shadowBlur = size * 0.06;
      x.drawImage(img, (size - d) / 2, (size - d) / 2, d, d);
      return c.toDataURL('image/png');
    };
    return { 'icon-192.png': draw(192, 0.86, true), 'icon-512.png': draw(512, 0.86, true), 'icon-maskable-512.png': draw(512, 0.62, false), 'apple-touch-icon.png': draw(180, 0.8, false) };
  });
  for (const [name, url] of Object.entries(icons)) {
    fs.writeFileSync(path.join(out, name), Buffer.from(url.split(',')[1], 'base64'));
    console.log('wrote web/app/' + name);
  }
  await b.close();
})();
