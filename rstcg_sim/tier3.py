"""Round 13: tier 3 (Rune bars, Yew logs, Death and Blood runes) and Legendary cards,
tested as packages in Round 11 field decks against the Round 11 field.

    python tier3.py 80                  # greedy bot, 80 games per seat order
    python tier3.py 80 Legendary        # only tests whose name contains "Legendary"
    python tier3.py 10 --smart          # playout bot
    python tier3.py 80 built --cheap    # with the Round 13 cost proposals
"""
import sys
from multiprocessing import Pool

import hybrid_cards as h
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot

FIELD = h.round11_field()


def apply_proposals():
    """Round 13 proposals: Rune gear at 1 Mithril + 2 Rune; Legendaries need 1 Legendary material
    and one or two fewer materials in play."""
    import dataclasses
    from cards import CARDS
    R = dataclasses.replace
    for n in ("Rune Dagger", "Rune Scimitar", "Rune Mace", "Rune Darts", "Rune Knives", "Rune Throwing Axe",
              "Rune Platebody"):
        CARDS[n] = R(CARDS[n], req=(("Mithril Bar", 1), ("Rune Bar", 2)))
    CARDS["Dragon Dagger"] = R(CARDS["Dragon Dagger"], fam_req=(("bar", 2),))
    CARDS["Granite Maul"] = R(CARDS["Granite Maul"], expend=(("Dragon Metal Shard", 1),), fam_req=(("bar", 3),))
    CARDS["Dark Bow"] = R(CARDS["Dark Bow"], expend=(("Zulrah's Scales", 1),), fam_req=(("log", 3),))
    CARDS["Staff of the Dead"] = R(CARDS["Staff of the Dead"], expend=(("Ancient Shard", 1),), fam_req=(("rune", 3),))


if "--cheap" in sys.argv:
    apply_proposals()
SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot


def swap(deck, out, inn):
    """deck with `out` counts removed and `inn` counts added (must stay 60)."""
    d = dict(deck)
    for k, v in out.items():
        assert d.get(k, 0) >= v, (k, d.get(k, 0), v)
        d[k] -= v
    for k, v in inn.items():
        d[k] = d.get(k, 0) + v
    d = {k: v for k, v in d.items() if v}
    assert sum(d.values()) == 60, sum(d.values())
    return d


