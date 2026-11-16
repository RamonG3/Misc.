// Every browser regression test against web/dist/arena.html (and the app in web/dist/netlify), one after
// another. Build first:
//   python web/build_arena.py && python web/build_netlify.py && node web/test/ui/run_all.js
// A test passes when it exits cleanly and prints "no errors" / "no page errors" (or, for the ones that run in
// node alone, "all passed"). Takes about a quarter of an hour.
const { spawnSync } = require('child_process');
const path = require('path');
const T = (f) => path.join(__dirname, f);
const tests = [
  ['duels by mouse (arena_play)', [path.join(__dirname, '..', 'arena_play.js'), '2']],
  ['menus end to end (flow)', [T('flow.js')]],
  ['controller (padtest)', [T('padtest.js')]],
  ['audio (audioflow)', [T('audioflow.js')]],
  ['cape ticks (capetick)', [T('capetick.js')]],
  ['tutorial, PC (tuttest)', [T('tuttest.js'), 'pc']],
  ['tutorial, phone (tuttest)', [T('tuttest.js'), 'phone']],
  ['deck wizard, PC (wizui)', [T('wizui.js')]],
  ['deck wizard, phone (wizui)', [T('wizui.js'), '892', '412']],
  ['fingers on a phone (touch)', [T('touch.js')]],
  ['every screen shape (layouts)', [T('layouts.js')]],
  ['installable app (pwa)', [T('pwa.js')]],
  ['online duels, two browsers (online)', [T('online.js'), '40']],
  ['the cloud save, in node (cloud_test)', [path.join(__dirname, '..', 'cloud_test.js'), '3000']],
  ['account calls, in node (accounts_test)', [path.join(__dirname, '..', 'accounts_test.js')]],
  ['accounts, several devices (accounts)', [T('accounts.js')]],
];
let bad = 0;
for (const [name, args] of tests) {
  const t0 = Date.now();
  const r = spawnSync(process.execPath, args, { encoding: 'utf8', timeout: 600000 });
  const out = (r.stdout || '') + (r.stderr || '');
  const ok = r.status === 0 && /no (page )?errors|all passed/.test(out) && !/ERRORS|Error:|FAILED/.test(out.replace(/no (page )?errors/g, ''));
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  (${((Date.now() - t0) / 1000).toFixed(0)} s)`);
  if (!ok) console.log(out.split('\n').slice(-15).map((l) => '      ' + l).join('\n'));
}
console.log(bad ? `\n${bad} failed` : '\nall passed');
process.exit(bad ? 1 : 0);
