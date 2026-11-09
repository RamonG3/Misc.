"""Round 7: buff prayers, special attacks and quests.

Each test deck is a field deck with a package swapped in. It plays the whole
reaction field (Round 6: every deck carries its natural reversals), and its
win rate is compared with the deck it was built from.

    python new_cards.py 100                 # every package
    python new_cards.py 100 Rigour          # only packages whose name contains "Rigour"
    python new_cards.py 100 --cape 12       # Quest Point Cape needs 12 QP
"""
import sys
from multiprocessing import Pool

import cards
from simulate import play

BASE = {**cards.LEAN_DECKS, **cards.OTHER_DECKS, **cards.GEAR_DECKS}
FIELD = {n: cards.with_reactions(d, cards.NATURAL_REVERSALS[n]) for n, d in BASE.items()}

# Cards a package may push out, in order: food, shields, NPCs, then spare materials.
SPARE = ("Shrimp", "Swordfish", "Karambwan", "Lobster", "Wooden Shield", "Kiteshield",
         "Banker", "Lowe", "Doric", "Vanaka", "Archmage Sedridor", "Wizards' Tower",
         "Rune Pouch", "Longrange", "Stab", "Bronze Dagger")


def swap(deck, add, out=SPARE, replace=None):
    """deck with `add` cards put in. `replace` maps cards to remove first; the rest come out of `out`."""
    d = dict(deck)
    for name, n in (replace or {}).items():
        d[name] -= n
    owed = sum(add.values()) - sum((replace or {}).values())
    for name in out:
        while owed > 0 and d.get(name, 0) > 0 and name not in add:
            d[name] -= 1
            owed -= 1
    assert owed <= 0, f"couldn't make room ({owed} short)"
    for name, n in add.items():
        d[name] = d.get(name, 0) + n
    d = {k: v for k, v in d.items() if v > 0}
    assert sum(d.values()) == 60, sum(d.values())
    return d


