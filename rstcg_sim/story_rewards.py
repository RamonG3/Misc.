"""Story Mode's reward cards (2026-11-18): how much each one adds to a deck that uses it.

    python story_rewards.py 300
    python story_rewards.py 300 ch2      # chapter 2's two armours

Claws of Guthix at 10, 12 and 14 damage: the Air Mage starter with 2 Claws and 2 Blood Runes in place of 2 Air
Blasts and 2 Air Runes, against the other two starters and the tournament field, both seats, greedy bots.
Shield of Arrav: each starter with its Wooden Shields swapped for it (up to 2). Compared with the deck as it is:
the gain is the reward's worth. A trophy, not a trump card: a few points, like a good Rare.
"""
import dataclasses
import json
import sys
from multiprocessing import Pool

import cards
from bot import HeuristicBot
from engine import Game

DATA = json.load(open("web/cards.json"))
SIM = {"Varrock Teleport": "Varrock Teleport Tablet"}
D = {d["name"]: {SIM.get(k, k): v for k, v in d["cards"].items()} for d in DATA["decks"]}
FIELD = [d["name"] for d in DATA["decks"] if d["group"] in ("Starter decks", "Tournament decks")]


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


def play(args):
    deck, opp, n, claws = args
    if claws is not None:
        cards.CARDS["Claws of Guthix"] = dataclasses.replace(cards.CARDS["Claws of Guthix"], damage=claws)
    w = 0
    for s in range(n):
        for seat in (0, 1):
            seed = 900_000 + s
            ds = [None, None]
            ds[seat], ds[1 - seat] = deck, D[opp]
            g = Game(ds, [HeuristicBot(seed * 2 + 1), HeuristicBot(seed * 2 + 2)], seed=seed)
            g.run()
            w += g.winner == seat
    return w / (2 * n)


def rate(deck, n, claws=None, pool=None):
    opps = [o for o in FIELD if D[o] != deck]
    rs = pool.map(play, [(deck, o, n, claws) for o in opps])
    return 100 * sum(rs) / len(rs)


def chapter2(n, pool):
    """Chapter 2 (2026-11-20): White Knight Armor for the Melee starter's Bronze Platebodies; Cabbage Armor for the
    Ranged starter's Leather Bodies (both pay 3 of a family: bars or logs)."""
    for st, out, add in (("Melee", "Bronze Platebody", "White Knight Armor"), ("Ranged", "Leather Body", "Cabbage Armor"),
                         ("Ranged", "Green D'hide Body", "Cabbage Armor")):
        b = rate(D[st], n, pool=pool)
        k = D[st][out]
        r = rate(swap(D[st], {out: k}, {add: k}), n, pool=pool)
        print(f"{st}: {b:.1f}% · with {k} {add} for {out}: {r:.1f}% ({r - b:+.1f})")


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    if "ch2" in sys.argv:
        with Pool() as pool:
            chapter2(n, pool)
        return
    with Pool() as pool:
        base = rate(D["Air Mage"], n, pool=pool)
        print(f"Air Mage as it is: {base:.1f}% against the field")
        withc = swap(D["Air Mage"], {"Air Blast": 2, "Air Rune": 2}, {"Claws of Guthix": 2, "Blood Rune": 2})
        for dmg in (10, 12, 14):
            r = rate(withc, n, claws=dmg, pool=pool)
            print(f"  + 2 Claws of Guthix at {dmg}: {r:.1f}% ({r - base:+.1f})")
        for st in ("Melee", "Ranged", "Air Mage"):
            b = rate(D[st], n, pool=pool)
            k = min(2, D[st].get("Wooden Shield", 0))
            r = rate(swap(D[st], {"Wooden Shield": k}, {"Shield of Arrav": k}), n, pool=pool)
            print(f"{st}: {b:.1f}% · with {k} Shield of Arrav for Wooden Shield: {r:.1f}% ({r - b:+.1f})")


if __name__ == "__main__":
    main()
