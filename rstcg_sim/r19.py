"""Round 19: the Air and Water Mage questions.

Full round robin: the best-of-each-style field (12 decks) plus every elemental mage (Mist, Dust,
Steam, Mud, Lava, Water, Fire), under several card variants. Every deck plays every other deck in
both seat orders, so a nerf to Air also shows up in the decks that play against Air.

Round 18's flurry template and Water Mage carried Iban's Staff, which needs an Air Rune, so the
non-Air decks had 2-3 dead cards. The "fair" decks swap those for their own element's staff.

    python r19.py 150                 # greedy, 150 games per ordered pairing, every variant
    python r19.py 150 "printed|A"     # only variants whose name contains one of these
"""
import dataclasses
import sys
from multiprocessing import Pool

import bestof
import cards
import group1
import group2
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot
from tier3 import swap

SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot
C = cards.CARDS
ORIG = dict(C)
ANY = (("Chaos Rune", 1), ("Elemental Rune", 1))
WATER = ("Water Strike", "Water Bolt", "Water Blast", "Water Wave")
AIR = ("Air Strike", "Air Bolt", "Air Blast", "Air Wave")
EARTH = ("Earth Strike", "Earth Bolt", "Earth Blast", "Earth Wave")
AIRDMG = (6, 10, 13, 17)

VARIANTS = {
    "printed": {},
    # A: the special staves stop needing Air
    "A1 Iban/Light need any element (Light still 2 Air)": {"Iban's Staff": {"req": ANY}, "Staff of Light": {"req": ANY}},
    "A2 Iban/Light need any element, Light counts as nothing": {"Iban's Staff": {"req": ANY},
                                                               "Staff of Light": {"req": ANY, "counts_as": ()}},
    # B: Air's damage comes down
    "B1 Air Strike 5": {"Air Strike": {"damage": 5}},
    "B2 Air line 5/9/12/16": {n: {"damage": d - 1} for n, d in zip(AIR, AIRDMG)},
    # C: Water gets Air's numbers (keeps heal 2)
    "C Water 6/10/13/17": {n: {"damage": d} for n, d in zip(WATER, AIRDMG)},
    "A2 + C": {"Iban's Staff": {"req": ANY}, "Staff of Light": {"req": ANY, "counts_as": ()},
               **{n: {"damage": d} for n, d in zip(WATER, AIRDMG)}},
    "A1 + C": {"Iban's Staff": {"req": ANY}, "Staff of Light": {"req": ANY},
               **{n: {"damage": d} for n, d in zip(WATER, AIRDMG)}},
    "A1 + C + Earth 6/10/13/17": {"Iban's Staff": {"req": ANY}, "Staff of Light": {"req": ANY},
                                  **{n: {"damage": d} for n, d in zip(WATER + EARTH, AIRDMG * 2)}},
}


def apply(changes):
    C.clear()
    C.update(ORIG)
    for name, kw in changes.items():
        C[name] = dataclasses.replace(C[name], **kw)


STAFF = {"Air Rune": None, "Water Rune": "Staff of Water", "Earth Rune": "Staff of Earth", "Fire Rune": "Staff of Fire"}


def decks(fair):
    d = {k: dict(v) for k, (_, v) in bestof.D.items()}
    p = {k: v for k, (_, v) in group2.packages().items()}
    new = {
        "Mist Mage": ("Air Rune", "Water Rune"),
        "Dust Mage": ("Air Rune", "Earth Rune"),
        "Steam Mage": ("Fire Rune", "Water Rune"),
        "Mud Mage": ("Water Rune", "Earth Rune"),
        "Lava Mage": ("Earth Rune", "Fire Rune"),
    }
    for name, (a, b) in new.items():
        key = next(k for k in p if k.startswith(name))
        deck = dict(p[key])
        if fair and "Air Rune" not in (a, b):          # Iban's needs Air: use the deck's own staff
            deck = swap(deck, {"Iban's Staff": 3}, {STAFF[a]: 3})
        d[name] = deck
    water = dict(p["Water Mage (Lean Mage on Water)"])
    if fair:
        water = swap(water, {"Iban's Staff": 2}, {"Water Rune": 2})
    d["Water Mage"] = water
    # Lean Mage's exact list on Water: Staff of Light 2 + Iban's 3 instead of Staff of Water 3 + Iban's 2
    d["Water Mage (Lean)"] = swap(dict(p["Water Mage (Lean Mage on Water)"]),
                                  {"Staff of Water": 3, "Iban's Staff": 2}, {"Staff of Light": 2, "Iban's Staff": 3})
    d["Fire Mage"] = dict(p["Fire Mage + Staff of Fire"])
    d = {k: cards.modernize(v) for k, v in d.items()}      # Round 22: Cloth, Leather, Thessalia cut
    for k, v in d.items():
        cards.check_decks({k: {a: b for a, b in v.items() if b}})
    return d


