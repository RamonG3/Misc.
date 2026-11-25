"""Round 28 (2026-11-25): the Air Battlestaff becomes a staff.

Ramon: it shouldn't come with arrows like a bow; a staff that counts as 2 Air Runes, with a better swing than Staff of
Air. Greedy bots, both seats, against the Arena's starter + tournament field (web/cards.json).

    python r28.py 300
"""
import dataclasses
import json
import sys
from collections import defaultdict
from multiprocessing import Pool

import cards
from bot import HeuristicBot
from engine import Game

DATA = json.load(open("web/cards.json"))
D = {d["name"]: d["cards"] for d in DATA["decks"]}
FIELD = [d["name"] for d in DATA["decks"] if d["group"] in ("Starter decks", "Tournament decks")]
NOW = cards.CARDS["Air Battlestaff"]
BOW = dataclasses.replace(NOW, kind="bow", req=(("Willow Logs", 1), ("Air Rune", 1)), hit=5, arrow_tier=2,
                          durability=0, wclass="")
MELEE = dict(kind="weapon", styles=("melee",))


def swap(deck, out, add):
    d = dict(deck)
    for k, n in out.items():
        d[k] -= n
        if not d[k]:
            del d[k]
    for k, n in add.items():
        d[k] = d.get(k, 0) + n
    assert sum(d.values()) == 60
    return d


AM, MR = D["Air Mage"], D["Mage/Ranged+"]
SLOTS = {"Staff of Air": 1, "Shrimp": 1, "Tinderbox": 1}
OLD_MR = swap(MR, {"Willow Shortbow": 1, "Mirror Shield": 1, "Ricochet": 1}, {"Air Battlestaff": 3})
TESTS = {   # label: (host, deck, Air Battlestaff as)
    "Air Mage as it is": ("Air Mage", AM, NOW),
    "Air Mage, 3 Staff of Air": ("Air Mage", swap(AM, SLOTS, {"Staff of Air": 3}), NOW),
    "Air Mage, 3 Battlestaff, swing 3 Magic": ("Air Mage", swap(AM, SLOTS, {"Air Battlestaff": 3}), dataclasses.replace(NOW, hit=3)),
    "Air Mage, 3 Battlestaff, swing 4 Magic (chosen)": ("Air Mage", swap(AM, SLOTS, {"Air Battlestaff": 3}), NOW),
    "Air Mage, 3 Battlestaff, swing 4 Melee": ("Air Mage", swap(AM, SLOTS, {"Air Battlestaff": 3}), dataclasses.replace(NOW, hit=4, **MELEE)),
    "Air Mage, 3 Battlestaff, swing 5 Melee": ("Air Mage", swap(AM, SLOTS, {"Air Battlestaff": 3}), dataclasses.replace(NOW, hit=5, **MELEE)),
    "Mage/Ranged+ old list, bow battlestaff": ("Mage/Ranged+", OLD_MR, BOW),
    "Mage/Ranged+ old list, staff battlestaff": ("Mage/Ranged+", OLD_MR, NOW),
    "Mage/Ranged+ new list": ("Mage/Ranged+", MR, NOW),
}


def play(args):
    label, opp, n = args
    host, deck, card = TESTS[label]
    cards.CARDS["Air Battlestaff"] = card
    w = 0
    for s in range(n):
        for seat in (0, 1):
            seed = 280_000 + s
            ds = [None, None]
            ds[seat], ds[1 - seat] = deck, D[opp]
            g = Game(ds, [HeuristicBot(seed * 2 + 1), HeuristicBot(seed * 2 + 2)], seed=seed)
            g.run()
            w += g.winner == seat
    return label, w / (2 * n)


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    jobs = [(label, o, n) for label, (host, _, _) in TESTS.items() for o in FIELD if o != host]
    with Pool() as pool:
        rs = pool.map(play, jobs, chunksize=2)
    agg = defaultdict(list)
    for label, r in rs:
        agg[label].append(r)
    for label in TESTS:
        print(f"{label:50s} {100 * sum(agg[label]) / len(agg[label]):5.1f}%")
