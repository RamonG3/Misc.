"""Round 22 parity: decks running the five changed cards (Smite, Anti-dragon Shield, Grand Exchange, Fire Blast,
Fire Wave) against six field decks, in the JS engine and the Python sim. python web/test/parity22.py 300"""
import json, math, os, subprocess, sys
from multiprocessing import Pool

H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(H, '..', '..'))
import sweep
from bot import HeuristicBot
from engine import Game

CARDS5 = ("Smite", "Anti-dragon Shield", "Grand Exchange", "Fire Blast", "Fire Wave")
OPPS = ("Lean Melee", "Rune Thrown", "Lean Ranged", "Lean Mage", "Steam Mage", "Fire Mage")


def py(args):
    k, da, db, n = args
    w = 0
    for s in range(1, n + 1):
        seed = 7_000_000 + k * 10000 + s
        g = Game([da, db], [HeuristicBot(seed * 2 + 1), HeuristicBot(seed * 2 + 2)], seed=seed)
        g.run()
        w += g.winner == 0
    return k, w / n


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    T, _, _ = sweep.tests()
    spec = []
    for c in CARDS5:
        d = T[c][0]["deck"]
        for o in OPPS:
            spec.append({"a": c, "b": o, "da": d, "db": sweep.FIELD[o]})
    sp, out = os.path.join(H, 'spec22.json'), os.path.join(H, 'out22.json')
    json.dump(spec, open(sp, 'w'))
    js = subprocess.Popen(['node', os.path.join(H, 'spec.js'), sp, str(n), out])
    with Pool(1) as pool:
        pyr = dict(pool.imap_unordered(py, [(k, m["da"], m["db"], n) for k, m in enumerate(spec)]))
    js.wait()
    jsr = json.load(open(out))
    zs = []
    for k, m in enumerate(spec):
        p1, p2 = pyr[k], jsr[k][0]
        pp = (p1 + p2) / 2
        se = math.sqrt(max(pp * (1 - pp), 1e-9) * 2 / n)
        zs.append((abs(p1 - p2) / se, m["a"], m["b"], p1, p2))
    zs.sort(reverse=True)
    print(f"{len(spec)} matchups x {n} games: mean |z| {sum(z for z, *_ in zs) / len(zs):.2f}, "
          f"above 2.5: {sum(z > 2.5 for z, *_ in zs)}, above 3: {sum(z > 3 for z, *_ in zs)}")
    for z, a, b, p1, p2 in zs[:5]:
        print(f"  z {z:.1f}  {a} deck vs {b}: py {p1:.2f} js {p2:.2f}")


if __name__ == '__main__':
    main()
