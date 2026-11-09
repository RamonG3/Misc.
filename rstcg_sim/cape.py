"""Quest Point Cape with a countdown: turtle quest decks against the Round 11 field.

    python cape.py 60                 # sweep cape_turns x cape_qp x free quest steps, greedy bot
    python cape.py 8 --smart          # a few settings with the playout bot
"""
import itertools
import sys
from collections import Counter
from multiprocessing import Pool

import hybrid_cards as h
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot

SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot

FIELD = h.round11_field()
DEF = h.DEF + ("Air Blast", "Banker", "Rune Pouch", "Stab", "Bronze Dagger", "Wizards' Tower", "Longrange")


def cape_decks():
    f = FIELD
    return {
        "Cape Mage": ("Lean Mage", h.add(f["Lean Mage"], {"Romeo & Juliet": 3, "Goblin Diplomacy": 2,
                                                          "Prince Ali Rescue": 2, "Quest Point Cape": 2},
                                         ("Banker", "Rune Pouch", "Air Blast", "Wizards' Tower", "Staff of Light",
                                          "Archmage Sedridor"))),
        "Cape Melee": ("Lean Melee", h.add(f["Lean Melee"], {"Romeo & Juliet": 3, "Goblin Diplomacy": 2,
                                                            "Vampyre Slayer": 2, "Quest Point Cape": 2},
                                           ("Stab", "Bronze Dagger", "Vanaka", "Doric", "Mithril Scimitar",
                                            "Bronze Scimitar"))),
        "Cape Thrown": ("Thrown Ranged", h.add(f["Thrown Ranged"], {"Romeo & Juliet": 3, "Goblin Diplomacy": 2,
                                                                    "Prince Ali Rescue": 2, "Quest Point Cape": 2},
                                               ("Longrange", "Doric", "Shrimp", "Bronze Darts", "Bronze Knives",
                                                "Bronze Throwing Axe"))),
        "Turtle Cape Melee": ("Lean Melee", {
            "Bronze Bar": 8, "Mithril Bar": 11, "Bronze Scimitar": 3, "Mithril Sword": 3, "Mithril Scimitar": 3,
            "Slash": 3, "Bronze Platebody": 2, "Kiteshield": 4, "Wooden Shield": 3, "Lobster": 3, "Swordfish": 2,
            "Protect from Magic": 2, "Redemption": 2, "Parry": 3, "Romeo & Juliet": 3, "Goblin Diplomacy": 1,
            "Cook's Assistant": 2, "Quest Point Cape": 2}),
        "Turtle Cape Mage": ("Lean Mage", {
            "Air Rune": 11, "Chaos Rune": 5, "Death Rune": 2, "Cloth": 2, "Air Strike": 4, "Air Bolt": 4, "Air Blast": 2,
            "Iban's Staff": 2, "Wizard Robe": 2, "Kiteshield": 4, "Wooden Shield": 3, "Lobster": 3,
            "Protect from Melee": 2, "Redemption": 2, "Mirror Shield": 3, "Romeo & Juliet": 3, "Prince Ali Rescue": 1,
            "Cook's Assistant": 2, "Quest Point Cape": 2, "Augury": 1}),
    }


def _job(args):
    name, deck, opp, seeds, rules = args
    wins, ends, capes, cape_round = 0, Counter(), 0, []
    for s in seeds:
        for seat in (0, 1):
            decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s, rules=rules)
            g.run()
            wins += g.winner == seat
            p = g.players[seat]
            if p.cape is not None:
                capes += 1
                cape_round.append(g.turn // 2 + 1 - p.cape)
            if g.winner == seat:
                ends[g.end_reason] += 1
    return name, opp, wins, 2 * len(seeds), ends, capes, cape_round


def run(decks, n, rules):
    jobs, seed = [], 1
    for name, deck in decks.items():
        for opp in FIELD:
            jobs.append((name, deck, opp, list(range(seed, seed + n)), rules))
            seed += n
    out = {}
    with Pool(2) as pool:
        for name, opp, w, t, ends, capes, cr in pool.map(_job, jobs):
            o = out.setdefault(name, {"w": 0, "t": 0, "ends": Counter(), "capes": 0, "vs": {}, "round": []})
            o["w"] += w; o["t"] += t; o["ends"] += ends; o["capes"] += capes; o["round"] += cr
            o["vs"][opp] = 100 * w / t
    return out


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 60
    cd = cape_decks()
    if "--turtle" in sys.argv:
        cd = {k: v for k, v in cd.items() if k.startswith("Turtle")}
    decks = {k: d for k, (_, d) in cd.items()}
    base = run({b: FIELD[b] for b, _ in cd.values()}, n, {})
    print("Base decks: " + ", ".join(f"{k} {100 * v['w'] / v['t']:.0f}%" for k, v in base.items()))
    grid = [(t, q, f) for f in (1, 2) for q in (5, 8) for t in (3, 4, 5)]
    if SMART:
        grid = [(5, 5, 1), (4, 5, 1), (3, 5, 1), (5, 8, 1)]
    if "--guard" in sys.argv:
        grid = [(5, 5, 1, 0), (5, 5, 1, 1), (5, 5, 1, 2), (4, 5, 1, 0), (3, 5, 1, 0)]
        if SMART:
            grid = [(5, 5, 1, 0), (5, 5, 1, 1)]
    print(f"{'turns':>5} {'QP':>3} {'free':>4}   " + "   ".join(f"{k:>40}" for k in decks))
    for row in grid:
        turns, qp, free = row[:3]
        guard = row[3] if len(row) > 3 else 0
        res = run(decks, n, {"cape_turns": turns, "cape_qp": qp, "free_quest_steps": free, "cape_guard": guard})
        cells = []
        for k in decks:
            r = res[k]
            win = 100 * r["w"] / r["t"]
            cape_win = 100 * r["ends"]["quest_cape"] / r["t"]
            played = 100 * r["capes"] / r["t"]
            rnd = sum(r["round"]) / len(r["round"]) if r["round"] else 0
            cells.append(f"{win:3.0f}% (cape on {played:3.0f}% by rd {rnd:4.1f}, wins by it {cape_win:3.0f}%)")
        print(f"{turns:5} {qp:3} {free:4} guard {guard}  " + "   ".join(f"{c:>40}" for c in cells))
