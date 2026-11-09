"""Round 21: cape need tweaks. The host deck brings the cape (opponent brings none) at the printed need
and at a tweaked need, against the other 19 field decks, both seats; value = with cape - without,
same seeds. Same hosts as sweep.py's cape tests.

    python capefix.py 100
    python capefix.py 4 --smart
"""
import sys
from multiprocessing import Pool

import sweep
from capes import CAPES
from engine import Game

BOT = sweep.BOT
TWEAKS = [("Ranged Cape", 3), ("Smithing Cape", 6), ("Cooking Cape", 4), ("Prayer Cape", 3),
          ("Magic Cape", None), ("Attack Cape", None), ("Runecrafting Cape", None)]
HOSTS = {}


def _job(args):
    cape, need, opp, seat, seeds = args
    hn, deck = HOSTS[cape]
    w = 0
    for s in seeds:
        decks = [deck, sweep.FIELD[opp]] if seat == 0 else [sweep.FIELD[opp], deck]
        rules = None
        if need != 0:
            rules = {"capes": [cape, None] if seat == 0 else [None, cape]}
            if need:
                rules["cape_need"] = {cape: need}
        g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s, rules=rules)
        g.run()
        w += g.winner == seat
    return cape, need, w, len(seeds)


def main():
    n = int([a for a in sys.argv[1:] if not a.startswith("--")][0])
    T, qp, pm = sweep.tests()
    HOSTS.update(sweep.cape_tests(qp, pm))
    jobs = []
    for cape, tweak in TWEAKS:
        hn = HOSTS[cape][0]
        for need in (0, None, tweak) if tweak else (0, None):     # 0: no cape, None: printed need
            for opp in sweep.OPPS:
                if opp == hn:
                    continue
                for seat in (0, 1):
                    base = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                    jobs.append((cape, need, opp, seat, list(range(base, base + n))))
    res = {}
    with Pool(2) as pool:
        for cape, need, w, g in pool.imap_unordered(_job, jobs, chunksize=2):
            r = res.setdefault((cape, need), [0, 0])
            r[0] += w
            r[1] += g
    wr = {k: 100 * w / g for k, (w, g) in res.items()}
    print(f"{'cape':18} {'host':26} {'no cape':>8} {'printed':>14} {'tweak':>16}")
    for cape, tweak in TWEAKS:
        hn = HOSTS[cape][0]
        base = wr[(cape, 0)]
        line = f"{cape:18} {hn:26} {base:8.1f} {CAPES[cape][2]:>3}: {wr[(cape, None)] - base:+5.1f}"
        if tweak:
            line += f"      {tweak:>3}: {wr[(cape, tweak)] - base:+5.1f}"
        print(line)


if __name__ == "__main__":
    main()
