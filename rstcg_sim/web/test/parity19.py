"""Round 19 parity: every exported deck that runs Iban's Staff or Staff of Light against every other
exported deck, in the JS engine and the Python sim. python web/test/parity19.py 400"""
import json, math, os, subprocess, sys
from multiprocessing import Pool

H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(H, '..', '..'))
from bot import HeuristicBot
from engine import Game

DATA = json.load(open(os.path.join(H, '..', 'cards.json')))
SIM_NAME = {"Varrock Teleport": "Varrock Teleport Tablet"}     # printed name -> simulator name (Round 26)
D = {d['name']: {SIM_NAME.get(k, k): v for k, v in (d['cards'].items() if isinstance(d['cards'], dict) else d['cards'])}
     for d in DATA['decks']}


def staves(deck):
    return any(c in deck for c in ("Iban's Staff", "Staff of Light"))


def py(args):
    a, b, n, k = args
    w = 0
    for s in range(1, n + 1):
        seed = 7_000_000 + k * 10000 + s
        g = Game([D[a], D[b]], [HeuristicBot(seed * 2 + 1), HeuristicBot(seed * 2 + 2)], seed=seed)
        g.run()
        w += g.winner == 0
    return k, w / n


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    names = list(D)
    spec = [{"a": a, "b": b} for a in names if staves(D[a]) for b in names if b != a]
    sp, out = os.path.join(H, 'spec19.json'), os.path.join(H, 'out19.json')
    json.dump(spec, open(sp, 'w'))
    js = subprocess.Popen(['node', os.path.join(H, 'spec.js'), sp, str(n), out])
    with Pool(1) as pool:
        pyr = dict(pool.imap_unordered(py, [(m["a"], m["b"], n, k) for k, m in enumerate(spec)]))
    js.wait()
    jsr = json.load(open(out))
    zs = []
    for k, m in enumerate(spec):
        p1, p2 = pyr[k], jsr[k][0]
        pool_p = (p1 + p2) / 2
        se = math.sqrt(max(pool_p * (1 - pool_p), 1e-9) * 2 / n)
        zs.append((abs(p1 - p2) / se, m["a"], m["b"], p1, p2))
    zs.sort(reverse=True)
    print(f"{len(spec)} matchups x {n} games: mean |z| {sum(z for z, *_ in zs) / len(zs):.2f}, "
          f"above 2.5: {sum(z > 2.5 for z, *_ in zs)}, above 3: {sum(z > 3 for z, *_ in zs)}")
    for z, a, b, p1, p2 in zs[:5]:
        print(f"  z {z:.1f}  {a} vs {b}: py {p1:.2f} js {p2:.2f}")


if __name__ == '__main__':
    main()
