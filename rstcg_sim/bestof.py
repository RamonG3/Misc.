"""Round 17: best-of-each-style field. Four decks per style, round robin.

    python bestof.py 40 [--set=...]          # greedy
    python bestof.py 6 --smart [--set=...]   # playout bot
"""
import sys
from multiprocessing import Pool

import cards
import group1
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot
from tier3 import swap

SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot


def field():
    p = {k: d for k, (_, d) in group1.packages().items()}
    F = group1.FIELD
    yew = dict(p["Yew Ranged (built for tier 3)"])
    yew["Rigour"] = yew.pop("Sharp Eye")
    mage = swap(F["Lean Mage"], {"Air Blast": 3, "Death Rune": 3, "Wizard Robe": 2, "Banker": 1},
                {"Air Wave": 3, "Blood Rune": 3, "Infinity Top": 2, "Cloth": 1})
    d = {
        "Lean Melee": ("melee", F["Lean Melee"]),
        "Mace Melee": ("melee", p["Mace Melee (Bronze/Mithril Mace, Crush)"]),
        "Rune Melee": ("melee", p["Rune Melee (built for tier 3)"]),
        "Specials Melee": ("melee", p["Lean Melee + Ancient Mace, Excalibur, Anchor"]),
        "Lean Ranged": ("ranged", F["Lean Ranged"]),
        "Thrown Ranged": ("ranged", F["Thrown Ranged"]),
        "Rune Thrown": ("ranged", p["Rune Thrown (built for tier 3)"]),
        "Yew Ranged": ("ranged", yew),
        "Lean Mage": ("magic", F["Lean Mage"]),
        "Smoke Mage": ("magic", F["Smoke Mage"]),
        "Earth Mage": ("magic", p["Earth Mage, modern build"]),
        "Wave Mage": ("magic", mage),
    }
    for k, (_, deck) in d.items():
        cards.check_decks({k: deck})
    return d


D = field()


def _job(args):
    a, b, seeds = args
    out = []
    for s in seeds:
        g = Game([D[a][1], D[b][1]], [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s)
        g.run()
        out.append(g.winner)
    return a, b, out


def main():
    n = int([x for x in sys.argv[1:] if not x.startswith("--")][0])
    names = list(D)
    jobs, seed = [], 1
    for a in names:
        for b in names:
            if a != b:
                jobs.append((a, b, list(range(seed, seed + n))))
                seed += n
    w = {}
    with Pool(2) as pool:
        for a, b, out in pool.imap_unordered(_job, jobs):
            for x in out:
                for seat, me, op in ((0, a, b), (1, b, a)):
                    t = w.setdefault((me, op), [0, 0])
                    t[0] += x == seat
                    t[1] += 1
    avg = {a: sum(100 * w[(a, b)][0] / w[(a, b)][1] for b in names if b != a) / (len(names) - 1) for a in names}
    print(("Smart" if SMART else "Greedy") + f" bot, best-of-each-style field, {n} games per ordered pairing"
          + "".join(f" {x}" for x in sys.argv if x.startswith("--set")))
    for st in ("melee", "ranged", "magic"):
        ds = [a for a in names if D[a][0] == st]
        print(f"  {st.title():7} average {sum(avg[a] for a in ds) / len(ds):3.0f}%, best {max(avg[a] for a in ds):3.0f}%")
        for a in sorted(ds, key=lambda a: -avg[a]):
            print(f"      {a:15} {avg[a]:3.0f}%")


if __name__ == "__main__":
    main()
