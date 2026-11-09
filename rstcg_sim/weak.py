"""Why do the weakest decks lose? How each deck's games go, in the current rules.

    python weak.py 100
"""
import statistics
import sys
from collections import defaultdict
from multiprocessing import Pool

import new_cards as nc
from bot import HeuristicBot
from cards import CARDS
from engine import Game

ATTACK = {"swing", "free_swing", "spec", "punch"}
ATTACK_CARDS = {"spell", "ability", "rapid", "longrange"}
DEFENSE = {"food", "shield", "armor", "buff"}


class Watcher(HeuristicBot):
    """Plays like the greedy bot and records its own decisions."""
    def __init__(self, seed, log):
        super().__init__(seed)
        self.log = log

    def choose(self, g, i, legal):
        a = super().choose(g, i, legal)
        if True:
            p = g.players[i]
            k = a[0] if a[0] != "play" else CARDS[a[1]].kind
            cat = ("attack" if a[0] in ATTACK or k in ATTACK_CARDS else
                   "draw" if a[0] == "draw" else
                   "material" if k == "material" else
                   "equip" if k in ("weapon", "thrown", "bow", "staff", "arrows", "pouch") else
                   "defense" if k in DEFENSE else "other")
            self.log["acts"][cat] += 1
            if a[0] == "punch":
                self.log["acts"]["punch"] += 1
            dead = sum(1 for n in p.hand if CARDS[n].req and not p.meets(CARDS[n].req))
            self.log["dead"].append(dead)
            self.log["hand"].append(len(p.hand))
        return a


def _job(args):
    name, deck, opp, odeck, seeds = args
    log = {"acts": defaultdict(int), "dead": [], "hand": []}
    rows = []
    for s in seeds:
        for seat in (0, 1):
            w = Watcher(s, log)
            other = HeuristicBot(s + 9)
            bots = [w, other] if seat == 0 else [other, w]
            decks = [deck, odeck] if seat == 0 else [odeck, deck]
            g = Game(decks, bots, seed=s)
            g.run()
            me = seat
            rows.append({"win": g.winner == me, "first": g.first_damage_turn[me],
                         "turns": g.turn // 2 + 1,
                         "dealt": sum(v for k, v in g.stats[me].items() if k.startswith("dmg_")),
                         "taken": sum(v for k, v in g.stats[1 - me].items() if k.startswith("dmg_"))})
    return name, opp, rows, dict(log["acts"]), log["dead"], log["hand"]


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    field = nc.round7_field()
    jobs = [(a, field[a], b, field[b], list(range(1, n + 1))) for a in field for b in field if a != b]
    agg = defaultdict(lambda: {"rows": [], "acts": defaultdict(int), "dead": [], "hand": [], "vs": {}})
    with Pool(2) as pool:
        for name, opp, rows, acts, dead, hand in pool.map(_job, jobs):
            A = agg[name]
            A["rows"] += rows
            for k, v in acts.items():
                A["acts"][k] += v
            A["dead"] += dead
            A["hand"] += hand
            A["vs"][opp] = 100 * sum(r["win"] for r in rows) / len(rows)
    print(f"{'deck':16} {'win':>4} {'1st hit':>7} {'dealt/turn':>10} {'taken/turn':>10} {'dead in hand':>12}   actions: attack / material / equip / defense / draw / other")
    for name in sorted(agg, key=lambda k: -statistics.mean(r["win"] for r in agg[k]["rows"])):
        A = agg[name]
        rows = A["rows"]
        win = 100 * statistics.mean(r["win"] for r in rows)
        first = statistics.mean(r["first"] for r in rows if r["first"])
        dpt = statistics.mean(r["dealt"] / r["turns"] for r in rows)
        tpt = statistics.mean(r["taken"] / r["turns"] for r in rows)
        dead = statistics.mean(A["dead"])
        tot = sum(v for k, v in A["acts"].items() if k != "punch")
        mix = " / ".join(f"{100 * A['acts'][k] / tot:.0f}%" for k in ("attack", "material", "equip", "defense", "draw", "other"))
        worst = sorted(A["vs"].items(), key=lambda x: x[1])[:2]
        print(f"{name:16} {win:3.0f}% {first:7.1f} {dpt:10.1f} {tpt:10.1f} {dead:12.1f}   {mix}   worst: "
              + ", ".join(f"{o} {v:.0f}%" for o, v in worst))
