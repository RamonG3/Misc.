"""Story Mode's new rules, the same hand-built positions in both engines (python web/test/story_rules.py):
a locked Location can't be replaced or teleported over and nobody owns it; Draynor Manor and Moonlit Circle
change damage; Claws of Guthix and Hellfire ignore shields; Demon Fire breaks the shield that blocks it;
Bite heals its user. Chapter 3 (2026-11-22): the Kinshra Hall changes damage; Unholy Wrath heals 2 when an attack
of its user's lands; Black Bars pay for the Black Knight cards and count as bars; the Black Knight Shield can't be
broken by Shatter. Prints each result from Python and from JS; they must match."""
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
    for loc in ("Draynor Manor", "Moonlit Circle", "Wilderness"):   # a Punch, dealt for real
        g3 = base({"locked_location": loc}); o = g3.players[1]; before = o.lp
        g3.apply(0, ("punch",)); out[f"punch_{loc}"] = before - o.lp
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
    # chapter 2
    g = base({"locked_location": "Falador Tunnels"})
    out["tunnels_damage"] = [g.compute_damage(0, 10, s)[0] for s in ("magic", "melee", "ranged")]
    for loc in (None, "Hidden Prison", "Duel Arena"):        # prayers and food at each
        g = base({"locked_location": loc} if loc else None); p, o = g.players
        p.hand = ["Piety", "Lobster", "Protect from Melee"]
        for _ in range(20): p.discard.append(p.deck.pop())
        p.materials.update({"Bronze Bar": 3})
        acts = g.legal_actions(0)
        out[f"prayer_{loc or 'none'}"] = [any(a[0] == "play" and a[1] == n for a in acts) for n in ("Piety", "Lobster")]
        seen = []
        class Rec(Pass):
            def react(self, g, j, amount, style, src, opts): seen.append(sorted(opts)); return None
        g.agents[0] = Rec()
        g.offer_reaction(1, 10, "melee", "weapon")
        out[f"protect_{loc or 'none'}"] = seen
    for card, mats in (("Cabbage Mace", 2), ("Cabbage Seeds", 1), ("Cabbages!", 3)):
        g = base(); p, o = g.players
        g.turn = 2; p.hand = [card]
        out[f"{card}_without"] = any(a[1] == card for a in g.legal_actions(0) if a[0] == "play")
        p.materials.update({"Cabbage": mats})
        out[f"{card}_with"] = any(a[1] == card for a in g.legal_actions(0) if a[0] == "play")
    g = base(); p, o = g.players
    p.materials.update({"Blood Rune": 1, "Death Rune": 4})
    for _ in range(10): p.discard.append(p.deck.pop())
    for spell in ("Necrotic Bolt", "Soul Siphon", "Cabbages!"):
        p.materials.update({"Cabbage": 3})
        before, obefore = p.lp, o.lp
        g._apply_card(0, spell, None)
        out[spell] = [p.lp - before, obefore - o.lp]
    for armor in ("White Knight Armor", "Cabbage Armor"):
        g = base(); p, o = g.players
        o.armor = armor
        out[armor] = [g.compute_damage(0, 10, s)[0] for s in ("magic", "melee", "ranged")]
        p.armor = armor
        out[armor + "_bonus"] = g.compute_damage(0, 10, "melee", src="weapon")[0]
    g = base(); p, o = g.players
    p.discard = ["Necrotic Bolt", "Bronze Bar", "Cabbage Mace"]; p.hand = ["Raise Dead"]
    out["raise_dead"] = sorted(a[2] for a in g.legal_actions(0) if a[0] == "play" and a[1] == "Raise Dead")
    # chapter 3
    g = base({"locked_location": "The Kinshra Hall"})
    out["kinshra_hall_damage"] = [g.compute_damage(0, 10, s)[0] for s in ("magic", "melee", "ranged")]
    g = base(); p, o = g.players
    for _ in range(10): p.discard.append(p.deck.pop())
    p.buff = "Unholy Wrath"; p.buff_left = 3
    before, ob = p.lp, o.lp
    g.deal(0, 6, "ranged", "weapon")
    out["unholy_wrath"] = [p.lp - before, ob - o.lp]
    o.shield = {"name": "Kiteshield", "soak": 20}
    before = p.lp
    g.deal(0, 6, "melee", "weapon")
    out["unholy_wrath_soaked"] = p.lp - before
    for card, bars in (("Black Knight Platebody", 2), ("Black Knight Shield", 1)):
        g = base(); p, o = g.players
        g.turn = 2; p.hand = [card]
        out[f"{card}_without"] = any(a[1] == card for a in g.legal_actions(0) if a[0] == "play")
        p.materials.update({"Black Bar": bars})
        out[f"{card}_with"] = any(a[1] == card for a in g.legal_actions(0) if a[0] == "play")
    g = base(); p, o = g.players
    p.materials = {"Black Bar": 3, "Bronze Bar": 3}
    from capes import progress
    out["smithing_with_black_bars"] = progress(p, "Smithing Cape", g.rules["cape_need"])[0]
    for sh in ("Black Knight Shield", "Kiteshield"):
        g = base(); p, o = g.players
        p.materials.update({"Fire Rune": 5, "Chaos Rune": 2, "Blood Rune": 2, "Air Rune": 2})
        o.shield = {"name": sh, "soak": 20}
        before = o.lp
        g._apply_card(0, "Demon Fire", None)
        out[f"shatter_vs_{sh}"] = [before - o.lp, o.shield["soak"] if o.shield else None]
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
  for (const loc of ['Draynor Manor', 'Moonlit Circle', 'Wilderness']) {
    const g3 = base({ locked_location: loc }); const o = g3.players[1]; const before = o.lp;
    await g3.apply(0, ['punch']); out[`punch_${loc}`] = before - o.lp;
  }
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
  { const g = base({ locked_location: 'Falador Tunnels' });
    out.tunnels_damage = ['magic', 'melee', 'ranged'].map((s) => g.compute_damage(0, 10, s)[0]); }
  for (const loc of [null, 'Hidden Prison', 'Duel Arena']) {
    const g = base(loc ? { locked_location: loc } : null); const [p] = g.players;
    p.hand = ['Piety', 'Lobster', 'Protect from Melee'];
    for (let k = 0; k < 20; k++) p.discard.push(p.deck.pop());
    Object.assign(p.materials, { 'Bronze Bar': 3 });
    const acts = g.legal_actions(0);
    out[`prayer_${loc || 'none'}`] = ['Piety', 'Lobster'].map((n) => acts.some((a) => a[0] === 'play' && a[1] === n));
    const seen = [];
    g.agents[0] = { ...Pass, react: async (g, j, amount, style, src, opts) => { seen.push([...opts].sort()); return null; } };
    await g.offer_reaction(1, 10, 'melee', 'weapon');
    out[`protect_${loc || 'none'}`] = seen;
  }
  for (const [card, mats] of [['Cabbage Mace', 2], ['Cabbage Seeds', 1], ['Cabbages!', 3]]) {
    const g = base(); const [p] = g.players;
    g.turn = 2; p.hand = [card];
    out[`${card}_without`] = g.legal_actions(0).some((a) => a[0] === 'play' && a[1] === card);
    Object.assign(p.materials, { Cabbage: mats });
    out[`${card}_with`] = g.legal_actions(0).some((a) => a[0] === 'play' && a[1] === card);
  }
  { const g = base(); const [p, o] = g.players;
    Object.assign(p.materials, { 'Blood Rune': 1, 'Death Rune': 4 });
    for (let k = 0; k < 10; k++) p.discard.push(p.deck.pop());
    for (const spell of ['Necrotic Bolt', 'Soul Siphon', 'Cabbages!']) {
      Object.assign(p.materials, { Cabbage: 3 });
      const b = p.lp, ob2 = o.lp; await g.apply_card(0, spell, null);
      out[spell] = [p.lp - b, ob2 - o.lp];
    } }
  for (const armor of ['White Knight Armor', 'Cabbage Armor']) {
    const g = base(); const [p, o] = g.players;
    o.armor = armor;
    out[armor] = ['magic', 'melee', 'ranged'].map((s) => g.compute_damage(0, 10, s)[0]);
    p.armor = armor;
    out[armor + '_bonus'] = g.compute_damage(0, 10, 'melee', false, false, 'weapon')[0];
  }
  { const g = base(); const [p] = g.players;
    p.discard = ['Necrotic Bolt', 'Bronze Bar', 'Cabbage Mace']; p.hand = ['Raise Dead'];
    out.raise_dead = g.legal_actions(0).filter((a) => a[0] === 'play' && a[1] === 'Raise Dead').map((a) => a[2]).sort(); }
  { const g = base({ locked_location: 'The Kinshra Hall' });
    out.kinshra_hall_damage = ['magic', 'melee', 'ranged'].map((s) => g.compute_damage(0, 10, s)[0]); }
  { const g = base(); const [p, o] = g.players;
    for (let k = 0; k < 10; k++) p.discard.push(p.deck.pop());
    p.buff = 'Unholy Wrath'; p.buff_left = 3;
    let b = p.lp; const ob2 = o.lp;
    await g.deal(0, 6, 'ranged', 'weapon');
    out.unholy_wrath = [p.lp - b, ob2 - o.lp];
    o.shield = { name: 'Kiteshield', soak: 20 };
    b = p.lp; await g.deal(0, 6, 'melee', 'weapon');
    out.unholy_wrath_soaked = p.lp - b; }
  for (const [card, bars] of [['Black Knight Platebody', 2], ['Black Knight Shield', 1]]) {
    const g = base(); const [p] = g.players;
    g.turn = 2; p.hand = [card];
    out[`${card}_without`] = g.legal_actions(0).some((a) => a[0] === 'play' && a[1] === card);
    Object.assign(p.materials, { 'Black Bar': bars });
    out[`${card}_with`] = g.legal_actions(0).some((a) => a[0] === 'play' && a[1] === card);
  }
  { const g = base(); const [p] = g.players;
    p.materials = { 'Black Bar': 3, 'Bronze Bar': 3 };
    out.smithing_with_black_bars = g.progress(p, 'Smithing Cape')[0]; }
  for (const sh of ['Black Knight Shield', 'Kiteshield']) {
    const g = base(); const [p, o] = g.players;
    Object.assign(p.materials, { 'Fire Rune': 5, 'Chaos Rune': 2, 'Blood Rune': 2, 'Air Rune': 2 });
    o.shield = { name: sh, soak: 20 };
    const b = o.lp; await g.apply_card(0, 'Demon Fire', null);
    out[`shatter_vs_${sh}`] = [b - o.lp, o.shield ? o.shield.soak : null];
  }
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
    # a Punch is never changed by a Location, the Wilderness included (Ramon, 2026-11-18)
    for loc in ("Draynor Manor", "Moonlit Circle", "Wilderness"):
        k = f"punch_{loc}"
        if py[k] != 2:
            bad += 1; print(f"WRONG {k}: a Punch dealt {py[k]}, not 2")
    print("all passed" if not bad else f"{bad} DIFFERENT")
    sys.exit(1 if bad else 0)
