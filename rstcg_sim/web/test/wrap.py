# Wrap the built page in the skeleton the artifact host adds, for local testing.
import os
H = os.path.dirname(os.path.abspath(__file__))
body = open(os.path.join(H, '..', 'dist', 'game.html')).read()
open(os.path.join(H, 'page.html'), 'w').write('<!doctype html><html lang="en"><head><meta charset="utf-8">'
  '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
  '<style>:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}'
  'body{margin:0;font:14px system-ui}img{max-width:100%}[hidden]{display:none!important}</style></head><body>' + body + '</body></html>')
