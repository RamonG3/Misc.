"""Necromancy's rules, one scripted situation each, in the Python engine (2026-11-27). Prints JSON;
web/test/necro_rules.js prints the same from engine.js. `node web/test/necro_rules.js --compare` runs both and diffs them."""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from engine import Game  # noqa: E402
from cards import CARDS  # noqa: E402


def fresh(loc=None):
    g = Game([{"Bronze Bar": 60}, {"Bronze Bar": 60}], [None, None], seed=3)
    for q in g.players:
        q.hand, q.deck, q.discard = [], ["Bronze Bar"] * 20, []
        q.materials.clear()
    g.location = loc
    return g, g.players[0], g.players[1]


def snap(g):
    out = {"loc": g.location}
    for k, q in enumerate(g.players):
        out[f"p{k}"] = {"lp": q.lp, "weapon": q.weapon and {"name": q.weapon["name"], "dur": q.weapon["dur"]},
                        "shield": q.shield and {"name": q.shield["name"], "soak": q.shield["soak"], "virtual": bool(q.shield.get("virtual"))},
                        "armor": q.armor, "hand": sorted(q.hand), "discard": sorted(q.discard),
                        "mats": {m: n for m, n in sorted(q.materials.items()) if n},
                        "cape": {c: n for c, n in sorted(q.cape_prog.items()) if n}}
    return out


def run():
    R = {}
    # summons arrive in the City of Um with +1 durability; the Guardian is a shield too and pushes out a real one
    g, p, o = fresh("City of Um")
    p.materials.update({"Ectoplasm": 1, "Bronze Bar": 1, "Mithril Bar": 2, "Normal Logs": 1})
    g._apply_card(0, "Skeleton Warrior", None); R["skeleton_um"] = snap(g)
    g._apply_card(0, "Putrid Zombie", None); R["zombie_um"] = snap(g)
    p.shield = {"name": "Kiteshield", "soak": 8}
    g._apply_card(0, "Phantom Guardian", None); R["guardian"] = snap(g)
    # the Ghost: Magic damage, heals 2 on every swing, counts for the Magic and the Necromancy Capes
    g, p, o = fresh()
    p.discard = ["Shrimp"] * 5                   # healing brings discards back under the deck
    p.weapon = {"name": "Vengeful Ghost", "dur": 3, "energy": 0, "played": 0}
    g.do_swing(0, "magic"); R["ghost_swing"] = snap(g)
    # Necrotic spells: armour takes 1 off, the City of Um adds 2, the Necromancy Cape counts it and the Magic Cape doesn't
    for loc in (None, "City of Um"):
        g, p, o = fresh(loc)
        o.armor = "Wizard Robe"
        p.materials.update({"Spirit Rune": 1})
        g._apply_card(0, "Touch of Death", None); R[f"touch_{loc}"] = snap(g)
    # Rasial's Greed: Necrotic damage, then an Ectoplasm goes
    g, p, o = fresh()
    o.materials.update({"Ectoplasm": 2})
    g._apply_card(0, "Rasial's Greed", "Ectoplasm"); R["greed"] = snap(g)
    # the Skull Lantern: which summons it can raise, and raising one
    g, p, o = fresh()
    p.discard = ["Skeleton Warrior", "Vengeful Ghost", "Bronze Scimitar"]
    p.materials.update({"Ectoplasm": 1, "Bronze Bar": 1})
    R["lantern_targets"] = sorted(a[2] for a in g._card_actions(0, CARDS["Skull Lantern"], False))
    g._apply_card(0, "Skull Lantern", "Skeleton Warrior"); R["lantern"] = snap(g)
    # the Ritual: an Ectoplasm into play, or a summon to hand
    g, p, o = fresh()
    p.deck = ["Ectoplasm", "Putrid Zombie", "Bronze Bar", "Ectoplasm"]
    R["ritual_targets"] = sorted(a[2] for a in g._card_actions(0, CARDS["Necromancy Ritual"], False))
    g._apply_card(0, "Necromancy Ritual", "Ectoplasm"); a = snap(g)
    g._apply_card(0, "Necromancy Ritual", "Putrid Zombie"); R["ritual"] = [a, snap(g)]
    # Robes of the First Necromancer: summons hit +2, spells don't; Necrotic armour on the other side
    g, p, o = fresh()
    p.armor = "Robes of the First Necromancer"
    p.weapon = {"name": "Skeleton Warrior", "dur": 3, "energy": 0, "played": 0}
    o.armor = "Master Deathwarden Robe"
    g.do_swing(0, "melee"); a = snap(g)
    p.materials.update({"Spirit Rune": 1})
    g._apply_card(0, "Touch of Death", None); R["robes"] = [a, snap(g)]
    return R


if __name__ == "__main__":
    print(json.dumps(run(), sort_keys=True))
