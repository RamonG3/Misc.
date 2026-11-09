"""Round 24: Protect prayers. Each Protect goes in a deck that loses to the style it protects from (the Round 21
test had them in the wrong decks), replacing a low-value card. Bots value a halving Protect honestly (half the hit).
No field deck runs Protect, so the rule variants only change the deck under test.

vs replaced = deck with Protect - host as it stands, same seeds, vs the 19 other field decks, both seats; also
split out vs the decks of the style it protects from.

    python r25.py 100
    python r25.py 8 --smart
"""
import sys
from collections import defaultdict
from multiprocessing import Pool

import bestof
import sweep
from bot import HeuristicBot
from engine import Game
from tier3 import swap

HeuristicBot.honest_protect = True
F = sweep.FIELD
STYLE = {k: s for k, (s, _) in bestof.D.items()}
STYLE = {k: STYLE.get(k, "magic") for k in F}

TESTS = {   # card: [(host, cut, style it protects from)]
    "Protect from Magic": [("Lean Melee", {"Stab": 2}), ("Rune Melee", {"Stab": 2})],
    "Protect from Missiles": [("Lean Mage", {"Wooden Shield": 2}), ("Water Mage", {"Wooden Shield": 2})],
    "Protect from Melee": [("Lean Ranged", {"Longrange": 2}), ("Thrown Ranged", {"Longrange": 2})],
}
TARGET = {"Protect from Magic": "magic", "Protect from Missiles": "ranged", "Protect from Melee": "melee"}
if "--final" in sys.argv:
    TESTS = {"Protect from Magic": [("Lean Melee", {"Stab": 2}), ("Rune Melee", {"Stab": 2})],
             "Protect from Missiles": [("Lean Mage", {"Wizards' Tower": 2}), ("Smoke Mage", {"Wizards' Tower": 2})],
             "Protect from Melee": [("Lean Ranged", {"Longrange": 2}), ("Thrown Ranged", {"Longrange": 2})]}
if "--missiles" in sys.argv:
    TESTS = {"Protect from Missiles": [("Lean Mage", {"Wizards' Tower": 2}), ("Water Mage", {"Wizards' Tower": 2}),
                                       ("Smoke Mage", {"Wizards' Tower": 2})]}
VARIANTS = {
    "printed (halve 1 attack)": {},
    "block 1 attack": {"protect_halves": False},
    "halve rest of turn": {"protect_turn": True},
    "block 1 + halve rest of turn": {"protect_halves": False, "protect_turn": True},
    "any style, halve rest of turn": {"protect_any": True, "protect_turn": True},
}
if "--missiles" in sys.argv or "--final" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed (halve 1 attack)", "block 1 attack")}
DECKS = {}


def _job(a):
    key, rules, opp, seat, seeds = a
    d = DECKS[key]
    w = 0
    for s in seeds:
        decks = [d, F[opp]] if seat == 0 else [F[opp], d]
        g = Game(decks, [sweep.BOT(s * 2 + 1), sweep.BOT(s * 2 + 2)], seed=s, rules=rules)
        g.run()
        w += g.winner == seat
    return key, tuple(sorted(rules.items())), opp, w, len(seeds)


def main():
    n = int([a for a in sys.argv[1:] if not a.startswith("--")][0])
    jobs = []
    for card, hs in TESTS.items():
        for host, cut in hs:
            DECKS[f"{host}|host"] = F[host]
            DECKS[f"{card}|{host}"] = swap(F[host], cut, {card: 2})
            for vn, rules in VARIANTS.items():
                for key in (f"{card}|{host}",) + ((f"{host}|host",) if vn.startswith("printed") else ()):
                    for opp in sweep.OPPS:
                        if opp == host:
                            continue
                        for seat in (0, 1):
                            b = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                            jobs.append((key, rules, opp, seat, list(range(b, b + n))))
    jobs = list({(j[0], tuple(sorted(j[1].items())), j[2], j[3]): j for j in jobs}.values())
    print(f"{len(jobs)} jobs", file=sys.stderr, flush=True)
    res = defaultdict(lambda: [0, 0])
    with Pool(2) as p:
        for key, rk, opp, w, g in p.imap_unordered(_job, jobs, chunksize=2):
            r = res[(key, rk, opp)]
            r[0] += w
            r[1] += g

    def wr(key, rules, opps):
        rk = tuple(sorted(rules.items()))
        w = sum(res[(key, rk, o)][0] for o in opps)
        g = sum(res[(key, rk, o)][1] for o in opps)
        return 100 * w / g
    print(("Smart" if sweep.SMART else "Greedy") + f" bot, {n} games per opponent per seat. "
          "Cells: vs replaced, all opponents / vs the style it protects from\n")
    print(f"{'card in host':38}" + "".join(f"{v[:24]:>26}" for v in VARIANTS))
    for card, hs in TESTS.items():
        for host, cut in hs:
            opps = [o for o in sweep.OPPS if o != host]
            tgt = [o for o in opps if STYLE[o] == TARGET[card]]
            h_all, h_t = wr(f"{host}|host", {}, opps), wr(f"{host}|host", {}, tgt)
            cells = []
            for vn, rules in VARIANTS.items():
                k = f"{card}|{host}"
                cells.append(f"{wr(k, rules, opps) - h_all:+5.1f} / {wr(k, rules, tgt) - h_t:+5.1f}")
            print(f"{card + ' in ' + host:38}" + "".join(f"{c:>26}" for c in cells))


if __name__ == "__main__":
    main()