def variants():
    f = FIELD
    v = {}
    # --- buff prayers ---
    v["Lean Melee + Piety"] = ("Lean Melee", swap(f["Lean Melee"], {"Piety": 3}))
    v["Lean Ranged + Rigour"] = ("Lean Ranged", swap(f["Lean Ranged"], {"Rigour": 3}))
    v["Thrown Ranged + Rigour"] = ("Thrown Ranged", swap(f["Thrown Ranged"], {"Rigour": 3}))
    v["Lean Mage + Augury"] = ("Lean Mage", swap(f["Lean Mage"], {"Augury": 3}))
    v["Smoke Mage + Augury"] = ("Smoke Mage", swap(f["Smoke Mage"], {"Augury": 3}))
    v["Lean Melee + Redemption"] = ("Lean Melee", swap(f["Lean Melee"], {"Redemption": 2, "Smite": 1}))
    v["Lean Melee + Burst"] = ("Lean Melee", swap(f["Lean Melee"], {"Burst of Strength": 3}))
    v["Lean Ranged + Sharp Eye"] = ("Lean Ranged", swap(f["Lean Ranged"], {"Sharp Eye": 3}))
    v["Smoke Mage + Mystic Will"] = ("Smoke Mage", swap(f["Smoke Mage"], {"Mystic Will": 3}))
    v["Lean Mage + Mystic Will"] = ("Lean Mage", swap(f["Lean Mage"], {"Mystic Will": 3}))
    # --- special attacks ---
    v["Lean Melee + specs"] = ("Lean Melee", swap(f["Lean Melee"], {"Ancient Mace": 2, "Excalibur": 2, "Barrelchest Anchor": 2},
                                                 replace={"Mithril Sword": 3, "Mithril Scimitar": 2, "Bronze Scimitar": 1}))
    v["Lean Ranged + specs"] = ("Lean Ranged", swap(f["Lean Ranged"], {"Magic Shortbow": 3, "Seercull": 2},
                                                   replace={"Willow Shortbow": 3, "Shortbow": 2}))
    v["Lean Mage + specs"] = ("Lean Mage", swap(f["Lean Mage"], {"Staff of Light": 2, "Iban's Staff": 2},
                                               replace={"Staff of Air": 3, "Air Blast": 1}))
    v["Smoke Mage + Iban"] = ("Smoke Mage", swap(f["Smoke Mage"], {"Iban's Staff": 3}))
    v["Mage/Melee+ + Sara Sword"] = ("Mage/Melee+", swap(f["Mage/Melee+"], {"Saradomin Sword": 2},
                                                       replace={"Mithril Sword": 2}))
    # --- quests ---
    small = {"Rune Mysteries": 1, "Sheep Shearer": 1, "Cook's Assistant": 1}
    v["Lean Mage + quests"] = ("Lean Mage", swap(f["Lean Mage"], {**small, "Vampyre Slayer": 1}))
    v["Lean Melee + quests"] = ("Lean Melee", swap(f["Lean Melee"], {"Doric's Quest": 1, "Vampyre Slayer": 1,
                                                                     "Ernest the Chicken": 1, "Cook's Assistant": 1}))
    quest_cape = {"Romeo & Juliet": 1, "Goblin Diplomacy": 1, "Ernest the Chicken": 1, "Vampyre Slayer": 1,
                  "Prince Ali Rescue": 1, "Demon Slayer": 1, "Dragon Slayer": 1, "Rune Mysteries": 1,
                  "Cook's Assistant": 1, "Sheep Shearer": 1, "Quest Point Cape": 3}
    v["Quest Mage 3x (cape)"] = ("Lean Mage", swap(f["Lean Mage"], {"Romeo & Juliet": 3, "Goblin Diplomacy": 3,
                                                                  "Vampyre Slayer": 2, "Rune Mysteries": 2, "Quest Point Cape": 2},
                                                   out=SPARE + ("Air Blast", "Death Rune")))
    v["Quest Melee 3x (cape)"] = ("Lean Melee", swap(f["Lean Melee"], {"Romeo & Juliet": 3, "Goblin Diplomacy": 3,
                                                                     "Vampyre Slayer": 2, "Ernest the Chicken": 2, "Quest Point Cape": 2},
                                                     out=SPARE + ("Mithril Scimitar", "Bronze Bar")))
    v["Quest Smoke 3x (cape)"] = ("Smoke Mage", swap(f["Smoke Mage"], {"Romeo & Juliet": 3, "Goblin Diplomacy": 3,
                                                                     "Rune Mysteries": 2, "Cook's Assistant": 2, "Quest Point Cape": 2},
                                                     out=SPARE + ("Fire Bolt", "Air Rune")))
    v["Quest Mage (cape)"] = ("Lean Mage", swap(f["Lean Mage"], quest_cape,
                                                out=SPARE + ("Air Blast", "Death Rune", "Air Rune")))
    v["Quest Melee (cape)"] = ("Lean Melee", swap(f["Lean Melee"], {**quest_cape, "Doric's Quest": 1, "Sheep Shearer": 0,
                                                                    "Cloth": 0},
                                                  out=SPARE + ("Mithril Scimitar", "Bronze Bar", "Mithril Bar")))
    return v


def _job(args):
    name, deck, opp, seeds, rules = args
    decks = {name: deck, **{"F:" + k: d for k, d in FIELD.items()}}
    out = []
    for s in seeds:
        out.append(play(name, "F:" + opp, s, rules, decks)["winner"] == 0)
        out.append(play("F:" + opp, name, s, rules, decks)["winner"] == 1)
    return name, opp, out


def run(tests, n, rules=None, workers=2):
    jobs, seed = [], 1
    for name, deck in tests.items():
        for opp in FIELD:
            jobs.append((name, deck, opp, list(range(seed, seed + n)), rules or {}))
            seed += n
    res = {}
    with Pool(workers) as pool:
        for name, opp, out in pool.map(_job, jobs):
            res[(name, opp)] = 100 * sum(out) / len(out)
    return res


