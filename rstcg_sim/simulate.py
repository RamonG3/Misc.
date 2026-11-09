"""Play every starter-deck matchup many times and summarize.

    python simulate.py            # 1000 games per seat order
    python simulate.py 300        # quicker
    python simulate.py 1000 --respawn-cost 5 --punch 3    # try other numbers
"""
import argparse
import json
import statistics
from collections import defaultdict

from bot import HeuristicBot
from cards import STARTER_DECKS
from engine import Game


def play(first, second, seed, rules, decks=None):
    decks = decks or STARTER_DECKS
    g = Game([decks[first], decks[second]],
             [HeuristicBot(seed * 2 + 1), HeuristicBot(seed * 2 + 2)],
             seed=seed, rules=rules, names=(first, second))
    g.run()
    return {
        "first": first, "second": second, "winner": g.winner, "reason": g.end_reason,
        "rounds": (g.turn + 2) // 2,
        "respawns": [g.stats[j]["respawns"] for j in (0, 1)],
        "punches": [g.stats[j]["punches"] for j in (0, 1)],
        "draw_actions": [g.stats[j]["draw_actions"] for j in (0, 1)],
        "dmg": [{k[4:]: v for k, v in g.stats[j].items() if k.startswith("dmg_")} for j in (0, 1)],
        "healed": [g.stats[j]["healed"] for j in (0, 1)],
        "soaked": [g.stats[j]["soaked"] for j in (0, 1)],
        "first_damage": g.first_damage_turn,
        "bolt_ready": g.bolt_ready_turn,
        "ran_dry": g.ran_dry,
        "final_lp": [p.lp for p in g.players],
    }


def run_all(n, rules, seed0=1, decks=None):
    decks = decks or STARTER_DECKS
    names = list(decks)
    games = []
    seed = seed0
    for a in names:
        for b in names:
            for _ in range(n):
                games.append(play(a, b, seed, rules, decks))
                seed += 1
    return games


def pct(x):
    return f"{100 * x:.0f}%"


