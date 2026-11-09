"""Round 20: smart-bot (RolloutBot) check of the Fire Mage fix.

Fire Mage vs every other deck in the 20-deck field, n games per seat, both bots RolloutBot.
Compares Fire Mage lists (printed cards unless a card variant name is given).

    python smart20.py 10                       # R19 list vs L1 list
    python smart20.py 10 "B1 Fire spells shatter shields"
"""
import sys
from multiprocessing import Pool

import r19
import r20
from engine import Game
from planner import RolloutBot

D = {}


def _job(args):
    a, b, seat, seeds = args
    wins = 0
    for s in seeds:
        decks = [D[a], D[b]] if seat == 0 else [D[b], D[a]]
        g = Game(decks, [RolloutBot(s * 2 + 1), RolloutBot(s * 2 + 2)], seed=s)
        g.run()
        wins += g.winner == seat
    return b, seat, wins, len(seeds)


def check(list_name, changes, n):
    r20.apply(changes)
    D.clear()
    D.update(r19.decks(False))
    D["Fire Mage"] = r20.fire_lists()[list_name]
    jobs, seed = [], 900000
    for b in D:
        if b == "Fire Mage":
            continue
        for seat in (0, 1):
            jobs.append(("Fire Mage", b, seat, list(range(seed, seed + n))))
            seed += n
    res = {}
    with Pool(2) as pool:
        for b, seat, w, t in pool.imap_unordered(_job, jobs):
            x = res.setdefault(b, [0, 0])
            x[0] += w
            x[1] += t
    return {b: 100 * w / t for b, (w, t) in res.items()}


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 10
    changes = r20.CARD_VARIANTS[args[1]] if len(args) > 1 else {}
    cols = {}
    for ln in ("R19 list", "L1 Superheat -> Iban's"):
        cols[ln] = check(ln, changes, n)
        avg = sum(cols[ln].values()) / len(cols[ln])
        print(f"done: {ln} {avg:.1f}", file=sys.stderr, flush=True)
    names = list(next(iter(cols.values())))
    print(f"Smart bot (RolloutBot), Fire Mage vs the field, {n} games per seat ({2 * n} per opponent)\n")
    print(f"{'opponent':18}" + "".join(f"{c[:22]:>24}" for c in cols))
    for b in names:
        print(f"{b:18}" + "".join(f"{cols[c][b]:24.0f}" for c in cols))
    print(f"{'AVERAGE':18}" + "".join(f"{sum(cols[c].values()) / len(names):24.1f}" for c in cols))


if __name__ == "__main__":
    main()
