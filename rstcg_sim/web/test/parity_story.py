"""Story Mode parity (2026-11-18): each story opponent's deck against the three starters, both seats, with the
boss fights' Location locked in play, in the JS engine and the Python sim. Covers the new rules: the locked
Location, Draynor Manor / Moonlit Circle, spells that pierce or shatter shields, and Bites that heal.
python web/test/parity_story.py 400"""
import json, math, os, subprocess, sys
from multiprocessing import Pool

H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(H, '..', '..'))
from bot import HeuristicBot
from engine import Game
import cards

import parity19 as p19
D = p19.D
LOCK = cards.STORY_LOCATIONS


def spec():
    out = []
    for opp in cards.STORY_DECKS:
        rules = {"locked_location": LOCK[opp]} if opp in LOCK else None
        for st in ("Melee", "Ranged", "Air Mage"):
            out.append({"a": st, "b": opp, "rules": rules})
            out.append({"a": opp, "b": st, "rules": rules})
    return out


def py(args):
    m, n, k = args
    w = 0
    for s in range(1, n + 1):
        seed = k * 10000 + s
        g = Game([D[m["a"]], D[m["b"]]], [HeuristicBot(seed * 2 + 1), HeuristicBot(seed * 2 + 2)], seed=seed, rules=m["rules"])
        g.run()
        w += g.winner == 0
    return k, w / n


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    sp = spec()
    spf, out = os.path.join(H, 'spec_story.json'), os.path.join(H, 'out_story.json')
    json.dump(sp, open(spf, 'w'))
    js = subprocess.Popen(['node', os.path.join(H, 'spec.js'), spf, str(n), out])
    with Pool(4) as pool:
        pyr = dict(pool.imap_unordered(py, [(m, n, k) for k, m in enumerate(sp)]))
    js.wait()
    jsr = json.load(open(out))
    zs = []
    for k, m in enumerate(sp):
        p1, p2 = pyr[k], jsr[k][0]
        pool_p = (p1 + p2) / 2
        se = math.sqrt(max(pool_p * (1 - pool_p), 1e-9) * 2 / n)
        zs.append((abs(p1 - p2) / se, m["a"], m["b"], p1, p2))
    zs.sort(reverse=True)
    print(f"{len(sp)} matchups x {n} games: mean |z| {sum(z for z, *_ in zs) / len(zs):.2f}, "
          f"above 2.5: {sum(z > 2.5 for z, *_ in zs)}, above 3: {sum(z > 3 for z, *_ in zs)}")
    for z, a, b, p1, p2 in zs[:6]:
        print(f"  z {z:.1f}  {a} vs {b}: py {p1:.2f} js {p2:.2f}")


if __name__ == '__main__':
    main()
