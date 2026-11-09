"""Round 23: cape needs, re-tested under current rules (Round 22 armor; decks went from Cloth/Leather to runes/logs).

Each cape on its natural host (sweep.cape_tests) and on a strong host, at the printed need and at candidates.
Value = the host's win rate bringing the cape (opponent brings none) minus the same host bringing no cape, same seeds,
against the 19 other field decks, both seats.

    python r24.py 100            # greedy
    python r24.py 8 --smart      # smart bot, chosen needs only (--final)
"""
import sys
from multiprocessing import Pool

import sweep
from capes import CAPES
from engine import Game

F = sweep.FIELD
CAND = {"Smithing Cape": (6, 7), "Woodcutting Cape": (7, 8), "Runecrafting Cape": (7, 8),
        "Ranged Cape": (3, 2), "Prayer Cape": (3,), "Cooking Cape": (4,), "Attack Cape": (7,), "Magic Cape": (),
        "Defence Cape": ()}
FINAL = {"Smithing Cape": 6, "Woodcutting Cape": 7, "Runecrafting Cape": 7, "Ranged Cape": 3}
if "--ranged" in sys.argv:
    FINAL = {"Ranged Cape": 3}


def hosts():
    T, qp, pm = sweep.tests()
    nat = sweep.cape_tests(qp, pm)
    h = [(c,) + nat[c] for c in nat if c != "Quest Point Cape"]
    h += [("Smithing Cape", "Rune Thrown", F["Rune Thrown"]), ("Woodcutting Cape", "Yew Ranged", F["Yew Ranged"]),
          ("Runecrafting Cape", "Wave Mage", F["Wave Mage"]), ("Attack Cape", "Rune Melee", F["Rune Melee"]),
          ("Magic Cape", "Mist Mage", F["Mist Mage"]), ("Runecrafting Cape", "Steam Mage", F["Steam Mage"]),
          ("Ranged Cape", "Rune Thrown", F["Rune Thrown"])]
    return h


DK = {}


def _job(a):
    cape, hn, need, opp, seat, seeds = a
    deck = DK[(cape, hn)]
    w = 0
    for s in seeds:
        decks = [deck, F[opp]] if seat == 0 else [F[opp], deck]
        rules = None
        if need != 0:
            rules = {"capes": [cape, None] if seat == 0 else [None, cape]}
            if need:
                rules["cape_need"] = {cape: need}
        g = Game(decks, [sweep.BOT(s * 2 + 1), sweep.BOT(s * 2 + 2)], seed=s, rules=rules)
        g.run()
        w += g.winner == seat
    return cape, hn, need, w, len(seeds)


def main():
    n = int([a for a in sys.argv[1:] if not a.startswith("--")][0])
    hs = hosts()
    if "--final" in sys.argv:
        hs = [h for h in hs if h[0] in FINAL]
    DK.update({(c, hn): d for c, hn, d in hs})
    jobs = []
    for c, hn, d in hs:
        needs = (0, None) + ((FINAL[c],) if "--final" in sys.argv else CAND.get(c, ()))
        for need in needs:
            for opp in sweep.OPPS:
                if opp == hn:
                    continue
                for seat in (0, 1):
                    b = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                    jobs.append((c, hn, need, opp, seat, list(range(b, b + n))))
    print(f"{len(jobs)} jobs", file=sys.stderr, flush=True)
    res = {}
    with Pool(2) as p:
        for c, hn, need, w, g in p.imap_unordered(_job, jobs, chunksize=2):
            r = res.setdefault((c, hn, need), [0, 0])
            r[0] += w
            r[1] += g
    wr = {k: 100 * w / g for k, (w, g) in res.items()}
    print(("Smart" if sweep.SMART else "Greedy") + f" bot, {n} games per opponent per seat. Value = with cape - without\n")
    print(f"{'cape':18} {'host':26} {'no cape':>7}   printed   candidates")
    for c, hn, d in hs:
        b = wr[(c, hn, 0)]
        s = f"{c:18} {hn:26} {b:7.1f}   {CAPES[c][2]:>2}: {wr[(c, hn, None)] - b:+5.1f}"
        needs = (FINAL[c],) if "--final" in sys.argv else CAND.get(c, ())
        for k in needs:
            s += f"   {k:>2}: {wr[(c, hn, k)] - b:+5.1f}"
        print(s)


if __name__ == "__main__":
    main()
