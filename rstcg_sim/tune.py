"""Search for weapon, bow, staff and spell numbers that balance the swing rule.

    python tune.py search 40 80      # 40 random settings, 80 games per seat order each
    python tune.py confirm 400       # rerun the best settings from tune_results.json

Each setting is scored on how far decks land from 50%: lean and dual-style decks
(each one's win rate against the other five), every lean-vs-lean and
lean-vs-hybrid matchup, and the three starter matchups. Lower is better.
"""
import json
import random
import sys
from dataclasses import replace
from multiprocessing import Pool

import cards
from simulate import play

BASE = dict(cards.CARDS)
FIELD = {**cards.LEAN_DECKS, **cards.HYBRID_DECKS}
RESULTS = "tune_results.json"

# name: (low, high, step)
SPACE = {
    "weapon_mult": (1.2, 2.4, 0.05),   # melee weapon hit x this
    "bow_mult": (1.0, 2.0, 0.05),      # bow base hit x this
    "staff_hit": (2, 7, 1),            # damage per staff swing
    "spell_mult": (0.7, 1.2, 0.05),    # spell damage x this
    "ability_bonus": (0, 4, 1),        # added to Slash (+2) and Stab (+0)
}


def apply(params):
    cards.CARDS.clear()
    cards.CARDS.update(BASE)
    for n, c in BASE.items():
        if c.kind == "weapon":
            cards.CARDS[n] = replace(c, hit=max(1, round(c.hit * params["weapon_mult"])))
        elif c.kind == "bow":
            cards.CARDS[n] = replace(c, hit=max(1, round(c.hit * params["bow_mult"])))
        elif c.kind == "staff":
            cards.CARDS[n] = replace(c, hit=params["staff_hit"])
        elif c.kind == "spell":
            cards.CARDS[n] = replace(c, damage=max(1, round(c.damage * params["spell_mult"])))
        elif c.kind == "ability":
            cards.CARDS[n] = replace(c, damage=c.damage + params["ability_bonus"])


def _job(args):
    params, deckset, first, second, seeds = args
    apply(params)
    decks = cards.STARTER_DECKS if deckset == "starter" else FIELD
    return [(first, second, play(first, second, s, {"swing_action": True}, decks)["winner"]) for s in seeds]


def evaluate(params, n, pool, seed0=1):
    jobs = []
    seed = seed0
    for deckset, decks in (("field", FIELD), ("starter", cards.STARTER_DECKS)):
        for a in decks:
            for b in decks:
                jobs.append((params, deckset, a, b, list(range(seed, seed + n))))
                seed += n
    wins = {}
    for deckset_rows, (params_, deckset, a, b, _) in zip(pool.map(_job, jobs), jobs):
        for first, second, w in deckset_rows:
            for seat, deck, opp in ((0, first, second), (1, second, first)):
                key = (deckset, deck, opp)
                got, total = wins.get(key, (0, 0))
                wins[key] = (got + (w == seat), total + 1)
    rate = {k: 100 * g / t for k, (g, t) in wins.items()}

    names = list(FIELD)
    field_avg = {a: sum(rate[("field", a, b)] for b in names if b != a) / (len(names) - 1) for a in names}
    matchups = [rate[("field", a, b)] for a in names for b in names if a < b]
    st = list(cards.STARTER_DECKS)
    starters = {f"{a} v {b}": rate[("starter", a, b)] for a in st for b in st if a < b}

    def msq(xs):
        return sum((x - 50) ** 2 for x in xs) / len(xs)

    loss = msq(field_avg.values()) + msq(starters.values()) + 0.25 * msq(matchups)
    return {"params": params, "loss": round(loss, 1), "field_avg": field_avg,
            "starters": starters, "worst_matchup": max(abs(x - 50) for x in matchups) + 50}


def random_params(rng):
    out = {}
    for k, (lo, hi, step) in SPACE.items():
        steps = int(round((hi - lo) / step))
        v = lo + step * rng.randint(0, steps)
        out[k] = int(v) if isinstance(step, int) else round(v, 2)
    return out


def neighbour(params, rng):
    out = dict(params)
    k = rng.choice(list(SPACE))
    lo, hi, step = SPACE[k]
    v = out[k] + step * rng.choice((-2, -1, 1, 2))
    v = min(hi, max(lo, v))
    out[k] = int(v) if isinstance(step, int) else round(v, 2)
    return out


def show(r):
    p = r["params"]
    print(f"loss {r['loss']:7.1f} | weapons x{p['weapon_mult']} bows x{p['bow_mult']} staff {p['staff_hit']} "
          f"spells x{p['spell_mult']} abilities +{p['ability_bonus']} | "
          + " ".join(f"{k.split()[-1][:6]} {v:.0f}" for k, v in r["field_avg"].items())
          + " | starters " + " ".join(f"{v:.0f}" for v in r["starters"].values())
          + f" | worst matchup {r['worst_matchup']:.0f}", flush=True)


if __name__ == "__main__":
    mode = sys.argv[1]
    rng = random.Random(7)
    with Pool(2) as pool:
        if mode == "search":
            k, n = int(sys.argv[2]), int(sys.argv[3])
            results = []
            for _ in range(k):                       # broad random search
                r = evaluate(random_params(rng), n, pool)
                results.append(r)
                show(r)
            results.sort(key=lambda r: r["loss"])
            print("--- refining the best five ---", flush=True)
            for best in results[:5]:                 # local search around the leaders
                cur = best
                for _ in range(6):
                    r = evaluate(neighbour(cur["params"], rng), n, pool)
                    results.append(r)
                    show(r)
                    if r["loss"] < cur["loss"]:
                        cur = r
            results.sort(key=lambda r: r["loss"])
            json.dump(results, open(RESULTS, "w"), indent=1)
        elif mode == "confirm":
            n = int(sys.argv[2])
            results = json.load(open(RESULTS))
            seen, confirmed = set(), []
            for r in results:
                key = json.dumps(r["params"], sort_keys=True)
                if key in seen:
                    continue
                seen.add(key)
                c = evaluate(r["params"], n, pool, seed0=10_000_000)
                confirmed.append(c)
                show(c)
                if len(confirmed) == 5:
                    break
            confirmed.sort(key=lambda r: r["loss"])
            json.dump(confirmed, open("tune_confirmed.json", "w"), indent=1)
    apply({"weapon_mult": 1, "bow_mult": 1, "staff_hit": BASE["Staff of Air"].hit,
           "spell_mult": 1, "ability_bonus": 0})
