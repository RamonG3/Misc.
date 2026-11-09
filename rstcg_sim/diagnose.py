"""Why does a deck win? Per-deck breakdown of how its games go.

    python diagnose.py 150
"""
import statistics
import sys
from collections import defaultdict

import cards
from bot import HeuristicBot
from engine import Game, DAMAGE_KINDS
from cards import CARDS

ATTACKS = {"swing", "free_swing"}
ATTACK_CARDS = {"spell", "ability", "rapid", "longrange"}
DEFENSE = {"food", "shield", "armor", "prayer"}
SETUP = {"material", "pouch", "weapon", "thrown", "bow", "staff", "arrows"}


class Watcher(HeuristicBot):
    """Plays like the normal bot but records what it could and did do."""
    def __init__(self, seed, log):
        super().__init__(seed)
        self.log = log

    def choose(self, g, i, legal):
        a = super().choose(g, i, legal)
        if i == 0:
            can_attack = any(x[0] in ATTACKS or (x[0] == "play" and CARDS[x[1]].kind in ATTACK_CARDS)
                             for x in legal)
            self.log["decisions"] += 1
            self.log["attack_available"] += can_attack
            kind = a[0] if a[0] != "play" else CARDS[a[1]].kind
            group = ("attack" if kind in ATTACKS or kind in ATTACK_CARDS else
                     "setup" if kind in SETUP else "defense" if kind in DEFENSE else
                     "draw" if kind == "draw" else "punch" if kind == "punch" else "other")
            self.log["act_" + group] += 1
        return a


def profile(deck_name, deck, opponents, n):
    log = defaultdict(float)
    curve = defaultdict(list)          # own turn -> damage dealt that turn
    first, wins, games, lengths = [], 0, 0, []
    dead_end = []                      # cards in hand that can't be played yet, end of turn
    for opp_name, opp in opponents.items():
        for s in range(n):
            g = Game([deck, opp], [Watcher(s, log), HeuristicBot(s + 77)], seed=s, names=(deck_name, opp_name))
            p, o = g.players
            while g.winner is None and g.turn < 400:
                me = g.active == 0
                before = o.lp
                g.play_turn()
                if me:
                    t = g.turn // 2 if g.winner is None else (g.turn // 2 + 1)
                    curve[t].append(max(0, before - o.lp))
                    stuck = sum(1 for c in p.hand if CARDS[c].req and not p.meets(CARDS[c].req))
                    dead_end.append(stuck)
            wins += g.winner == 0
            games += 1
            lengths.append(g.turn // 2 + 1)
            if g.first_damage_turn[0]:
                first.append(g.first_damage_turn[0])
    acts = sum(v for k, v in log.items() if k.startswith("act_"))
    return {
        "win": 100 * wins / games,
        "first_damage_turn": statistics.median(first) if first else None,
        "attack_available": 100 * log["attack_available"] / log["decisions"],
        "actions": {k[4:]: 100 * v / acts for k, v in log.items() if k.startswith("act_")},
        "stuck_cards": statistics.mean(dead_end),
        "curve": {t: statistics.mean(v) for t, v in sorted(curve.items()) if 1 <= t <= 10 and len(v) > n},
        "rounds": statistics.median(lengths),
    }


def deck_stats(deck):
    total = sum(deck.values())
    mats = sum(v for c, v in deck.items() if CARDS[c].kind == "material")
    dmg = sum(v for c, v in deck.items() if CARDS[c].kind in DAMAGE_KINDS)
    families = {CARDS[c].mat for c in deck if CARDS[c].kind == "material"}
    tiers = len([c for c in deck if CARDS[c].kind == "material"])
    cheapest = min((sum(k for _, k in CARDS[c].req) for c in deck
                    if CARDS[c].kind in ("weapon", "thrown", "bow", "spell")), default=0)
    return {"materials": mats, "damage cards": dmg, "material cards (types)": tiers,
            "cheapest attack (materials)": cheapest}


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 150
    field = {**cards.LEAN_DECKS, **cards.OTHER_DECKS, **cards.GEAR_DECKS}
    subjects = ["Lean Melee", "Smoke Mage", "Lean Ranged", "Thrown Ranged", "Lean Mage", "Mage/Ranged+"]
    for name in subjects:
        opps = {k: v for k, v in field.items() if k != name}
        r = profile(name, field[name], opps, n)
        print(f"\n{name}: {deck_stats(field[name])}")
        print(f"  win {r['win']:.0f}%, first damage own turn {r['first_damage_turn']}, rounds {r['rounds']}, "
              f"attack available at {r['attack_available']:.0f}% of decisions, stuck cards in hand {r['stuck_cards']:.1f}")
        print("  actions: " + ", ".join(f"{k} {v:.0f}%" for k, v in sorted(r["actions"].items())))
        print("  damage by own turn: " + ", ".join(f"T{t} {v:.1f}" for t, v in r["curve"].items()))
