"""Every test deck against every other: each deck's win rate against the field.

    python field.py 200
"""
import sys
from multiprocessing import Pool

import cards
from simulate import play

FIELD = {**cards.LEAN_DECKS, **cards.OTHER_DECKS, **cards.HYBRID_DECKS, **cards.GEAR_DECKS}


def _job(args):
    a, b, seeds = args
    return a, b, [play(a, b, s, {}, FIELD)["winner"] for s in seeds]


def run(n, workers=2):
    names = list(FIELD)
    jobs, seed = [], 1
    for a in names:
        for b in names:
            jobs.append((a, b, list(range(seed, seed + n))))
            seed += n
    wins = {}
    with Pool(workers) as pool:
        for a, b, ws in pool.map(_job, jobs):
            for w in ws:
                for seat, deck, opp in ((0, a, b), (1, b, a)):
                    g, t = wins.get((deck, opp), (0, 0))
                    wins[(deck, opp)] = (g + (w == seat), t + 1)
    rate = {k: 100 * g / t for k, (g, t) in wins.items()}
    avg = {a: sum(rate[(a, b)] for b in names if b != a) / (len(names) - 1) for a in names}
    return names, rate, avg


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    names, rate, avg = run(n)
    for a in sorted(names, key=avg.get, reverse=True):
        others = {b: rate[(a, b)] for b in names if b != a}
        lo = min(others, key=others.get)
        hi = max(others, key=others.get)
        print(f"{a:15} {avg[a]:5.0f}%   worst: {lo} {others[lo]:.0f}%   best: {hi} {others[hi]:.0f}%")