def tests():
    f = FIELD
    t = {}
    lm, lr, tr, sm, mg = f["Lean Melee"], f["Lean Ranged"], f["Thrown Ranged"], f["Smoke Mage"], f["Lean Mage"]
    # --- tier 3 ---
    t["Lean Melee + Rune Scimitar"] = ("Lean Melee", swap(lm, {"Bronze Bar": 3, "Mithril Bar": 3, "Bronze Scimitar": 2,
                                                             "Bronze Dagger": 1},
                                                         {"Rune Bar": 6, "Rune Scimitar": 3}))
    t["Lean Melee + Rune Mace, Crush"] = ("Lean Melee", swap(lm, {"Bronze Bar": 3, "Mithril Bar": 3, "Bronze Scimitar": 2,
                                                                "Stab": 2},
                                                            {"Rune Bar": 6, "Rune Mace": 2, "Crush": 2}))
    t["Thrown Ranged + Rune thrown"] = ("Thrown Ranged", swap(tr, {"Bronze Bar": 3, "Mithril Bar": 3, "Bronze Darts": 2,
                                                                   "Bronze Throwing Axe": 2},
                                                               {"Rune Bar": 6, "Rune Knives": 2, "Rune Throwing Axe": 2}))
    t["Lean Ranged + Yew, Rune Arrows"] = ("Lean Ranged", swap(lr, {"Normal Logs": 3, "Willow Logs": 3, "Bronze Arrows": 3,
                                                                    "Seercull": 2},
                                                                {"Yew Logs": 6, "Rune Arrows": 3, "Yew Shortbow": 2}))
    t["Smoke Mage + Fire Blast"] = ("Smoke Mage", swap(sm, {"Air Strike": 3, "Wizards' Tower": 1, "Archmage Sedridor": 1,
                                                            "Fire Rune": 1},
                                                        {"Death Rune": 3, "Fire Blast": 3}))
    t["Lean Mage + Air Wave"] = ("Lean Mage", swap(mg, {"Air Blast": 3, "Death Rune": 3}, {"Blood Rune": 3, "Air Wave": 3}))
    # --- Legendary ---
    t["Lean Melee + Legendary"] = ("Lean Melee", swap(lm, {"Bronze Dagger": 2, "Stab": 2, "Wooden Shield": 2, "Vanaka": 1,
                                                         "Doric": 1},
                                                     {"Dragon Metal Shard": 4, "Dragon Dagger": 2, "Granite Maul": 2}))
    t["Lean Ranged + Legendary"] = ("Lean Ranged", swap(lr, {"Longrange": 3, "Lowe": 2, "Wooden Shield": 1},
                                                       {"Zulrah's Scales": 4, "Dark Bow": 2}))
    t["Lean Mage + Legendary"] = ("Lean Mage", swap(mg, {"Banker": 1, "Wizards' Tower": 2, "Wooden Shield": 3},
                                                   {"Ancient Shard": 4, "Staff of the Dead": 2}))
    t["Rune Melee (built for tier 3)"] = ("Lean Melee", {
        "Bronze Bar": 7, "Mithril Bar": 12, "Rune Bar": 7, "Mithril Sword": 4, "Mithril Scimitar": 4,
        "Rune Scimitar": 3, "Rune Dagger": 1, "Slash": 4, "Stab": 2, "Rune Platebody": 2, "Kiteshield": 3,
        "Wooden Shield": 2, "Lobster": 1, "Parry": 3, "Burst of Strength": 3, "Doric": 1, "Vanaka": 1})
    t["Rune Thrown (built for tier 3)"] = ("Thrown Ranged", {
        "Bronze Bar": 5, "Mithril Bar": 12, "Rune Bar": 6, "Leather": 2, "Mithril Darts": 4, "Mithril Knives": 4,
        "Mithril Throwing Axe": 2, "Rune Knives": 3, "Rune Throwing Axe": 2, "Rapid": 4, "Longrange": 1,
        "Leather Body": 2, "Kiteshield": 3, "Wooden Shield": 2, "Doric": 2, "Ricochet": 3, "Rigour": 3})
    t["Yew Ranged (built for tier 3)"] = ("Lean Ranged", {
        "Normal Logs": 5, "Willow Logs": 11, "Yew Logs": 6, "Leather": 2, "Magic Shortbow": 3, "Yew Shortbow": 3,
        "Mithril Arrows": 4, "Rune Arrows": 3, "Rapid": 4, "Longrange": 2, "Leather Body": 2, "Kiteshield": 4,
        "Wooden Shield": 3, "Lowe": 2, "Ricochet": 3, "Sharp Eye": 3})
    return t


def _job(args):
    name, deck, opp, seeds = args
    wins = 0
    for s in seeds:
        for seat in (0, 1):
            decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s)
            g.run()
            wins += g.winner == seat
    return name, opp, 100 * wins / (2 * len(seeds))


def run(decks, n):
    jobs, seed = [], 1
    for name, deck in decks.items():
        for opp in FIELD:
            jobs.append((name, deck, opp, list(range(seed, seed + n))))
            seed += n
    res = {}
    with Pool(2) as pool:
        for name, opp, r in pool.imap_unordered(_job, jobs):
            res[(name, opp)] = r
    return res


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 80
    only = args[1] if len(args) > 1 else ""
    t = {k: v for k, v in tests().items() if only in k}
    decks = {k: d for k, (_, d) in t.items()}
    bases = sorted({b for b, _ in t.values()})
    decks.update({"BASE " + b: FIELD[b] for b in bases})
    res = run(decks, n)
    avg = {k: sum(res[(k, o)] for o in FIELD) / len(FIELD) for k in decks}
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order, against the Round 11 field")
    for b in bases:
        for k in ["BASE " + b] + [k for k, (bb, _) in t.items() if bb == b]:
            print(f"  {k:34} {avg[k]:4.0f}% {avg[k] - avg['BASE ' + b]:+5.0f}")
        print()
