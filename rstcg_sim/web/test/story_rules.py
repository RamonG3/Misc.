"""Story Mode's new rules, the same hand-built positions in both engines (python web/test/story_rules.py):
a locked Location can't be replaced or teleported over and nobody owns it; Draynor Manor and Moonlit Circle
change damage; Claws of Guthix and Hellfire ignore shields; Demon Fire breaks the shield that blocks it;
Bite heals its user. Prints each result from Python and from JS; they must match."""
import json, os, subprocess, sys
H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(H, '..', '..'))
from engine import Game

class Pass:                         # never reacts
    def react(self, *a): return None
    def choose(self, g, i, legal): return legal[0]
    def discard_choice(self, g, i): return g.players[i].hand[0]

def base(rules=None):
    deck = {"Bronze Bar": 60}
    g = Game([deck, deck], [Pass(), Pass()], seed=5, rules=rules)
    for p in g.players: p.hand = []
    return g

def results():
    out = {}
    g = base({"locked_location": "Draynor Manor"})
    g.players[0].hand = ["Varrock"]
    out["locked_in_play"] = [g.location, g.location_owner]
    out["can_play_location"] = any(a[0] == "play" and a[1] == "Varrock" for a in g.legal_actions(0))
    g.players[0].deck.append("Varrock"); g._teleport(0, "Varrock")
    out["teleport_blocked"] = g.location
    out["manor_damage"] = [g.compute_damage(0, 10, s)[0] for s in ("magic", "melee", "ranged")]
    g2 = base({"locked_location": "Moonlit Circle"})
    out["circle_damage"] = [g2.compute_damage(0, 10, s)[0] for s in ("magic", "melee", "ranged")]
    for spell in ("Claws of Guthix", "Hellfire", "Demon Fire", "Fire Bolt"):
        g = base()
        p, o = g.players
        p.materials.update({"Blood Rune": 2, "Fire Rune": 5, "Chaos Rune": 2, "Air Rune": 2})
        o.shield = {"name": "Kiteshield", "soak": 8}
        before = o.lp
        g._apply_card(0, spell, None)
        out[spell] = [before - o.lp, o.shield["soak"] if o.shield else None]
    g = base()
    p, o = g.players
    p.weapon = {"name": "Vampyric Claws", "dur": 4}
    for _ in range(10): p.discard.append(p.deck.pop())
    before, obefore = p.lp, o.lp
    g._apply_card(0, "Bite", None)
    out["bite"] = [p.lp - before, obefore - o.lp]
    return out

JS = r"""
const R = require('../engine.js'); const fs = require('fs'), path = require('path');
R.setData(JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'cards.json'), 'utf8')));
const Pass = { react: async () => null, choose: async (g, i, l) => l[0], discard_choice: async (g, i) => g.players[i].hand[0] };
const base = (rules) => { const d = { 'Bronze Bar': 60 }; const g = new R.Game([d, d], [Pass, Pass], 5, rules || null); for (const p of g.players) p.hand = []; return g; };
(async () => {
  const out = {};
  let g = base({ locked_location: 'Draynor Manor' });
  g.players[0].hand = ['Varrock'];
  out.locked_in_play = [g.location, g.location_owner];
  out.can_play_location = g.legal_actions(0).some((a) => a[0] === 'play' && a[1] === 'Varrock');
  g.players[0].deck.push('Varrock'); await g.teleport(0, 'Varrock');
  out.teleport_blocked = g.location;
  out.manor_damage = ['magic', 'melee', 'ranged'].map((s) => g.compute_damage(0, 10, s)[0]);
  const g2 = base({ locked_location: 'Moonlit Circle' });
  out.circle_damage = ['magic', 'melee', 'ranged'].map((s) => g2.compute_damage(0, 10, s)[0]);
  for (const spell of ['Claws of Guthix', 'Hellfire', 'Demon Fire', 'Fire Bolt']) {
    g = base(); const [p, o] = g.players;
    Object.assign(p.materials, { 'Blood Rune': 2, 'Fire Rune': 5, 'Chaos Rune': 2, 'Air Rune': 2 });
    o.shield = { name: 'Kiteshield', soak: 8 };
    const before = o.lp; await g.apply_card(0, spell, null);
    out[spell] = [before - o.lp, o.shield ? o.shield.soak : null];
  }
  g = base(); const [p, o] = g.players;
  p.weapon = { name: 'Vampyric Claws', dur: 4 };
  for (let k = 0; k < 10; k++) p.discard.push(p.deck.pop());
  const before = p.lp, ob = o.lp; await g.apply_card(0, 'Bite', null);
  out.bite = [p.lp - before, ob - o.lp];
  console.log(JSON.stringify(out));
})();
"""

if __name__ == "__main__":
    py = results()
    f = os.path.join(H, '_story_rules.js'); open(f, 'w').write(JS)
    js = json.loads(subprocess.run(['node', f], capture_output=True, text=True, check=True).stdout)
    os.remove(f)
    bad = 0
    for k in py:
        same = json.dumps(py[k]) == json.dumps(js[k])
        bad += not same
        print(f"{'ok  ' if same else 'DIFF'} {k:18s} py {py[k]}  js {js[k]}")
    print("all passed" if not bad else f"{bad} DIFFERENT")
    sys.exit(1 if bad else 0)
