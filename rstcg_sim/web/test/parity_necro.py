"""Necromancy parity (2026-11-27): the five Necromancy decks against the field, with capes, in the Python engine
and bot. Compare with `node web/test/parity_necro.js N`.   python web/test/parity_necro.py 100"""
import json, sys
from multiprocessing import Pool
sys.path.insert(0, __import__("os").path.join(__import__("os").path.dirname(__file__), "..", ".."))
from engine import Game
from bot import HeuristicBot as HB
DATA = json.load(open(__import__("os").path.join(__import__("os").path.dirname(__file__), "..", "cards.json")))
D = {d["name"]: d for d in DATA["decks"]}
ALL_NEW = ["Necromancy", "Lean Necrotic", "Skeleton Melee", "Zombie Ranged", "Ghost Mage"]
NEW = sys.argv[2].split(",") if len(sys.argv) > 2 else ALL_NEW     # or any decks, to compare with the old ones
FIELD = [d["name"] for d in DATA["decks"] if d["group"] in ("Starter decks", "Tournament decks") and d["name"] not in ALL_NEW]
N = int(sys.argv[1]) if len(sys.argv) > 1 else 100

def one(args):
    me, opp, cape = args
    w = 0
    for s in range(N):
        for seat in (0, 1):
            ds, cs = [None, None], [None, None]
            ds[seat], ds[1 - seat] = D[me]["cards"], D[opp]["cards"]
            cs[seat], cs[1 - seat] = cape, D[opp]["capes"][0]
            g = Game(ds, [HB(2 * s + 1 + 7 * seat), HB(2 * s + 2 + 7 * seat)], seed=700_000 + s, rules={"capes": cs})
            g.run()
            w += g.winner == seat
    return w / (2 * N)

if __name__ == "__main__":
    with Pool() as pool:
        for me in NEW:
            rs = []
            for cape in D[me]["capes"]:
                r = pool.map(one, [(me, o, cape) for o in FIELD])
                rs.append(100 * sum(r) / len(r))
            print(f"{me:16s} with capes {sum(rs) / len(rs):5.1f}%  ({', '.join(f'{c} {x:.1f}' for c, x in zip(D[me]['capes'], rs))})", flush=True)
