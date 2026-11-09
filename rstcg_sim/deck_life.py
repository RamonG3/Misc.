"""Deck as life (Harry Potter TCG style) against the 50 LP game.

Each point of damage discards the top card of your deck; healing puts random
cards from your discard pile on the bottom; you lose when your deck is empty and
you take damage or must draw. No Respawn.

    python deck_life.py 60
"""
import sys
from multiprocessing import Pool

import simulate
from engine import DECK_LIFE
from lp_sweep import PACKAGES, rules_for, sweep
from collections import defaultdict


def starters(rules, n=500):
    games = simulate.run_all(n, rules)
    w = defaultdict(lambda: [0, 0])
    for gm in games:
        a, b = gm["first"], gm["second"]
        if a == b:
            continue
        for seat, d, o in ((0, a, b), (1, b, a)):
            w[(d, o)][0] += gm["winner"] == seat
            w[(d, o)][1] += 1
    return {f"{d} v {o}": 100 * x / t for (d, o), (x, t) in sorted(w.items()) if d < o}


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    modes = [("50 LP", rules_for(50)), ("Deck as life", DECK_LIFE)]
    with Pool(2) as pool:
        out = [(label, sweep(label, n, pool, rules)) for label, rules in modes]
    print(f"{'':14} {'rounds':>6} {'draw acts':>9} {'spread':>6} {'1st':>5} {'Lean Melee':>10} {'Ranged v LM':>11}")
    for label, r in out:
        rvm = (r["rate"][("Lean Ranged", "Lean Melee")] + r["rate"][("Thrown Ranged", "Lean Melee")]) / 2
        print(f"{label:14} {r['rounds']:6.1f} {r['draws']:9.1f} {r['spread']:6.0f} {r['first']:4.0f}% "
              f"{r['avg']['Lean Melee']:9.0f}% {rvm:10.0f}%   ends: {r['ends']}")
    print("\nField win rate:")
    for a in out[0][1]["avg"]:
        print(f"  {a:16}" + "".join(f"{r['avg'][a]:6.0f}%" for _, r in out))
    print("\nPackage value (change vs. same deck without it):")
    for k in PACKAGES:
        print(f"  {k:24}" + "".join(f"{r['pkg'][k]:+6.0f}" for _, r in out))
    print("\nStarters:")
    for label, rules in modes:
        print(f"  {label:14}", "  ".join(f"{k} {v:.0f}%" for k, v in starters(rules).items()))
