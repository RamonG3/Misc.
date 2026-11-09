"""Dual-style decks against the starters and each other.

    python hybrids.py 400
"""
import sys

from cards import HYBRID_DECKS, STARTER_DECKS
from simulate import run_all, summarize

if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 400
    decks = {**STARTER_DECKS, **HYBRID_DECKS}
    s = summarize(run_all(n, {}, decks=decks), list(decks))
    names = list(decks)
    print(" " * 13 + "".join(f"{x:>13}" for x in names) + "   avg vs field")
    for a in names:
        others = [s["matrix"][a][b] for b in names if b != a]
        print(f"{a:>13}" + "".join(f"{100*s['matrix'][a][b]:12.0f}%" for b in names)
              + f"{100*sum(others)/len(others):12.0f}%")
    print("\\nWorst and best matchup per deck:")
    for a in names:
        others = {b: s["matrix"][a][b] for b in names if b != a}
        lo, hi = min(others, key=others.get), max(others, key=others.get)
        print(f"  {a}: worst {lo} {100*others[lo]:.0f}%, best {hi} {100*others[hi]:.0f}%")
    print("\\nDamage sources:")
    for a in names:
        sh = s["decks"][a]["damage_share"]
        print(f"  {a}: " + ", ".join(f"{k} {100*v:.0f}%" for k, v in sh.items())
              + f"; first damage turn {s['decks'][a]['median_first_damage_turn']}")
