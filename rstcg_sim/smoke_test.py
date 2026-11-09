"""Is Smoke Mage's edge the Smoke Runes? Variants of the Round 11 Smoke Mage against
the rest of the Round 11 field.

    python smoke_test.py 150            # greedy bot, 150 games per seat order
    python smoke_test.py 30 --smart     # playout bot
"""
import dataclasses
import sys
from multiprocessing import Pool

import hybrid_cards as h
from bot import HeuristicBot
from cards import CARDS
from engine import Game
from planner import RolloutBot

SMART = "--smart" in sys.argv
FIELD = h.round11_field()
OPPS = [k for k in FIELD if k != "Smoke Mage"]


def swap(d, out, inn):
    d = dict(d)
    for k, v in out.items():
        d[k] -= v
    for k, v in inn.items():
        d[k] = d.get(k, 0) + v
    d = {k: v for k, v in d.items() if v}
    assert sum(d.values()) == 60
    return d


SM = FIELD["Smoke Mage"]
VARIANTS = {
    "Smoke Mage (now)": (SM, None),
    "Smoke Flurry 9 -> 8": (SM, ("Smoke Flurry", {"damage": 8})),
    "Smoke Runes x3 (Fire +1)": (swap(SM, {"Smoke Rune": 1}, {"Fire Rune": 1}), None),
    "Smoke Flurry needs Air 1 + Fire 2": (SM, ("Smoke Flurry", {"req": (("Air Rune", 1), ("Fire Rune", 2))})),
    "No Smoke Runes (Air +2, Fire +2)": (swap(SM, {"Smoke Rune": 4}, {"Air Rune": 2, "Fire Rune": 2}), None),
    "Smoke Runes x2 (Air +1, Fire +1)": (swap(SM, {"Smoke Rune": 2}, {"Air Rune": 1, "Fire Rune": 1}), None),
    "Smoke Flurry 9 -> 7": (SM, ("Smoke Flurry", {"damage": 7})),
    "No Smoke Runes, Flurry 7": (swap(SM, {"Smoke Rune": 4}, {"Air Rune": 2, "Fire Rune": 2}), ("Smoke Flurry", {"damage": 7})),
}


def bot(seed):
    return RolloutBot(seed) if SMART else HeuristicBot(seed)


def _job(args):
    name, opp, seeds = args
    deck, tweak = VARIANTS[name]
    original = CARDS[tweak[0]] if tweak else None
    if tweak:
        CARDS[tweak[0]] = dataclasses.replace(original, **tweak[1])
    wins = 0
    for s in seeds:
        for seat in (0, 1):
            decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
            g = Game(decks, [bot(s * 2 + 1), bot(s * 2 + 2)], seed=s)
            g.run()
            wins += g.winner == seat
    if tweak:
        CARDS[tweak[0]] = original
    return name, opp, 100 * wins / (2 * len(seeds))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0])
    if len(args) > 1:                    # only variants whose name contains this
        keep = args[1].split("|")       # e.g. "Flurry 9 -> 8|x3"
        VARIANTS = {k: v for k, v in VARIANTS.items() if any(x in k for x in keep) or k == "Smoke Mage (now)"}
    jobs, seed = [], 1
    for name in VARIANTS:
        for opp in OPPS:
            jobs.append((name, opp, list(range(seed, seed + n))))
            seed += n
    res = {}
    with Pool(2) as pool:
        for name, opp, r in pool.imap_unordered(_job, jobs):
            res[(name, opp)] = r
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order")
    show = ("Lean Melee", "Lean Mage", "Lean Ranged", "Thrown Ranged")
    print(f"{'':34} {'field':>6}  " + "  ".join(f"{o[:12]:>12}" for o in show))
    for name in VARIANTS:
        avg = sum(res[(name, o)] for o in OPPS) / len(OPPS)
        print(f"{name:34} {avg:5.0f}%  " + "  ".join(f"{res[(name, o)]:11.0f}%" for o in show))