def summarize(games, names=None):
    names = names or list(dict.fromkeys(gm["first"] for gm in games))
    out = {}

    # Win rate of row deck vs column deck, both seat orders pooled
    wins = defaultdict(lambda: [0, 0])
    for gm in games:
        for seat, deck, opp in ((0, gm["first"], gm["second"]), (1, gm["second"], gm["first"])):
            wins[(deck, opp)][1] += 1
            if gm["winner"] == seat:
                wins[(deck, opp)][0] += 1
    out["matrix"] = {a: {b: wins[(a, b)][0] / wins[(a, b)][1] for b in names} for a in names}

    # Going first
    decided = [gm for gm in games if gm["winner"] is not None]
    out["first_player_win"] = sum(gm["winner"] == 0 for gm in decided) / len(decided)
    out["first_by_deck"] = {}
    for d in names:
        gs = [gm for gm in decided if gm["first"] == d and gm["second"] != d]
        out["first_by_deck"][d] = sum(gm["winner"] == 0 for gm in gs) / len(gs)
    mirrors = [gm for gm in decided if gm["first"] == gm["second"]]
    out["first_in_mirror"] = sum(gm["winner"] == 0 for gm in mirrors) / len(mirrors)

    # Length and endings per matchup (unordered)
    out["matchups"] = []
    for x in range(len(names)):
        for y in range(x, len(names)):
            a, b = names[x], names[y]
            gs = [gm for gm in games if {gm["first"], gm["second"]} == {a, b}
                  and (a != b or gm["first"] == a)]
            r = sorted(gm["rounds"] for gm in gs)
            out["matchups"].append({
                "a": a, "b": b, "games": len(gs),
                "median_rounds": statistics.median(r),
                "p10": r[len(r) // 10], "p90": r[9 * len(r) // 10],
                "respawn_games": sum(any(gm["respawns"]) for gm in gs) / len(gs),
                "deck_out": sum(gm["reason"] in ("deck_out", "respawn_cost") for gm in gs) / len(gs),
                "timeouts": sum(gm["reason"] == "timeout" for gm in gs) / len(gs),
            })

    # Per-deck behaviour
    out["decks"] = {}
    for d in names:
        seats = [(gm, s) for gm in games for s in (0, 1)
                 if (gm["first"], gm["second"])[s] == d]
        total = defaultdict(int)
        for gm, s in seats:
            for k, v in gm["dmg"][s].items():
                total[k] += v
        all_dmg = sum(total.values()) or 1
        fd = [gm["first_damage"][s] for gm, s in seats if gm["first_damage"][s]]
        out["decks"][d] = {
            "damage_share": {k: v / all_dmg for k, v in sorted(total.items(), key=lambda kv: -kv[1])},
            "punches_per_game": statistics.mean(gm["punches"][s] for gm, s in seats),
            "healed_per_game": statistics.mean(gm["healed"][s] for gm, s in seats),
            "soaked_per_game": statistics.mean(gm["soaked"][s] for gm, s in seats),
            "median_first_damage_turn": statistics.median(fd) if fd else None,
            "ran_dry": statistics.mean(gm["ran_dry"][s] for gm, s in seats),
            "respawned": statistics.mean(gm["respawns"][s] > 0 for gm, s in seats),
        }

    # Air Mage: how fast it reaches Bolt (1 Chaos + 2 Air, staff counting)
    mage = [(gm, s) for gm in games for s in (0, 1) if (gm["first"], gm["second"])[s] == "Air Mage"]
    br = [gm["bolt_ready"][s] for gm, s in mage]
    if br:
        out["bolt_ready"] = {f"by own turn {t}": sum(1 for x in br if x and x <= t) / len(br)
                             for t in (1, 2, 3, 4, 6)}
    return out


def print_summary(s):
    names = list(s["matrix"])
    print("\nWin rate (row deck vs column deck, both seat orders):")
    print(" " * 10 + "".join(f"{n:>10}" for n in names))
    for a in names:
        print(f"{a:>10}" + "".join(f"{pct(s['matrix'][a][b]):>10}" for b in names))
    print(f"\nFirst player wins: {pct(s['first_player_win'])}  (mirrors: {pct(s['first_in_mirror'])})")
    for d, v in s["first_by_deck"].items():
        print(f"  {d} going first (non-mirror): {pct(v)}")
    print("\nMatchups:")
    for m in s["matchups"]:
        print(f"  {m['a']} v {m['b']}: median {m['median_rounds']} rounds "
              f"(10-90%: {m['p10']}-{m['p90']}), respawn in {pct(m['respawn_games'])}, "
              f"deck-out loss {pct(m['deck_out'])}, timeouts {pct(m['timeouts'])}")
    print("\nDecks:")
    for d, v in s["decks"].items():
        share = ", ".join(f"{k} {pct(x)}" for k, x in v["damage_share"].items())
        print(f"  {d}: damage from {share}")
        print(f"     punches/game {v['punches_per_game']:.1f}, healed {v['healed_per_game']:.1f}, "
              f"soaked {v['soaked_per_game']:.1f}, first damage turn {v['median_first_damage_turn']}, "
              f"ran dry {pct(v['ran_dry'])}, respawned {pct(v['respawned'])}")
    if "bolt_ready" in s:
        print("\nAir Mage reaches Bolt:", ", ".join(f"{k}: {pct(v)}" for k, v in s["bolt_ready"].items()))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("n", nargs="?", type=int, default=1000, help="games per seat order per matchup")
    ap.add_argument("--respawn-cost", type=int)
    ap.add_argument("--punch", type=int)
    ap.add_argument("--json", help="write the summary to this file")
    args = ap.parse_args()
    rules = {}
    if args.respawn_cost is not None:
        rules["respawn_cost"] = args.respawn_cost
    if args.punch is not None:
        rules["punch_damage"] = args.punch
    summary = summarize(run_all(args.n, rules))
    print_summary(summary)
    if args.json:
        with open(args.json, "w") as f:
            json.dump(summary, f, indent=2)
