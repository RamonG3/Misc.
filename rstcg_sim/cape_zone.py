"""Round 15: the cape zone. Each player brings one cape (chosen blind); either player can claim
either cape; claim is an action; one cape each; 5 counters and you win.

    python cape_zone.py fit 10        # each field deck brings each cape; opponent brings none
    python cape_zone.py best 20       # each deck brings its best cape, opponent brings its best too
"""
import sys
from collections import defaultdict
from multiprocessing import Pool

import hybrid_cards as h
from bot import HeuristicBot
from capes import CAPES
from engine import Game
from planner import RolloutBot

FIELD = h.round11_field()
SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot
TUNE = {}
for _a in sys.argv:
    if _a.startswith("--tune="):
        import json
        TUNE = json.loads(_a[len("--tune="):])
TUNE_V2 = {"Quest Point Cape": 6, "Attack Cape": 6, "Ranged Cape": 4, "Magic Cape": 4, "Defence Cape": 12,
           "Prayer Cape": 2, "Cooking Cape": 3, "Smithing Cape": 5, "Woodcutting Cape": 5, "Runecrafting Cape": 6}
if "--v2" in sys.argv:
    TUNE = TUNE_V2
TUNE_V3 = dict(TUNE_V2, **{"Woodcutting Cape": 6})
if "--v3" in sys.argv:
    TUNE = TUNE_V3
TUNE_V4 = dict(TUNE_V3, **{"Quest Point Cape": 5})
if "--v4" in sys.argv:          # with R&J and Goblin Diplomacy at 3 QP: no single quest is a whole cape
    import dataclasses
    from cards import CARDS
    for _n in ("Romeo & Juliet", "Goblin Diplomacy"):
        CARDS[_n] = dataclasses.replace(CARDS[_n], qp=3)
    TUNE = TUNE_V4
if "--v5" in sys.argv:          # R&J and Goblin Diplomacy at 4 QP: a big quest plus a small one makes a cape
    import dataclasses
    from cards import CARDS
    for _n in ("Romeo & Juliet", "Goblin Diplomacy"):
        CARDS[_n] = dataclasses.replace(CARDS[_n], qp=4)
    TUNE = TUNE_V4
TUNE_FAST = {"Quest Point Cape": 6, "Attack Cape": 4, "Ranged Cape": 3, "Magic Cape": 3, "Defence Cape": 8,
             "Prayer Cape": 2, "Cooking Cape": 2, "Smithing Cape": 4, "Woodcutting Cape": 4, "Runecrafting Cape": 4}
if "--fast" in sys.argv:        # claim earlier, keep the 5-turn countdown
    TUNE = TUNE_FAST
TUNE_V1 = {"Quest Point Cape": 8, "Attack Cape": 6, "Ranged Cape": 4, "Magic Cape": 4, "Defence Cape": 2,
           "Prayer Cape": 2, "Cooking Cape": 3, "Smithing Cape": 7, "Woodcutting Cape": 7, "Runecrafting Cape": 8}
if "--v1" in sys.argv:
    TUNE = TUNE_V1


def _job(args):
    deck, cape, opp, ocape, seeds = args
    r = defaultdict(int)
    for s in seeds:
        for seat in (0, 1):
            decks = [FIELD[deck], FIELD[opp]] if seat == 0 else [FIELD[opp], FIELD[deck]]
            capes = [cape, ocape] if seat == 0 else [ocape, cape]
            capes = capes if any(capes) else None
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s,
                     rules={"capes": capes, "cape_need": TUNE})
            g.run()
            me = seat
            r["games"] += 1
            r["turns"] += g.turn
            r["wins"] += g.winner == me
            if g.end_reason == "cape":
                r["cape_end"] += 1
                r["cape_win_me"] += g.winner == me
            for c, who in g.cape_claimed.items():
                r[("claim", c, "me" if who == me else "opp")] += 1
    return deck, cape, opp, ocape, dict(r)


def run(jobs):
    out = []
    with Pool(2) as pool:
        for res in pool.imap_unordered(_job, jobs):
            out.append(res)
    return out