def round7_field():
    """Each field deck with its best new option (Round 7)."""
    f, v = FIELD, variants()
    return {
        "Lean Melee": swap(f["Lean Melee"], {"Burst of Strength": 3}),
        "Lean Mage": swap(v["Lean Mage + specs"][1], {"Mystic Will": 2}),
        "Lean Ranged": swap(v["Lean Ranged + specs"][1], {"Sharp Eye": 3}),
        "Smoke Mage": v["Smoke Mage + Iban"][1],
        "Thrown Ranged": swap(f["Thrown Ranged"], {"Sharp Eye": 3}),
        "Mage/Ranged+": swap(f["Mage/Ranged+"], {"Sharp Eye": 2}),
        "Mage/Melee+": v["Mage/Melee+ + Sara Sword"][1],
        "Melee/Ranged+": swap(f["Melee/Ranged+"], {"Sharp Eye": 2}),
        "Melee/Thrown": swap(f["Melee/Thrown"], {"Sharp Eye": 2}),
    }


def field_matrix(decks, n, rules=None, workers=2):
    names = list(decks)
    jobs, seed = [], 1
    for a in names:
        for b in names:
            jobs.append((a, b, list(range(seed, seed + n)), decks, rules or {}))
            seed += n
    wins = {}
    with Pool(workers) as pool:
        for a, b, ws in pool.map(_fjob, jobs):
            for w in ws:
                for seat, deck, opp in ((0, a, b), (1, b, a)):
                    g, t = wins.get((deck, opp), (0, 0))
                    wins[(deck, opp)] = (g + (w == seat), t + 1)
    rate = {k: 100 * g / t for k, (g, t) in wins.items()}
    avg = {a: sum(rate[(a, b)] for b in names if b != a) / (len(names) - 1) for a in names}
    return rate, avg


def _fjob(args):
    a, b, seeds, decks, rules = args
    return a, b, [play(a, b, s, rules, decks)["winner"] for s in seeds]


if __name__ == "__main__" and "--field" in sys.argv:
    n = int(sys.argv[1])
    for label, decks in (("Round 6 field", FIELD), ("Round 7 field", round7_field())):
        rate, avg = field_matrix(decks, n)
        print(label, "spread", round(max(avg.values()) - min(avg.values())))
        for a in sorted(avg, key=avg.get, reverse=True):
            print(f"  {a:15} {avg[a]:4.0f}%   vs Lean Melee {rate.get((a, 'Lean Melee'), 50):3.0f}%")
    sys.exit()

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 100
    only = args[1] if len(args) > 1 else ""
    rules = {}
    if "--cape" in sys.argv:
        rules["cape_qp"] = int(sys.argv[sys.argv.index("--cape") + 1])
    var = {k: v for k, v in variants().items() if only in k}
    tests = {k: d for k, (_, d) in var.items()}
    bases = {b for b, _ in var.values()}
    tests.update({"BASE " + b: FIELD[b] for b in bases})
    res = run(tests, n, rules)
    avg = {t: sum(res[(t, o)] for o in FIELD) / len(FIELD) for t in tests}
    show = ("Lean Melee", "Lean Mage", "Smoke Mage", "Lean Ranged")
    print(f"{'deck':28} {'field':>6} {'delta':>6}   " + "  ".join(f"{o[:10]:>10}" for o in show))
    for b in sorted(bases):
        for t in ["BASE " + b] + [k for k, (bb, _) in var.items() if bb == b]:
            d = avg[t] - avg["BASE " + b]
            print(f"{t:28} {avg[t]:5.0f}% {d:+5.0f}   " + "  ".join(f"{res[(t, o)]:9.0f}%" for o in show))
        print()
