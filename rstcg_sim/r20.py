"""Round 20: fixing Fire Mage (32% in Round 19).

Same harness as r19.py --b: the 20-deck round robin (best-of-each-style field + every elemental mage),
every deck plays every other deck in both seat orders. Each variant is (card changes, Fire Mage list).

Phase 1 (--lists): printed cards, different Fire Mage lists. The Round 19 list is a Round 17 leftover:
8 damage spells, 3 Superheat, no Iban's Staff (it needed Air until Round 19).
Phase 2 (default): card changes, played with the chosen list (--list NAME, default "Water shell").

    python r20.py 300 --lists
    python r20.py 300 --list "Water shell"
    python r20.py 300 "S3|B1"          # only variants whose name contains one of these
"""
import dataclasses
import sys
from multiprocessing import Pool

import bestof
import cards
import r19
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot
from tier3 import swap

SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot
C = cards.CARDS
ORIG = dict(C)
FIRE = ("Fire Strike", "Fire Bolt", "Fire Blast", "Fire Wave")


def fire_lists():
    base = r19.decks(False)["Fire Mage"]
    water = r19.decks(False)["Water Mage"]          # Round 19 Water Mage: Staff of Water 3 + Iban's 2
    shell = {k.replace("Water", "Fire"): v for k, v in water.items()}
    return {
        "R19 list": base,
        "L1 Superheat -> Iban's": swap(base, {"Superheat": 3}, {"Iban's Staff": 3}),
        "L2 Staff of Fire -> Iban's": swap(base, {"Staff of Fire": 3}, {"Iban's Staff": 3}),
        "Water shell": shell,                       # Water Mage's exact list with Fire for Water
        "Water shell, Blast -> Wave": swap(shell, {"Death Rune": 3, "Fire Blast": 3},
                                           {"Blood Rune": 3, "Fire Wave": 3}),
    }


CARD_VARIANTS = {
    "printed": {},
    "S3 Staff of Fire counts as 3 Fire": {"Staff of Fire": {"counts_as": (("Fire Rune", 3),)}},
    "P1 Fire Strike needs 1 Fire (8 dmg)": {"Fire Strike": {"req": (("Fire Rune", 1),)}},
    "P2 Fire Strike needs 1 Fire, 7 dmg": {"Fire Strike": {"req": (("Fire Rune", 1),), "damage": 7}},
    "P3 every Fire spell needs 1 fewer Fire": {
        "Fire Strike": {"req": (("Fire Rune", 1),)},
        "Fire Bolt": {"req": (("Chaos Rune", 1), ("Fire Rune", 2))},
        "Fire Blast": {"req": (("Death Rune", 1), ("Fire Rune", 3))},
        "Fire Wave": {"req": (("Blood Rune", 1), ("Fire Rune", 4))}},
    "B1 Fire spells shatter shields": {n: {"burns": "shatter"} for n in FIRE},
    "B2 Fire spells ignore shields": {n: {"burns": "pierce"} for n in FIRE},
}


def apply(changes):
    C.clear()
    C.update(ORIG)
    for name, kw in changes.items():
        C[name] = dataclasses.replace(C[name], **kw)


D = {}


def _job(args):
    a, b, seeds = args
    out = []
    for s in seeds:
        g = Game([D[a], D[b]], [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s)
        g.run()
        out.append(g.winner)
    return a, b, out


def run(changes, fire_list, n, workers=2):
    apply(changes)
    D.clear()
    D.update(r19.decks(False))
    D["Fire Mage"] = fire_list
    cards.check_decks({"Fire Mage": {k: v for k, v in fire_list.items() if v}})
    names = list(D)
    jobs, seed = [], 1
    for a in names:
        for b in names:
            if a != b:
                jobs.append((a, b, list(range(seed, seed + n))))
                seed += n
    w = {}
    with Pool(workers) as pool:
        for a, b, out in pool.imap_unordered(_job, jobs, chunksize=4):
            for x in out:
                for seat, me, op in ((0, a, b), (1, b, a)):
                    t = w.setdefault((me, op), [0, 0])
                    t[0] += x == seat
                    t[1] += 1
    pct = {k: 100 * v[0] / v[1] for k, v in w.items()}
    avg = {a: sum(pct[(a, b)] for b in names if b != a) / (len(names) - 1) for a in names}
    return avg, pct


def table(rows, mats, title):
    names = list(next(iter(rows.values())))
    style = {k: s for k, (s, _) in bestof.D.items()}
    cols = list(rows)
    print(title + "\n")
    for i, c in enumerate(cols):
        print(f"  [{i}] {c}")
    print("\n" + f"{'deck':18}" + "".join(f"{'[' + str(i) + ']':>6}" for i in range(len(cols))))
    for a in names:
        print(f"{a:18}" + "".join(f"{rows[c][a]:6.0f}" for c in cols))
    print()
    for st in ("melee", "ranged"):
        ds = [a for a in names if style.get(a) == st]
        print(f"{st + ' avg':18}" + "".join(f"{sum(rows[c][a] for a in ds) / len(ds):6.0f}" for c in cols))
    ds = [a for a in names if style.get(a, "magic") == "magic"]
    print(f"{'magic avg':18}" + "".join(f"{sum(rows[c][a] for a in ds) / len(ds):6.0f}" for c in cols))
    spread = [max(rows[c].values()) - min(rows[c].values()) for c in cols]
    print(f"{'best-worst spread':18}" + "".join(f"{s:6.0f}" for s in spread))
    print("\nFire Mage by opponent:")
    print(f"{'opponent':18}" + "".join(f"{'[' + str(i) + ']':>6}" for i in range(len(cols))))
    for b in names:
        if b != "Fire Mage":
            print(f"{b:18}" + "".join(f"{mats[c][('Fire Mage', b)]:6.0f}" for c in cols))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 100
    only = [x for x in args[1].split("|") if x] if len(args) > 1 else None
    lists = fire_lists()
    rows, mats = {}, {}
    if "--lists" in sys.argv:
        runs = [(k, {}, v) for k, v in lists.items()]
    else:
        lname = sys.argv[sys.argv.index("--list") + 1] if "--list" in sys.argv else "Water shell"
        args = [a for a in args if a != lname]
        only = [x for x in args[1].split("|") if x] if len(args) > 1 else None
        runs = [(k, v, lists[lname]) for k, v in CARD_VARIANTS.items()]
    for vname, changes, fl in runs:
        if only and not any(x in vname for x in only):
            continue
        rows[vname], mats[vname] = run(changes, fl, n)
        print(f"done: {vname}  Fire Mage {rows[vname]['Fire Mage']:.1f}", file=sys.stderr, flush=True)
    table(rows, mats, ("Smart" if SMART else "Greedy") + f" bot, 20-deck round robin, {n} games per ordered pairing")


if __name__ == "__main__":
    main()
