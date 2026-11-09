"""Try rule, card and deck tweaks side by side against the baseline.

    python experiments.py          # 300 games per seat order per matchup
    python experiments.py 800      # slower, steadier numbers

Add your own line to EXPERIMENTS: a label, card changes, rule changes and
(optionally) replacement decklists.
"""
import sys
from dataclasses import replace

import cards
from simulate import run_all, summarize

BASE_CARDS = dict(cards.CARDS)
BASE_DECKS = {k: dict(v) for k, v in cards.STARTER_DECKS_R26.items()}


def bows(**f):
    return {n: f for n, c in BASE_CARDS.items() if c.kind == "bow"}


def bows_plus_hit(k):
    return {n: {"hit": c.hit + k} for n, c in BASE_CARDS.items() if c.kind == "bow"}


def halved_armor():
    out = {}
    for n, c in BASE_CARDS.items():
        if c.kind == "armor":
            out[n] = {"armor": tuple((s, int(m / 2)) for s, m in c.armor)}
    return out


LEAN_RANGED = {**BASE_DECKS["Ranged"], "Shortbow": 2, "Willow Shortbow": 3, "Longrange": 4}

# (label, card changes, rule changes, deck changes)
EXPERIMENTS = [
    ("Baseline", {}, {}, {}),
    ("Punch deals 1", {}, {"punch_damage": 1}, {}),
    ("Respawn costs 5 LP", {}, {"respawn_cost": 5}, {}),
    ("Arrows and built-in shots back to 3", {}, {"arrow_shots": 3, "bow_shots": 3}, {}),
    ("Bows -1 hit", bows_plus_hit(-1), {}, {}),
    ("Armor modifiers halved", halved_armor(), {}, {}),
    ("Two fewer bows in the Ranged deck", {}, {}, {"Ranged": LEAN_RANGED}),
]


def setup(card_changes, deck_changes):
    cards.CARDS.clear()
    cards.CARDS.update(BASE_CARDS)
    for name, fields in card_changes.items():
        cards.CARDS[name] = replace(BASE_CARDS[name], **fields)
    for name, deck in BASE_DECKS.items():
        cards.STARTER_DECKS_R26[name].clear()
        cards.STARTER_DECKS_R26[name].update(deck_changes.get(name, deck))


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    print(f"{'':48}{'R v Mage':>9}{'R v Melee':>10}{'Mage v Me':>10}{'1st wins':>9}{'rounds':>9}  punch share M/Me/R")
    for label, card_changes, rules, deck_changes in EXPERIMENTS:
        setup(card_changes, deck_changes)
        s = summarize(run_all(n, rules))
        m = s["matrix"]
        r = [x["median_rounds"] for x in s["matchups"]]
        ps = [s["decks"][d]["damage_share"].get("punch", 0) for d in ("Air Mage", "Melee", "Ranged")]
        print(f"{label:48}{100*m['Ranged']['Air Mage']:8.0f}%{100*m['Ranged']['Melee']:9.0f}%"
              f"{100*m['Air Mage']['Melee']:9.0f}%{100*s['first_player_win']:8.0f}%"
              f"{min(r):>5.0f}-{max(r):<3.0f}  " + "/".join(f"{100*x:.0f}" for x in ps) + "%")
    setup({}, {})
