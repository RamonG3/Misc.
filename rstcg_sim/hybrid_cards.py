"""Round 11: cards for the weakest decks, tested against the current field (Round 7
field, deck-as-life rules).

    python hybrid_cards.py 80           # greedy bot, 80 games per seat order
    python hybrid_cards.py 80 Void      # only tests whose name contains "Void"
"""
import sys
from multiprocessing import Pool

import new_cards as nc
from simulate import play

FIELD = nc.round7_field()


def add(deck, cards, take):
    """Put `cards` in, taking the same number out of `take` (in order)."""
    d = dict(deck)
    owed = sum(cards.values())
    for name in take:
        while owed and d.get(name, 0) > 0:
            d[name] -= 1
            owed -= 1
    assert owed == 0, f"{owed} short"
    for k, v in cards.items():
        d[k] = d.get(k, 0) + v
    d = {k: v for k, v in d.items() if v > 0}
    assert sum(d.values()) == 60
    return d


DEF = ("Shrimp", "Swordfish", "Lobster", "Wooden Shield", "Doric", "Lowe", "Archmage Sedridor",
       "Wizards' Tower", "Rune Pouch", "Kiteshield")


def tests():
    f = FIELD
    t = {}
    mm, mr, mgr = f["Mage/Melee+"], f["Melee/Ranged+"], f["Mage/Ranged+"]
    t["Mage/Melee+ + Enchanted Bar"] = ("Mage/Melee+", add(mm, {"Enchanted Bar": 4}, ("Mithril Bar", "Chaos Rune") * 2))
    t["Mage/Melee+ + Void"] = ("Mage/Melee+", add(mm, {"Void Knight Top": 2}, ("Bronze Platebody",) * 2))
    t["Mage/Melee+ + dual + Void"] = ("Mage/Melee+", add(add(mm, {"Enchanted Bar": 4}, ("Mithril Bar", "Chaos Rune") * 2),
                                                        {"Void Knight Top": 2}, ("Bronze Platebody",) * 2))
    t["Melee/Ranged+ + Grapple"] = ("Melee/Ranged+", add(mr, {"Mithril Grapple": 4}, ("Mithril Bar", "Willow Logs") * 2))
    t["Melee/Ranged+ + Void"] = ("Melee/Ranged+", add(mr, {"Void Knight Top": 2}, ("Bronze Platebody",) * 2))
    t["Melee/Ranged+ + dual + Void"] = ("Melee/Ranged+", add(add(mr, {"Mithril Grapple": 4}, ("Mithril Bar", "Willow Logs") * 2),
                                                            {"Void Knight Top": 2}, ("Bronze Platebody",) * 2))
    t["Mage/Ranged+ + Enchanted Logs"] = ("Mage/Ranged+", add(mgr, {"Enchanted Logs": 4}, ("Willow Logs", "Chaos Rune") * 2))
    t["Mage/Ranged+ + Void"] = ("Mage/Ranged+", add(mgr, {"Void Knight Top": 2}, DEF))
    t["Mage/Ranged+ + dual + Void"] = ("Mage/Ranged+", add(add(mgr, {"Enchanted Logs": 4}, ("Willow Logs", "Chaos Rune") * 2),
                                                          {"Void Knight Top": 2}, DEF))
    return t


def _job(args):
    name, deck, opp, seeds, rules = args
    decks = {name: deck, "F:" + opp: FIELD[opp]}
    out = [play(name, "F:" + opp, s, rules, decks)["winner"] == 0 for s in seeds]
    out += [play("F:" + opp, name, s, rules, decks)["winner"] == 1 for s in seeds]
    return name, opp, out


def run(decks, n, rules=None):
    jobs, seed = [], 1
    for name, deck in decks.items():
        for opp in FIELD:
            jobs.append((name, deck, opp, list(range(seed, seed + n)), rules or {}))
            seed += n
    res = {}
    with Pool(2) as pool:
        for name, opp, out in pool.map(_job, jobs):
            res[(name, opp)] = 100 * sum(out) / len(out)
    return res


def round11_field():
    """The Round 7 field with the three hybrids on dual materials and Void Knight Top."""
    f = dict(FIELD)
    t = tests()
    for b in ("Mage/Melee+", "Melee/Ranged+", "Mage/Ranged+"):
        f[b] = t[b + " + dual + Void"][1]
    lm = dict(f["Lean Mage"])                 # better builds from existing cards
    lm["Augury"] = lm.pop("Mystic Will")
    f["Lean Mage"] = add(lm, {"Iban's Staff": 1}, DEF)
    tr = dict(f["Thrown Ranged"])
    tr["Rigour"] = tr.pop("Sharp Eye")
    f["Thrown Ranged"] = tr
    return f


if __name__ == "__main__" and "--field" in sys.argv:
    n = int(sys.argv[1])
    for label, decks in (("Round 7 field", FIELD), ("Round 11 field", round11_field())):
        rate, avg = nc.field_matrix(decks, n)
        print(label, "spread", round(max(avg.values()) - min(avg.values())))
        for a in sorted(avg, key=avg.get, reverse=True):
            print(f"  {a:15} {avg[a]:4.0f}%")
    sys.exit()

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 80
    only = args[1] if len(args) > 1 else ""
    t = {k: v for k, v in tests().items() if only in k}
    decks = {k: d for k, (_, d) in t.items()}
    bases = sorted({b for b, _ in t.values()})
    decks.update({"BASE " + b: FIELD[b] for b in bases})
    res = run(decks, n)
    avg = {k: sum(res[(k, o)] for o in FIELD) / len(FIELD) for k in decks}
    show = ("Lean Melee", "Smoke Mage", "Lean Ranged", "Lean Mage")
    print(f"{'deck':30} {'field':>6} {'change':>6}   " + "  ".join(f"{o[:10]:>10}" for o in show))
    for b in bases:
        for k in ["BASE " + b] + [k for k, (bb, _) in t.items() if bb == b]:
            print(f"{k:30} {avg[k]:5.0f}% {avg[k] - avg['BASE ' + b]:+6.0f}   "
                  + "  ".join(f"{res[(k, o)]:9.0f}%" for o in show))
        print()