D = {}


def _job(args):
    a, b, seeds = args
    out = []
    for s in seeds:
        g = Game([D[a], D[b]], [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s)
        g.run()
        out.append(g.winner)
    return a, b, out


def run(vname, changes, n, fair, as_printed_decks=False):
    apply(changes)
    D.clear()
    D.update(decks(fair))
    names = list(D)
    jobs, seed = [], 1
    for a in names:
        for b in names:
            if a != b:
                jobs.append((a, b, list(range(seed, seed + n))))
                seed += n
    w = {}
    with Pool(2) as pool:
        for a, b, out in pool.imap_unordered(_job, jobs, chunksize=4):
            for x in out:
                for seat, me, op in ((0, a, b), (1, b, a)):
                    t = w.setdefault((me, op), [0, 0])
                    t[0] += x == seat
                    t[1] += 1
    avg = {a: sum(100 * w[(a, b)][0] / w[(a, b)][1] for b in names if b != a) / (len(names) - 1) for a in names}
    return avg


LIGHT_ANY = {"req": ANY, "counts_as": (("Elemental Rune", 2),)}
VARIANTS_B = {
    "printed": {},
    "A1 any element (Light 2 Air)": {"Iban's Staff": {"req": ANY}, "Staff of Light": {"req": ANY}},
    "A3 any element, Light 2 of your main element": {"Iban's Staff": {"req": ANY}, "Staff of Light": LIGHT_ANY},
    "A3 + Air Strike 5": {"Iban's Staff": {"req": ANY}, "Staff of Light": LIGHT_ANY, "Air Strike": {"damage": 5}},
    "A3 + Water 6/10/13/17": {"Iban's Staff": {"req": ANY}, "Staff of Light": LIGHT_ANY,
                              **{n: {"damage": d} for n, d in zip(WATER, AIRDMG)}},
    "A3 + Water Strike 6 only": {"Iban's Staff": {"req": ANY}, "Staff of Light": LIGHT_ANY,
                                 "Water Strike": {"damage": 6}},
}


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 100
    only = [x for x in args[1].split("|") if x] if len(args) > 1 else None
    rows = {}
    if "--b" in sys.argv:      # Round 18 lists (Iban's live once it needs any element)
        runs = [(k, v, False) for k, v in VARIANTS_B.items()]
    else:
        runs = [("printed (Round 18 decks, dead Iban's)", {}, False)]
        runs += [(k, v, True) for k, v in VARIANTS.items()]
    for vname, changes, fair in runs:
        if only and not any(x in vname for x in only):
            continue
        rows[vname] = run(vname, changes, n, fair)
        print(f"done: {vname}", file=sys.stderr, flush=True)
    names = list(next(iter(rows.values())))
    style = {k: s for k, (s, _) in bestof.D.items()}
    print(("Smart" if SMART else "Greedy") + f" bot, 19-deck round robin, {n} games per ordered pairing\n")
    cols = list(rows)
    for i, c in enumerate(cols):
        print(f"  [{i}] {c}")
    print("\n" + f"{'deck':16}" + "".join(f"{'[' + str(i) + ']':>6}" for i in range(len(cols))))
    for a in names:
        print(f"{a:16}" + "".join(f"{rows[c][a]:6.0f}" for c in cols))
    print()
    for st in ("melee", "ranged"):
        ds = [a for a in names if style.get(a) == st]
        print(f"{st + ' avg':16}" + "".join(f"{sum(rows[c][a] for a in ds) / len(ds):6.0f}" for c in cols))
    ds = [a for a in names if style.get(a, "magic") == "magic"]
    print(f"{'magic avg':16}" + "".join(f"{sum(rows[c][a] for a in ds) / len(ds):6.0f}" for c in cols))


if __name__ == "__main__":
    main()