def fit(n):
    jobs, seed = [], 1
    for deck in FIELD:
        for cape in [None] + list(CAPES):
            for opp in FIELD:
                jobs.append((deck, cape, opp, None, list(range(seed, seed + n))))
                seed += n
    tot = defaultdict(lambda: defaultdict(int))
    for deck, cape, opp, _, r in run(jobs):
        for k, v in r.items():
            tot[(deck, cape)][k] += v
    names = [c.replace(" Cape", "").replace("Quest Point", "QP") for c in CAPES]
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order. Win% change when the deck brings"
          " this cape (opponent brings none). Bracket: % of games the cape decided.")
    print(f"  {'deck':16} {'base':>5} " + " ".join(f"{x[:10]:>11}" for x in names))
    for deck in FIELD:
        b = tot[(deck, None)]
        base = 100 * b["wins"] / b["games"]
        row = []
        for cape in CAPES:
            t = tot[(deck, cape)]
            row.append(f"{100 * t['wins'] / t['games'] - base:+4.0f} ({100 * t['cape_end'] / t['games']:2.0f})")
        print(f"  {deck[:16]:16} {base:4.0f}% " + " ".join(f"{x:>11}" for x in row))
    print("\n  Average over decks:")
    for cape in CAPES:
        d = [100 * tot[(dk, cape)]["wins"] / tot[(dk, cape)]["games"]
             - 100 * tot[(dk, None)]["wins"] / tot[(dk, None)]["games"] for dk in FIELD]
        ce = sum(tot[(dk, cape)]["cape_end"] for dk in FIELD) / sum(tot[(dk, cape)]["games"] for dk in FIELD)
        st = sum(tot[(dk, cape)][("claim", cape, "opp")] for dk in FIELD) / sum(tot[(dk, cape)]["games"] for dk in FIELD)
        best = max(FIELD, key=lambda dk: tot[(dk, cape)]["wins"] / tot[(dk, cape)]["games"]
                   - tot[(dk, None)]["wins"] / tot[(dk, None)]["games"])
        print(f"  {cape:19} {sum(d) / len(d):+5.1f}   decided {100 * ce:3.0f}% of games, "
              f"stolen by the opponent {100 * st:3.0f}%, best fit: {best}")


def built():
    """Each cape with a deck built for it: (cape, base deck name, deck)."""
    from tier3 import swap
    import cape as old
    lm, lr, mg = FIELD["Lean Melee"], FIELD["Lean Ranged"], FIELD["Lean Mage"]
    return {
        "Quest Point Cape": ("Lean Melee", swap(old.cape_decks()["Cape Melee"][1], {"Quest Point Cape": 2, "Vampyre Slayer": 2},
                                                    {"Lobster": 2, "Doric's Quest": 2})),
        "Attack Cape": ("Lean Melee", lm),
        "Ranged Cape": ("Lean Ranged", lr),
        "Magic Cape": ("Lean Mage", mg),
        "Defence Cape": ("Lean Melee", swap(lm, {"Stab": 4, "Bronze Dagger": 1},
                                            {"Parry": 1, "Protect from Magic": 2, "Protect from Missiles": 2})),
        "Prayer Cape": ("Lean Melee", swap(lm, {"Burst of Strength": 3, "Stab": 2}, {"Piety": 3, "Smite": 2})),
        "Cooking Cape": ("Lean Melee", swap(lm, {"Stab": 4, "Bronze Dagger": 2, "Wooden Shield": 1},
                                            {"Lobster": 3, "Swordfish": 4})),
        "Smithing Cape": ("Lean Melee", lm),
        "Woodcutting Cape": ("Lean Ranged", lr),
        "Runecrafting Cape": ("Lean Mage", mg),
    }


def _bjob(args):
    cape, deck, opp, turns, tune, seeds = args
    r = defaultdict(int)
    for s in seeds:
        for seat in (0, 1):
            decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
            capes = ([cape, None] if seat == 0 else [None, cape]) if cape else None
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s,
                     rules={"capes": capes, "cape_need": tune, "cape_turns": turns})
            g.run()
            r["games"] += 1
            r["wins"] += g.winner == seat
            r["len"] += g.own_turn(seat)
            if g.end_reason == "cape":
                r["cape_end"] += 1
            if g.stats[seat]["cape_claimed"]:
                r["claimed"] += 1
                r["cturn"] += g.stats[seat]["cape_claim_turn"]
            if g.stats[1 - seat]["cape_claimed"]:
                r["stolen"] += 1
    return cape, deck, opp, turns, r


