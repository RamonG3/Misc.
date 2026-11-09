"""Round 18: group 2, the 34 cards the simulator had never seen (Water and elemental-pair runes, the Water
and Earth top spells, the five new flurries, the Fire/Water/Earth staves, Varrock Teleport and its tablet,
Smith's Hammer, Anti-dragon Shield, Shark, Horvik, Brother Jered, Thessalia, Wise Old Man, and the
Lumbridge, Varrock, Mage Arena, Duel Arena and Grand Exchange locations). Each package goes into the
deck it fits and plays the Round 17 best-of-each-style field.

    python group2.py 40              # greedy bot, 40 games per seat order per matchup
    python group2.py 4 --smart       # playout bot
    python group2.py 40 "Flurry|Water"
"""
import sys
from multiprocessing import Pool

import bestof
import cards
import group1
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot
from tier3 import swap

SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot
FIELD = {k: d for k, (_, d) in bestof.D.items()}


def flurry_mage(a, b, combo, a_strike, b_strike, a_bolt, b_bolt, flurry):
    """Smoke Mage rebuilt on another pair of elements."""
    d = {a: 6, b: 6, combo: 4, "Chaos Rune": 4, "Cloth": 2, a_strike: 4, b_strike: 4, flurry: 4,
         a_bolt: 4, b_bolt: 4, "Rune Pouch": 2, "Wizard Robe": 2, "Kiteshield": 3, "Wooden Shield": 1,
         "Archmage Sedridor": 2, "Wizards' Tower": 2, "Mirror Shield": 3, "Iban's Staff": 3}
    return d


