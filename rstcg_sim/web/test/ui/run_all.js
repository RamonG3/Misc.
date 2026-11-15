// Every browser regression test against web/dist/arena.html, one after another. Build first:
//   python web/build_arena.py && node web/test/ui/run_all.js
// A test passes when it exits cleanly and prints "no errors" / "no page errors". Takes a few minutes.
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
];
let bad = 0;
for (const [name, args] of tests) {
  const t0 = Date.now();
  const r = spawnSync(process.execPath, args, { encoding: 'utf8', timeout: 600000 });
  const out = (r.stdout || '') + (r.stderr || '');
  const ok = r.status === 0 && /no (page )?errors/.test(out) && !/ERRORS|Error:/.test(out.replace(/no (page )?errors/g, ''));
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  (${((Date.now() - t0) / 1000).toFixed(0)} s)`);
  if (!ok) console.log(out.split('\n').slice(-15).map((l) => '      ' + l).join('\n'));
}
console.log(bad ? `\n${bad} failed` : '\nall passed');
process.exit(bad ? 1 : 0);