def built_test(n, turns_list, tune):
    b = built()
    jobs, seed = [], 1
    for turns in turns_list:
        for cape, (_, deck) in b.items():
            for opp in FIELD:
                jobs.append((cape, deck, opp, turns, tune, list(range(seed, seed + n))))
                seed += n
    for cape, (_, deck) in b.items():
        for opp in FIELD:
            jobs.append((None, deck, opp, 5, tune, list(range(seed, seed + n))))
            seed += n
    tot = defaultdict(lambda: defaultdict(int))
    with Pool(2) as pool:
        for cape, deck, opp, turns, r in pool.imap_unordered(_bjob, jobs):
            key = (cape, turns) if cape else ("none", tuple(sorted(deck.items())))
            for k, v in r.items():
                tot[key][k] += v
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order per matchup; the opponent brings no cape."
          + (f" Needs: {tune}" if tune else ""))
    for turns in turns_list:
        print(f"\n  Countdown {turns}:  {'cape':19} {'vs no cape':>10} {'decided':>8} {'claimed':>8} {'at turn':>8} {'stolen':>7}")
        for cape, (_, deck) in b.items():
            t, base = tot[(cape, turns)], tot[("none", tuple(sorted(deck.items())))]
            d = 100 * t["wins"] / t["games"] - 100 * base["wins"] / base["games"]
            print(f"  {'':13} {cape:19} {d:+9.0f}% {100 * t['cape_end'] / t['games']:7.0f}% "
                  f"{100 * t['claimed'] / t['games']:7.0f}% {t['cturn'] / max(1, t['claimed']):8.1f} "
                  f"{100 * t['stolen'] / t['games']:6.0f}%")



def _hjob(args):
    a, b, turns, tune, seeds = args
    bl = built()
    r = defaultdict(int)
    for s in seeds:
        for seat in (0, 1):
            decks = [bl[a][1], bl[b][1]] if seat == 0 else [bl[b][1], bl[a][1]]
            capes = ([a, b] if seat == 0 else [b, a]) if turns else None
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s,
                     rules={"capes": capes, "cape_need": tune, "cape_turns": turns or 5})
            g.run()
            r["games"] += 1
            r["wins"] += g.winner == seat
            r["cape_end"] += g.end_reason == "cape"
            if g.end_reason == "cape" and g.winner == seat:
                r["own" if g.players[seat].cape_name == a else "stole"] += 1
    return a, b, r


def h2h(n, turns, tune):
    names = list(built())
    jobs, seed = [], 1
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            jobs.append((a, b, turns, tune, list(range(seed, seed + n))))
            seed += n
    tot = defaultdict(lambda: defaultdict(int))
    with Pool(2) as pool:
        for a, b, r in pool.imap_unordered(_hjob, jobs):
            tot[a]["games"] += r["games"]; tot[b]["games"] += r["games"]
            tot[a]["wins"] += r["wins"]; tot[b]["wins"] += r["games"] - r["wins"]
            tot["all"]["games"] += r["games"]; tot["all"]["cape_end"] += r["cape_end"]
            tot[a]["own"] += r["own"]; tot[a]["stole"] += r["stole"]
    print(("Smart" if SMART else "Greedy") + f" bot, both players bring a cape, countdown {turns}, "
          f"{n} games per seat order per pairing")
    print(f"  Capes decided {100 * tot['all']['cape_end'] / tot['all']['games']:.0f}% of games")
    for c in sorted(names, key=lambda c: -tot[c]["wins"] / tot[c]["games"]):
        print(f"  {c:19} ({built()[c][0]:11}) {100 * tot[c]['wins'] / tot[c]['games']:4.0f}%")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mode = args[0] if args else "fit"
    n = int(args[1]) if len(args) > 1 else 10
    if mode == "fit":
        fit(n)
    if mode == "h2h":
        h2h(n, int(args[2]) if len(args) > 2 else 3, TUNE)
    if mode == "built":
        turns = [int(t) for t in (args[2] if len(args) > 2 else "5,3").split(",")]
        built_test(n, turns, TUNE)
