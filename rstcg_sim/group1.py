"""Round 17: the 42 cards the sim knows but no current deck uses, retested against the Round 11 field
under today's rules (deck as life, Round 16 fixes). Each package goes into the deck it fits.

    python group1.py 60            # greedy bot, 60 games per seat order per matchup
    python group1.py 8 --smart     # playout bot
    python group1.py 60 Mace       # only packages whose name contains "Mace"
"""
import sys
from multiprocessing import Pool

import cards
import hybrid_cards as h
import tier3
from bot import HeuristicBot
from engine import Game
from planner import RolloutBot
from tier3 import swap

FIELD = h.round11_field()
SMART = "--smart" in sys.argv
for _a in sys.argv:          # --set=Card:field=value,... tries a stat change
    if _a.startswith("--set="):
        import dataclasses
        for part in _a[6:].split(";"):
            _name, kv = part.split(":")
            _f, _v = kv.split("=")
            cards.CARDS[_name] = dataclasses.replace(cards.CARDS[_name], **{_f: int(_v)})
BOT = RolloutBot if SMART else HeuristicBot


def packages():
    f = FIELD
    lm, mg, lr, sm, tr = f["Lean Melee"], f["Lean Mage"], f["Lean Ranged"], f["Smoke Mage"], f["Thrown Ranged"]
    t3 = tier3.tests()
    p = {
        # Melee
        "Mace Melee (Bronze/Mithril Mace, Crush)": ("Lean Melee", swap(lm, {"Bronze Dagger": 2, "Bronze Scimitar": 2, "Mithril Sword": 4, "Stab": 4},
                                                                  {"Bronze Mace": 3, "Mithril Mace": 4, "Crush": 4, "Lobster": 1})),
        "Lean Melee + Mithril Platebody": ("Lean Melee", swap(lm, {"Bronze Platebody": 2}, {"Mithril Platebody": 2})),
        "Lean Melee + Ancient Mace, Excalibur, Anchor": ("Lean Melee", swap(lm, {"Bronze Dagger": 2, "Mithril Sword": 2, "Stab": 2},
                                                                          {"Ancient Mace": 2, "Excalibur": 2, "Barrelchest Anchor": 2})),
        "Lean Melee + Redemption": ("Lean Melee", swap(lm, {"Burst of Strength": 2}, {"Redemption": 2})),
        "Lean Melee + Vampyre/Demon/Dragon Slayer": ("Lean Melee", swap(lm, {"Stab": 2, "Bronze Dagger": 2},
                                                                      {"Vampyre Slayer": 2, "Demon Slayer": 1, "Dragon Slayer": 1})),
        "Lean Melee + Ernest the Chicken": ("Lean Melee", swap(lm, {"Stab": 2}, {"Ernest the Chicken": 2})),
        "Lean Melee + Prince Ali Rescue": ("Lean Melee", swap(lm, {"Stab": 2}, {"Prince Ali Rescue": 2})),
        "Lean Melee + Restless Ghost, Piety": ("Lean Melee", swap(lm, {"Stab": 2, "Burst of Strength": 2},
                                                                 {"The Restless Ghost": 2, "Piety": 2})),
        "Lean Melee + Cook's Assistant": ("Lean Melee", swap(lm, {"Stab": 4}, {"Cook's Assistant": 2, "Lobster": 2})),
        "Lean Melee + Rune Scimitar": ("Lean Melee", t3["Lean Melee + Rune Scimitar"][1]),
        "Lean Melee + Rune Mace, Crush": ("Lean Melee", t3["Lean Melee + Rune Mace, Crush"][1]),
        "Rune Melee (built for tier 3)": ("Lean Melee", t3["Rune Melee (built for tier 3)"][1]),
        # Ranged
        "Lean Ranged + Black D'hide Body": ("Lean Ranged", swap(lr, {"Leather Body": 2, "Wooden Shield": 1}, {"Black D'hide Body": 2, "Leather": 1})),
        "Lean Ranged + Yew Shortbow, Rune Arrows": ("Lean Ranged", t3["Lean Ranged + Yew, Rune Arrows"][1]),
        "Yew Ranged (built for tier 3)": ("Lean Ranged", t3["Yew Ranged (built for tier 3)"][1]),
        "Thrown Ranged + Rune thrown": ("Thrown Ranged", t3["Thrown Ranged + Rune thrown"][1]),
        "Rune Thrown (built for tier 3)": ("Thrown Ranged", t3["Rune Thrown (built for tier 3)"][1]),
        # Magic
        "Lean Mage + Air Wave": ("Lean Mage", t3["Lean Mage + Air Wave"][1]),
        "Smoke Mage + Fire Blast": ("Smoke Mage", t3["Smoke Mage + Fire Blast"][1]),
        "Smoke Mage + Fire Wave": ("Smoke Mage", swap(sm, {"Air Strike": 3, "Wizards' Tower": 1, "Archmage Sedridor": 1, "Kiteshield": 1},
                                                     {"Blood Rune": 3, "Fire Wave": 3})),
        "Smoke Mage + 2 Fire Blast (light)": ("Smoke Mage", swap(sm, {"Air Strike": 2, "Fire Bolt": 1, "Wizards' Tower": 1},
                                                                {"Death Rune": 2, "Fire Blast": 2})),
        "Smoke Mage + 2 Fire Wave (light)": ("Smoke Mage", swap(sm, {"Air Strike": 2, "Fire Bolt": 1, "Wizards' Tower": 1},
                                                               {"Blood Rune": 2, "Fire Wave": 2})),
        "Mace Melee + Rune Mace": ("Lean Melee", swap(lm, {"Bronze Dagger": 2, "Bronze Scimitar": 2, "Mithril Sword": 4, "Stab": 4, "Bronze Bar": 2, "Mithril Bar": 2},
                                                     {"Bronze Mace": 2, "Mithril Mace": 3, "Rune Mace": 2, "Crush": 4, "Rune Bar": 5})),
        "Lean Mage + Mystic Robe Top": ("Lean Mage", swap(mg, {"Wizard Robe": 2}, {"Mystic Robe Top": 2})),
        "Lean Mage + Infinity Top": ("Lean Mage", swap(mg, {"Wizard Robe": 2, "Banker": 1}, {"Infinity Top": 2, "Cloth": 1})),
        "Lean Mage + Protect from Melee": ("Lean Mage", swap(mg, {"Wooden Shield": 2}, {"Protect from Melee": 2})),
        "Lean Mage + Rune Mysteries, Sheep Shearer": ("Lean Mage", swap(mg, {"Banker": 1, "Wooden Shield": 2, "Wizards' Tower": 1},
                                                                      {"Rune Mysteries": 2, "Sheep Shearer": 2})),
        "Smoke Mage + Superheat": ("Smoke Mage", swap(sm, {"Air Strike": 3}, {"Superheat": 3})),
        "Earth Mage (Earth spells, Crumble, Dust Rune)": (None, cards.EARTH_MAGE),
        "Fire Mage, built for Blast and Wave": ("Lean Mage", {
            "Fire Rune": 12, "Chaos Rune": 4, "Death Rune": 2, "Blood Rune": 2, "Cloth": 2, "Fire Strike": 4, "Fire Bolt": 4,
            "Fire Blast": 3, "Fire Wave": 2, "Rune Pouch": 4, "Wizard Robe": 2, "Kiteshield": 3, "Wooden Shield": 3,
            "Mirror Shield": 3, "Archmage Sedridor": 2, "Wizards' Tower": 2, "Augury": 2, "Superheat": 2, "Lobster": 2}),
        "Fire Mage without Blast and Wave": ("Lean Mage", {
            "Fire Rune": 12, "Chaos Rune": 4, "Cloth": 2, "Fire Strike": 4, "Fire Bolt": 4, "Smoke Flurry": 0,
            "Rune Pouch": 4, "Wizard Robe": 2, "Kiteshield": 4, "Wooden Shield": 3, "Mirror Shield": 3,
            "Archmage Sedridor": 2, "Wizards' Tower": 2, "Augury": 2, "Superheat": 3, "Lobster": 4, "Air Rune": 1, "Air Strike": 4}),
        "Earth Mage, modern build": ("Lean Mage", {
            "Earth Rune": 8, "Air Rune": 4, "Dust Rune": 4, "Chaos Rune": 4, "Cloth": 2, "Earth Strike": 4, "Earth Bolt": 4,
            "Crumble": 2, "Air Strike": 4, "Air Bolt": 4, "Iban's Staff": 3, "Rune Pouch": 3, "Wizard Robe": 2,
            "Kiteshield": 3, "Wooden Shield": 3, "Mirror Shield": 3, "Archmage Sedridor": 2, "Augury": 1}),
        "Anti-Melee (Superheat, Protect from Melee)": (None, cards.ANTI_MELEE),
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
    n = int(args[0]) if args else 60
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
    print(("Smart" if SMART else "Greedy") + f" bot, {n} games per seat order per matchup, Round 11 field, Round 16 rules")
    for b in bases + [None]:
        if b:
            print(f"  {'BASE ' + b:48} {pct['BASE ' + b]:4.0f}%")
        for k, (bb, _) in p.items():
            if bb == b:
                ref = pct["BASE " + b] if b else 50.0
                print(f"    {k:46} {pct[k]:4.0f}% {pct[k] - ref:+5.0f}")
        print()


if __name__ == "__main__":
    main()