def packages():
    F = FIELD
    lm, mm, lr, mg, sm, em = (F["Lean Melee"], F["Mace Melee"], F["Lean Ranged"], F["Lean Mage"],
                              F["Smoke Mage"], F["Earth Mage"])
    g1 = {k: d for k, (_, d) in group1.packages().items()}
    water = {"Water Rune": 12, "Chaos Rune": 5, "Death Rune": 3, "Cloth": 2, "Water Strike": 4, "Water Bolt": 4,
             "Water Blast": 3, "Rune Pouch": 4, "Wizard Robe": 2, "Kiteshield": 3, "Wooden Shield": 3,
             "Archmage Sedridor": 2, "Wizards' Tower": 2, "Banker": 1, "Mirror Shield": 3, "Staff of Water": 3,
             "Iban's Staff": 2, "Augury": 2}
    fire = g1["Fire Mage without Blast and Wave"]
    p = {
        # Magic: new elements
        "Water Mage (Lean Mage on Water)": ("Lean Mage", water),
        "Water Mage + Water Wave": ("Lean Mage", swap(water, {"Death Rune": 3, "Water Blast": 3},
                                                      {"Blood Rune": 3, "Water Wave": 3})),
        "Mist Mage (Air/Water flurry)": ("Smoke Mage", flurry_mage("Air Rune", "Water Rune", "Mist Rune", "Air Strike",
                                                                   "Water Strike", "Air Bolt", "Water Bolt", "Mist Flurry")),
        "Steam Mage (Fire/Water flurry)": ("Smoke Mage", flurry_mage("Fire Rune", "Water Rune", "Steam Rune", "Fire Strike",
                                                                     "Water Strike", "Fire Bolt", "Water Bolt", "Steam Flurry")),
        "Mud Mage (Water/Earth flurry)": ("Smoke Mage", flurry_mage("Water Rune", "Earth Rune", "Mud Rune", "Water Strike",
                                                                    "Earth Strike", "Water Bolt", "Earth Bolt", "Mud Flurry")),
        "Lava Mage (Earth/Fire flurry)": ("Smoke Mage", flurry_mage("Earth Rune", "Fire Rune", "Lava Rune", "Earth Strike",
                                                                    "Fire Strike", "Earth Bolt", "Fire Bolt", "Lava Flurry")),
        "Dust Mage (Air/Earth flurry)": ("Smoke Mage", flurry_mage("Air Rune", "Earth Rune", "Dust Rune", "Air Strike",
                                                                   "Earth Strike", "Air Bolt", "Earth Bolt", "Dust Flurry")),
        "Earth Mage + Earth Blast": ("Earth Mage", swap(em, {"Air Strike": 4}, {"Death Rune": 2, "Earth Blast": 2})),
        "Earth Mage + Earth Wave": ("Earth Mage", swap(em, {"Air Strike": 4}, {"Blood Rune": 2, "Earth Wave": 2})),
        "Earth Mage + Staff of Earth": ("Earth Mage", swap(em, {"Iban's Staff": 3}, {"Staff of Earth": 3})),
        "Smoke Mage + Staff of Fire": ("Smoke Mage", swap(sm, {"Rune Pouch": 2, "Wooden Shield": 1}, {"Staff of Fire": 3})),
        "Fire Mage + Staff of Fire": ("Smoke Mage", swap(fire, {"Air Rune": 1, "Air Strike": 4},
                                                         {"Staff of Fire": 3, "Fire Rune": 2})),
        "Fire Mage (no staff, Round 17)": ("Smoke Mage", fire),
        # Magic: teleports and locations
        "Smoke Mage + Varrock Teleport": ("Smoke Mage", swap(sm, {"Wizards' Tower": 2, "Wooden Shield": 1, "Rune Pouch": 2},
                                                            {"Law Rune": 2, "Varrock Teleport": 2, "Varrock": 1})),
        "Lean Mage + Mage Arena": ("Lean Mage", swap(mg, {"Wizards' Tower": 2}, {"Mage Arena": 2})),
        "Lean Mage + Wise Old Man": ("Lean Mage", swap(mg, {"Banker": 1}, {"Wise Old Man": 1})),
        "Lean Mage + Duel Arena": ("Lean Mage", swap(mg, {"Wizards' Tower": 2}, {"Duel Arena": 2})),
        # Melee
        "Lean Melee + Smith's Hammer": ("Lean Melee", swap(lm, {"Stab": 3}, {"Smith's Hammer": 3})),
        "Mace Melee + Smith's Hammer": ("Mace Melee", swap(mm, {"Slash": 3}, {"Smith's Hammer": 3})),
        "Lean Melee + Anti-dragon Shield": ("Lean Melee", swap(lm, {"Kiteshield": 3}, {"Anti-dragon Shield": 3})),
        "Lean Melee + Shark": ("Lean Melee", swap(lm, {"Lobster": 1, "Wooden Shield": 1}, {"Shark": 2})),
        "Lean Melee + Horvik": ("Lean Melee", swap(lm, {"Lobster": 1, "Doric": 1}, {"Horvik": 2})),
        "Lean Melee + Brother Jered": ("Lean Melee", swap(lm, {"Stab": 2}, {"Brother Jered": 2})),
        "Lean Melee + Wise Old Man": ("Lean Melee", swap(lm, {"Vanaka": 1}, {"Wise Old Man": 1})),
        "Lean Melee + Lumbridge": ("Lean Melee", swap(lm, {"Stab": 2}, {"Lumbridge": 2})),
        "Lean Melee + Varrock": ("Lean Melee", swap(lm, {"Stab": 2}, {"Varrock": 2})),
        "Lean Melee + Varrock, Tablets": ("Lean Melee", swap(lm, {"Stab": 3}, {"Varrock": 1, "Varrock Teleport Tablet": 2})),
        "Lean Melee + Grand Exchange": ("Lean Melee", swap(lm, {"Stab": 2}, {"Grand Exchange": 2})),
        "Lean Melee + Duel Arena": ("Lean Melee", swap(lm, {"Stab": 2}, {"Duel Arena": 2})),
        # Ranged
        "Lean Ranged + Thessalia": ("Lean Ranged", swap(lr, {"Lowe": 1, "Wooden Shield": 1}, {"Thessalia": 2})),
        "Lean Ranged + Smith's Hammer": ("Lean Ranged", swap(lr, {"Longrange": 2}, {"Smith's Hammer": 2})),
        "Lean Ranged + Grand Exchange": ("Lean Ranged", swap(lr, {"Longrange": 2}, {"Grand Exchange": 2})),
    }
    for name, (_, d) in p.items():
        for k in [k for k, v in d.items() if not v]:
            del d[k]
        cards.check_decks({name: d})
    return p


def _job(args):
    name, deck, opp, seeds = args
    wins = 0
    for s in seeds:
        for seat in (0, 1):
            decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
            g = Game(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s)
            g.run()
            wins += g.winner == seat
    return name, opp, wins, 2 * len(seeds)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 40
    only = args[1] if len(args) > 1 else ""
    keys = [x for x in only.split("|") if x] if only else [""]
    p = {k: v for k, v in packages().items() if any(x in k for x in keys)}
    decks = {k: d for k, (_, d) in p.items()}
    bases = sorted({b for b, _ in p.values() if b})
    decks.update({"BASE " + b: FIELD[b] for b in bases})
    jobs, seed = [], 1
    for name, deck in decks.items():
        for opp in FIELD:
            jobs.append((name, deck, opp, list(range(seed, seed + n))))
            seed += n
    tot = {}
    with Pool(2) as pool:
        for name, opp, w, g in pool.imap_unordered(_job, jobs):
            t = tot.setdefault(name, [0, 0])
            t[0] += w
            t[1] += g
    pct = {k: 100 * w / g for k, (w, g) in tot.items()}
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order per matchup, best-of field")
    for b in bases:
        print(f"  {'BASE ' + b:48} {pct['BASE ' + b]:4.0f}%")
        for k, (bb, _) in p.items():
            if bb == b:
                print(f"    {k:46} {pct[k]:4.0f}% {pct[k] - pct['BASE ' + b]:+5.0f}")
        print()


if __name__ == "__main__":
    main()
