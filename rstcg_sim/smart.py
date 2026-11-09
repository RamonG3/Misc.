"""Round 8: rerun the Round 6-7 tests with the playout bot (planner.RolloutBot) on both sides.

    python smart.py packages 10     # key Round 7 packages vs the reaction field, 10 games per seat order
    python smart.py field 10        # Round 6 field and Round 7 field, every deck against every other
    python smart.py packages 10 --greedy   # same tests with the greedy bot, for comparison
    python smart.py confirm 20 "Lean Ranged + specs"   # rerun chosen packages with more games
    python smart.py field7 10 --deck-life              # Round 7 field in the deck-as-life game

Slow: a game takes about 1.5 s with two playout bots.
"""
import sys
from multiprocessing import Pool

import new_cards as nc
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot

GREEDY = "--greedy" in sys.argv
RULES = None
if "--deck-life" in sys.argv:
    from engine import DECK_LIFE
    RULES = DECK_LIFE

KEY = ["Lean Melee + Burst", "Lean Melee + specs", "Lean Melee + Piety", "Lean Melee + Redemption",
       "Lean Melee + quests", "Lean Ranged + Sharp Eye", "Lean Ranged + specs", "Lean Ranged + Rigour",
       "Lean Mage + Mystic Will", "Lean Mage + specs", "Lean Mage + quests", "Smoke Mage + Iban",
       "Smoke Mage + Augury", "Quest Mage 3x (cape)", "Quest Smoke 3x (cape)"]


def bot(seed):
    return HeuristicBot(seed) if GREEDY else RolloutBot(seed)


def play(d1, d2, seed, rules=None):
    g = Game([d1, d2], [bot(seed * 2 + 1), bot(seed * 2 + 2)], seed=seed, rules=rules or RULES)
    g.run()
    return g.winner


def _pkg(args):
    name, deck, opp, seeds = args
    f = nc.FIELD[opp]
    return name, opp, [play(deck, f, s) == 0 for s in seeds] + [play(f, deck, s) == 1 for s in seeds]


def packages(n, keys=None, seed0=1):
    keys = keys or KEY
    v = nc.variants()
    tests = {k: v[k][1] for k in keys}
    bases = sorted({v[k][0] for k in keys})
    tests.update({"BASE " + b: nc.FIELD[b] for b in bases})
    jobs, seed = [], seed0
    for name, deck in tests.items():
        for opp in nc.FIELD:
            jobs.append((name, deck, opp, list(range(seed, seed + n))))
            seed += n
    res = {}
    with Pool(2) as pool:
        for name, opp, out in pool.imap_unordered(_pkg, jobs):
            res[(name, opp)] = 100 * sum(out) / len(out)
    avg = {t: sum(res[(t, o)] for o in nc.FIELD) / len(nc.FIELD) for t in tests}
    for b in bases:
        for t in ["BASE " + b] + [k for k in keys if v[k][0] == b]:
            print(f"{t:28} {avg[t]:5.0f}% {avg[t] - avg['BASE ' + b]:+5.0f}   vs Lean Melee {res[(t, 'Lean Melee')]:4.0f}%")
        print()


def _fld(args):
    label, a, b, seeds = args
    if label == 11:
        import hybrid_cards
        decks = hybrid_cards.round11_field()
    else:
        decks = nc.FIELD if label == 6 else nc.round7_field()
    return label, a, b, [play(decks[a], decks[b], s) for s in seeds]


def field(n, labels=(6, 7)):
    names = list(nc.FIELD)
    jobs, seed = [], 1
    for label in labels:
        for a in names:
            for b in names:
                if a != b:
                    jobs.append((label, a, b, list(range(seed, seed + n))))
                    seed += n
    wins = {}
    with Pool(2) as pool:
        for label, a, b, ws in pool.imap_unordered(_fld, jobs):
            for w in ws:
                for seat, deck, opp in ((0, a, b), (1, b, a)):
                    g, t = wins.get((label, deck, opp), (0, 0))
                    wins[(label, deck, opp)] = (g + (w == seat), t + 1)
    for label in labels:
        rate = {(d, o): 100 * g / t for (l, d, o), (g, t) in wins.items() if l == label}
        avg = {a: sum(rate[(a, b)] for b in names if b != a) / (len(names) - 1) for a in names}
        print(f"Round {label} field, spread {max(avg.values()) - min(avg.values()):.0f}")
        for a in sorted(avg, key=avg.get, reverse=True):
            vm = rate.get((a, "Lean Melee"))
            print(f"  {a:15} {avg[a]:4.0f}%   vs Lean Melee {vm if vm is not None else 50:4.0f}%")
        tri = [rate[(r, m)] for r in ("Lean Ranged", "Thrown Ranged") for m in ("Lean Melee",)]
        print("  Ranged vs Lean Melee:", ", ".join(f"{x:.0f}%" for x in tri))


if __name__ == "__main__":
    mode, n = sys.argv[1], int(sys.argv[2])
    if mode == "confirm":      # python smart.py confirm 20 "Lean Ranged + specs" "Quest Mage 3x (cape)"
        packages(n, [a for a in sys.argv[3:] if not a.startswith("--")], seed0=500001)   # fresh games
    else:
        {"packages": packages, "field": field, "field7": lambda k: field(k, (7,)),
         "field11": lambda k: field(k, (11,))}[mode](n)
