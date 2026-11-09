"""How starting Life Points change the game.

For each LP total: the Round 7 field (every deck against every other), game
length, how games end, first-player advantage, and what setup-heavy packages
are worth. Respawn costs a fifth of starting LP; food can't heal above it.

    python lp_sweep.py 60            # 60 games per seat order
    python lp_sweep.py 60 30 50 75   # only these LP totals
"""
import sys
from collections import Counter
from multiprocessing import Pool

import new_cards as nc
from simulate import play

PACKAGES = ["Lean Melee + Piety", "Lean Ranged + Rigour", "Smoke Mage + Augury", "Lean Mage + specs",
            "Lean Ranged + specs", "Lean Melee + quests", "Lean Mage + quests"]


def rules_for(lp):
    return {"deck_life": False, "max_respawns": 1, "start_lp": lp, "max_lp": lp,
            "respawn_cost": max(1, round(lp / 5))}


def _job(args):
    a, b, seeds, decks, rules = args
    return a, b, [play(a, b, s, rules, decks) for s in seeds]


def sweep(lp, n, pool, rules=None):
    """lp is a label; rules default to rules_for(lp)."""
    rules = rules or rules_for(lp)
    field = nc.round7_field()
    v = nc.variants()
    pk = {k: v[k][1] for k in PACKAGES}
    base = {"BASE " + v[k][0]: field_base(v[k][0]) for k in PACKAGES}
    decks = {**field, **{"P:" + k: d for k, d in pk.items()}, **base}
    names = list(field)
    jobs, seed = [], 1
    for a in names:
        for b in names:
            jobs.append((a, b, list(range(seed, seed + n)), decks, rules))
            seed += n
    for t in list(("P:" + k for k in pk)) + list(base):
        for o in nc.FIELD:
            fo = "F:" + o
            decks[fo] = nc.FIELD[o]
            jobs.append((t, fo, list(range(seed, seed + n)), decks, rules))
            jobs.append((fo, t, list(range(seed, seed + n)), decks, rules))
            seed += n
    wins, rounds, ends, seat0, draws = {}, [], Counter(), [0, 0], []
    pkg = {}
    for a, b, games in pool.map(_job, jobs):
        for gm in games:
            w = gm["winner"]
            if a in field and b in field:
                rounds.append(gm["rounds"])
                draws.append(sum(gm.get("draw_actions", [0, 0])))
                ends[gm["reason"]] += 1
                seat0[0] += w == 0
                seat0[1] += 1
                for seat, deck, opp in ((0, a, b), (1, b, a)):
                    g, t = wins.get((deck, opp), (0, 0))
                    wins[(deck, opp)] = (g + (w == seat), t + 1)
            else:
                t, seat = (a, 0) if not a.startswith("F:") else (b, 1)
                g, tt = pkg.get(t, (0, 0))
                pkg[t] = (g + (w == seat), tt + 1)
    rate = {k: 100 * g / t for k, (g, t) in wins.items()}
    avg = {a: sum(rate[(a, b)] for b in names if b != a) / (len(names) - 1) for a in names}
    pkg = {k: 100 * g / t for k, (g, t) in pkg.items()}
    return {
        "lp": lp, "avg": avg, "rate": rate, "spread": max(avg.values()) - min(avg.values()),
        "rounds": sum(rounds) / len(rounds), "ends": {k: 100 * c / sum(ends.values()) for k, c in ends.items()},
        "first": 100 * seat0[0] / seat0[1], "draws": sum(draws) / len(draws),
        "pkg": {k: pkg["P:" + k] - pkg["BASE " + v[k][0]] for k in PACKAGES},
    }


def field_base(name):
    return nc.FIELD[name]


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    lps = [int(x) for x in sys.argv[2:]] or [30, 40, 50, 60, 75]
    with Pool(2) as pool:
        out = [sweep(lp, n, pool) for lp in lps]
    print(f"{'LP':>4} {'rounds':>6} {'spread':>6} {'1st':>5} {'top deck':>22} {'Lean Melee':>10} {'Ranged v LM':>11}  ends")
    for r in out:
        top = max(r["avg"], key=r["avg"].get)
        rvm = (r["rate"][("Lean Ranged", "Lean Melee")] + r["rate"][("Thrown Ranged", "Lean Melee")]) / 2
        ends = ", ".join(f"{k} {v:.0f}%" for k, v in sorted(r["ends"].items(), key=lambda x: -x[1]))
        print(f"{r['lp']:4} {r['rounds']:6.1f} {r['spread']:6.0f} {r['first']:4.0f}% {top:>15} {r['avg'][top]:4.0f}%"
              f" {r['avg']['Lean Melee']:9.0f}% {rvm:10.0f}%  {ends}")
    print()
    print("Package value (change vs. the same deck without it):")
    print(f"{'':24}" + "".join(f"{r['lp']:>7}" for r in out))
    for k in PACKAGES:
        print(f"{k:24}" + "".join(f"{r['pkg'][k]:+7.0f}" for r in out))
    print()
    print("Field win rate by LP:")
    print(f"{'':16}" + "".join(f"{r['lp']:>7}" for r in out))
    for a in out[0]["avg"]:
        print(f"{a:16}" + "".join(f"{r['avg'][a]:6.0f}%" for r in out))
