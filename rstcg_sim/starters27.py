"""Round 27: rebuilt starter decks. Each starter carries a little of everything its style needs: two material
tiers, weapons and their tricks, own-style armor, shields, food, a basic and an elite prayer, an answer to the
style that beats it, NPCs, a location, a quest, a destroy card, an item, and a suggested cape.

    python starters27.py 200            # starters vs each other (no capes and with capes), and vs the field
    python starters27.py 200 --smart
"""
import sys
from collections import defaultdict
from multiprocessing import Pool

import sweep
import cards
from engine import Game

from starter_lists import NEW, CAPE
SETS = {"old": cards.STARTER_DECKS_R26, "new": NEW}
for nm, d in NEW.items():
    assert sum(d.values()) == 60, (nm, sum(d.values()))
    for c, n in d.items():
        assert c in cards.CARDS, c
        assert n <= 4 or cards.CARDS[c].kind == "material", c


def _job(a):
    sa, x, sb, y, capes, seat, seeds = a
    da, db = SETS[sa][x] if sa in SETS else sweep.FIELD[x], SETS[sb][y] if sb in SETS else sweep.FIELD[y]
    w = 0
    for s in seeds:
        decks = [da, db] if seat == 0 else [db, da]
        rules = None
        if capes:
            cs = [CAPE.get(x), CAPE.get(y)]
            rules = {"capes": cs if seat == 0 else cs[::-1]}
        g = Game(decks, [sweep.BOT(s * 2 + 1), sweep.BOT(s * 2 + 2)], seed=s, rules=rules)
        g.run()
        w += g.winner == seat
    return a[:5], w, len(seeds)


def main():
    n = int([a for a in sys.argv[1:] if not a.startswith("--")][0])
    names = list(NEW)
    jobs = []
    for st in ("old", "new"):
        for i, x in enumerate(names):
            for y in names[i + 1:]:
                for capes in (False, True):
                    for seat in (0, 1):
                        b = 2_000_000 + seat * 10_000
                        jobs.append((st, x, st, y, capes, seat, list(range(b, b + n))))
        for x in names:
            for opp in sweep.FIELD:
                for seat in (0, 1):
                    b = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                    jobs.append((st, x, "field", opp, False, seat, list(range(b, b + max(n // 4, 10)))))
    print(f"{len(jobs)} jobs", file=sys.stderr, flush=True)
    res = defaultdict(lambda: [0, 0])
    with Pool(2) as p:
        for k, w, g in p.imap_unordered(_job, jobs, chunksize=1):
            r = res[k]
            r[0] += w
            r[1] += g
    print(("Smart" if sweep.SMART else "Greedy") + f" bot, {n} games per seat (field: {max(n // 4, 10)})\n")
    for st in ("old", "new"):
        print(f"== {st} starters ==")
        for capes in (False, True):
            tot = defaultdict(lambda: [0, 0])
            print(f"  head to head, {'with suggested capes' if capes else 'no capes'}:")
            for i, x in enumerate(names):
                for y in names[i + 1:]:
                    w = sum(res[(st, x, st, y, capes)][:1]) if False else res[(st, x, st, y, capes)][0]
                    g = res[(st, x, st, y, capes)][1]
                    print(f"    {x:9} vs {y:9} {100 * w / g:5.1f}%")
                    tot[x][0] += w; tot[x][1] += g; tot[y][0] += g - w; tot[y][1] += g
            print("    overall: " + ", ".join(f"{k} {100 * a / b:.1f}" for k, (a, b) in tot.items()))
        f = {x: (sum(res[(st, x, "field", o, False)][0] for o in sweep.FIELD),
                 sum(res[(st, x, "field", o, False)][1] for o in sweep.FIELD)) for x in names}
        print("  vs the 20-deck field: " + ", ".join(f"{k} {100 * a / b:.1f}" for k, (a, b) in f.items()) + "\n")


if __name__ == "__main__":
    main()
