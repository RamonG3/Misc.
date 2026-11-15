"""The installable app for Netlify: python web/build_netlify.py  (after python web/build_arena.py)

Makes web/dist/netlify/, a folder to deploy as it is (drag it onto the site's Deploys page, or
`netlify deploy --prod --dir web/dist/netlify`):
  index.html            the built Arena, plus the lines that make it installable
  manifest.webmanifest  name, icons, landscape, full screen
  sw.js                 keeps a copy on the phone so it opens offline; picks up a new deploy when online
  _headers              tells Netlify not to cache index.html and sw.js, so updates arrive
  icon-*.png            from web/app/ (made by web/make_icons.js)

web/dist/arena.html itself is not changed: it stays the one self-contained file published as the artifact.
"""
import hashlib
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'dist', 'arena.html')
APP = os.path.join(HERE, 'app')
OUT = os.path.join(HERE, 'dist', 'netlify')
ICONS = ['icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'apple-touch-icon.png']

HEAD = """<link rel="manifest" href="manifest.webmanifest">
<meta name="theme-color" content="#0a0806">
<link rel="icon" type="image/png" href="icon-192.png">
<link rel="apple-touch-icon" href="apple-touch-icon.png">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="RS TCG">
<meta name="apple-mobile-web-app-status-bar-style" content="black">
"""
TAIL = """<script>
if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
</script>
"""
MANIFEST = {
    'name': 'RuneScape TCG Arena',
    'short_name': 'RS TCG',
    'description': 'The RuneScape trading card game: duel the bots, build decks, open packs.',
    'start_url': './',
    'scope': './',
    'id': './',
    'display': 'fullscreen',
    'display_override': ['fullscreen', 'standalone'],
    'orientation': 'landscape',
    'background_color': '#0a0806',
    'theme_color': '#0a0806',
    'categories': ['games'],
    'icons': [
        {'src': 'icon-192.png', 'sizes': '192x192', 'type': 'image/png', 'purpose': 'any'},
        {'src': 'icon-512.png', 'sizes': '512x512', 'type': 'image/png', 'purpose': 'any'},
        {'src': 'icon-maskable-512.png', 'sizes': '512x512', 'type': 'image/png', 'purpose': 'maskable'},
    ],
}
# Pages are fetched from the network first (a new deploy shows up straight away) and fall back to the saved
# copy when offline; icons and the manifest come from the saved copy. A new deploy has a new VERSION, so the
# new worker replaces the old one and clears its cache.
SW = """const VERSION = '%s';
const FILES = ['./', 'index.html', 'manifest.webmanifest', %s];
self.addEventListener('install', (e) => { self.skipWaiting(); e.waitUntil(caches.open(VERSION).then((c) => c.addAll(FILES))); });
self.addEventListener('activate', (e) => e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).then((r) => { const copy = r.clone(); caches.open(VERSION).then((c) => c.put('index.html', copy)); return r; })
      .catch(() => caches.match('index.html', { ignoreSearch: true })));
    return;
  }
  e.respondWith(caches.match(req, { ignoreSearch: true }).then((hit) => hit || fetch(req)));
});
"""
HEADERS = """/
  Cache-Control: no-cache
/index.html
  Cache-Control: no-cache
/sw.js
  Cache-Control: no-cache
/manifest.webmanifest
  Content-Type: application/manifest+json
"""


def main():
    html = open(SRC, encoding='utf-8').read()
    assert '</head>' in html and '</body>' in html, 'unexpected page shape'
    html = html.replace('</head>', HEAD + '</head>', 1)
    html = html[::-1].replace('>ydob/<', ('>ydob/<' + TAIL[::-1]), 1)[::-1]     # before the last </body>
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    with open(os.path.join(OUT, 'index.html'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(html)
    for name in ICONS:
        shutil.copy(os.path.join(APP, name), os.path.join(OUT, name))
    with open(os.path.join(OUT, 'manifest.webmanifest'), 'w', encoding='utf-8') as f:
        json.dump(MANIFEST, f, indent=2)
    version = 'rstcg-' + hashlib.sha256(html.encode('utf-8')).hexdigest()[:12]
    with open(os.path.join(OUT, 'sw.js'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(SW % (version, ', '.join("'%s'" % n for n in ICONS)))
    with open(os.path.join(OUT, '_headers'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(HEADERS)
    print('%s  (%d KB, %s)' % (OUT, len(html.encode('utf-8')) // 1024, version))


if __name__ == '__main__':
    main()
