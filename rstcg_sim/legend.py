"""Round 14: one Legendary per deck, Telegraph on the card (reveal as an action, equip as an action
on a later turn), no Legendary materials. Each Legendary replaces one filler card in a Round 11 field
deck and plays the Round 11 field.

Two numbers per Legendary:
  natural  - the 1-of as it is (how much the card moves the whole deck)
  in hand  - the Legendary starts in the opening hand (how strong the card is when you have it)

    python legend.py 60              # greedy bot
    python legend.py 10 --smart      # playout bot
    python legend.py 60 --boost      # with the power-up proposals
"""
import dataclasses
import sys
from multiprocessing import Pool

import hybrid_cards as h
from bot import HeuristicBot
from cards import CARDS
from engine import Game
from planner import RolloutBot
from tier3 import swap

FIELD = h.round11_field()
SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot


def boost():
    """Power-up proposals for a card you only get one of."""
    R = dataclasses.replace
    CARDS["Dragon Dagger"] = R(CARDS["Dragon Dagger"], hit=10, durability=3)
    CARDS["Granite Maul"] = R(CARDS["Granite Maul"], hit=24, durability=2)
    CARDS["Dark Bow"] = R(CARDS["Dark Bow"], hit=13, durability=2)
    CARDS["Staff of the Dead"] = R(CARDS["Staff of the Dead"], hit=22, durability=2)


if "--boost" in sys.argv:
    boost()

def variant(base, label, **kw):
    """Register a tuned copy of a Legendary under its own name, e.g. 'Dragon Dagger 9x1'."""
    name = f"{base} {label}"
    CARDS[name] = dataclasses.replace(CARDS[base], name=name, **kw)
    return name


TUNE = {
    "Lean Melee + Dragon Dagger 9 x1 swing": ("Lean Melee", variant("Dragon Dagger", "9 x1 swing", durability=1), "Vanaka"),
    "Lean Melee + Dragon Dagger 7 x2 swings": ("Lean Melee", variant("Dragon Dagger", "7 x2 swings", hit=7), "Vanaka"),
    "Lean Ranged + Dark Bow 15": ("Lean Ranged", variant("Dark Bow", "15", hit=15), "Wooden Shield"),
    "Lean Ranged + Dark Bow 10 x2 swings": ("Lean Ranged", variant("Dark Bow", "10 x2 swings", hit=10, durability=2),
                                           "Wooden Shield"),
}

TESTS = TUNE if "--tune" in sys.argv else {
    "Lean Melee + Dragon Dagger": ("Lean Melee", "Dragon Dagger", "Vanaka"),
    "Lean Melee + Granite Maul": ("Lean Melee", "Granite Maul", "Vanaka"),
    "Thrown Ranged + Granite Maul": ("Thrown Ranged", "Granite Maul", "Wooden Shield"),
    "Lean Ranged + Dark Bow": ("Lean Ranged", "Dark Bow", "Wooden Shield"),
    "Lean Mage + Staff of the Dead": ("Lean Mage", "Staff of the Dead", "Banker"),
    "Smoke Mage + Staff of the Dead": ("Smoke Mage", "Staff of the Dead", "Wooden Shield"),
}


def _job(args):
    name, deck, legend, opp, seeds = args
    wins = equipped = eq_wins = 0
    for s in seeds:
        for seat in (0, 1):
            decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s)
            if legend:                       # put it in the opening hand, swap a random card back
                p = g.players[seat]
                if legend in p.deck:
                    p.deck.remove(legend)
                    k = g.rng.randrange(len(p.hand))
                    p.deck.insert(g.rng.randrange(len(p.deck) + 1), p.hand[k])
                    p.hand[k] = legend
            g.run()
            wins += g.winner == seat
            if g.stats[seat]["legend_equipped"]:
                equipped += 1
                eq_wins += g.winner == seat
    return name, opp, wins, equipped, eq_wins, 2 * len(seeds)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 60
    jobs, seed = [], 1
    for name, (base, legend, cut) in TESTS.items():
        deck = swap(FIELD[base], {cut: 1}, {legend: 1})
        modes = (("in hand", legend),) if "--hand-only" in sys.argv else (("natural", None), ("in hand", legend))
        for mode, forced in modes:
            for opp in FIELD:
                jobs.append(((name, mode), deck, forced, opp, list(range(seed, seed + n))))
                seed += n
    for base in sorted({b for b, _, _ in TESTS.values()}):
        for opp in FIELD:
            jobs.append(((base, "base"), FIELD[base], None, opp, list(range(seed, seed + n))))
            seed += n
    res = {}
    with Pool(2) as pool:
        for key, opp, w, e, ew, g in pool.imap_unordered(_job, jobs):
            r = res.setdefault(key, [0, 0, 0, 0])
            for j, v in enumerate((w, e, ew, g)):
                r[j] += v
    pct = lambda k: 100 * res[k][0] / res[k][3]
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order per matchup, Round 11 field"
          + (", boosted" if "--boost" in sys.argv else ""))
    print(f"  {'deck':32} {'base':>5} {'natural':>8} {'in hand':>8}   equipped (natural / in hand), win% when equipped")
    for name, (base, legend, cut) in TESTS.items():
        b = pct((base, "base"))
        hand = res[(name, "in hand")]
        nat = res.get((name, "natural"), [0, 0, 0, 1])
        res.setdefault((name, "natural"), nat)
        natural = "    n/a" if "--hand-only" in sys.argv else f"{pct((name, 'natural')) - b:+7.0f}"
        print(f"  {name:32} {b:4.0f}% {natural} {pct((name, 'in hand')) - b:+8.0f}"
              f"   {100 * nat[1] / nat[3]:3.0f}% / {100 * hand[1] / hand[3]:3.0f}%,"
              f" {100 * hand[2] / max(1, hand[1]):3.0f}% when equipped (in hand)")


if __name__ == "__main__":
    main()
