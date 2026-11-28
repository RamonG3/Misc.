// Necromancy's rules, one scripted situation each, in engine.js (2026-11-27): the same as web/test/necro_rules.py.
//   node web/test/necro_rules.js             prints the JSON
//   node web/test/necro_rules.js --compare   runs the Python side too and diffs them; ends "all passed"
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const R = require('../engine.js');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const CARDS = R.CARDS;

function fresh(loc = null) {
  const g = new R.Game([{ 'Bronze Bar': 60 }, { 'Bronze Bar': 60 }], [{}, {}], 3, {});
  for (const q of g.players) { q.hand = []; q.deck = Array(20).fill('Bronze Bar'); q.discard = []; q.materials = {}; }
  g.location = loc;
  return [g, g.players[0], g.players[1]];
}
const sortObj = (o) => Object.fromEntries(Object.entries(o).filter(([, n]) => n).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
const snap = (g) => {
  const out = { loc: g.location };
  g.players.forEach((q, k) => {
    out['p' + k] = { lp: q.lp, weapon: q.weapon && { name: q.weapon.name, dur: q.weapon.dur },
      shield: q.shield && { name: q.shield.name, soak: q.shield.soak, virtual: !!q.shield.virtual },
      armor: q.armor, hand: q.hand.slice().sort(), discard: q.discard.slice().sort(),
      mats: sortObj(q.materials), cape: sortObj(q.cape_prog) };
  });
  return out;
};
const add = (m, o) => { for (const [k, n] of Object.entries(o)) m[k] = (m[k] || 0) + n; };

async function run() {
  const R_ = {};
  let g, p, o;
  [g, p, o] = fresh('City of Um');
  add(p.materials, { Ectoplasm: 1, 'Bronze Bar': 1, 'Mithril Bar': 2, 'Normal Logs': 1 });
  await g.apply_card(0, 'Skeleton Warrior', null); R_.skeleton_um = snap(g);
  await g.apply_card(0, 'Putrid Zombie', null); R_.zombie_um = snap(g);
  p.shield = { name: 'Kiteshield', soak: 8 };
  await g.apply_card(0, 'Phantom Guardian', null); R_.guardian = snap(g);
  [g, p, o] = fresh();
  p.discard = Array(5).fill('Shrimp');
  p.weapon = { name: 'Vengeful Ghost', dur: 3, energy: 0, played: 0 };
  await g.do_swing(0, 'magic'); R_.ghost_swing = snap(g);
  for (const loc of [null, 'City of Um']) {
    [g, p, o] = fresh(loc);
    o.armor = 'Wizard Robe';
    add(p.materials, { 'Spirit Rune': 1 });
    await g.apply_card(0, 'Touch of Death', null); R_[`touch_${loc === null ? 'None' : loc}`] = snap(g);
  }
  [g, p, o] = fresh();
  add(o.materials, { Ectoplasm: 2 });
  await g.apply_card(0, "Rasial's Greed", 'Ectoplasm'); R_.greed = snap(g);
  [g, p, o] = fresh();
  p.discard = ['Skeleton Warrior', 'Vengeful Ghost', 'Bronze Scimitar'];
  add(p.materials, { Ectoplasm: 1, 'Bronze Bar': 1 });
  R_.lantern_targets = g.card_actions(0, CARDS['Skull Lantern'], false).map((a) => a[2]).sort();
  await g.apply_card(0, 'Skull Lantern', 'Skeleton Warrior'); R_.lantern = snap(g);
  [g, p, o] = fresh();
  p.deck = ['Ectoplasm', 'Putrid Zombie', 'Bronze Bar', 'Ectoplasm'];
  R_.ritual_targets = g.card_actions(0, CARDS['Necromancy Ritual'], false).map((a) => a[2]).sort();
  await g.apply_card(0, 'Necromancy Ritual', 'Ectoplasm'); const a = snap(g);
  await g.apply_card(0, 'Necromancy Ritual', 'Putrid Zombie'); R_.ritual = [a, snap(g)];
  [g, p, o] = fresh();
  p.armor = 'Robes of the First Necromancer';
  p.weapon = { name: 'Skeleton Warrior', dur: 3, energy: 0, played: 0 };
  o.armor = 'Master Deathwarden Robe';
  await g.do_swing(0, 'melee'); const b = snap(g);
  add(p.materials, { 'Spirit Rune': 1 });
  await g.apply_card(0, 'Touch of Death', null); R_.robes = [b, snap(g)];
  return R_;
}

const canon = (x) => JSON.stringify(x, (k, v) => (v && typeof v === 'object' && !Array.isArray(v) ? Object.fromEntries(Object.keys(v).sort().map((kk) => [kk, v[kk]])) : v));
run().then((js) => {
  if (!process.argv.includes('--compare')) { console.log(canon(js)); return; }
  const py = JSON.parse(execFileSync('python3', [path.join(__dirname, 'necro_rules.py')], { encoding: 'utf8' }));
  const bad = Object.keys({ ...py, ...js }).filter((k) => canon(py[k]) !== canon(js[k]));
  for (const k of bad) console.log(`DIFFERS: ${k}\n  py ${canon(py[k])}\n  js ${canon(js[k])}`);
  console.log(`${Object.keys(py).length} situations · ${bad.length ? 'FAILED' : 'all passed'}`);
  process.exitCode = bad.length ? 1 : 0;
});
