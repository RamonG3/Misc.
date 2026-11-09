"""Round 21: every card in Set 1, tested under current rules (Round 19 cards, Round 20 Fire Mage list).

One measure for every card: VALUE OVER BLANK. The card in a deck that would run it, against the same
deck with those slots replaced by a Blank (a card that can never be played). Both decks play the same
seeds against the other 19 decks of the 20-deck field, both seats, so the difference is the card.
Reported as win-rate points for the whole package and per copy.

- Field cards (in a current field deck): blanked in the deck that runs the most copies.
- Basic materials: 2 copies blanked (their marginal value; they are tested through what they unlock).
- Specialty runes: also compared with the two basic runes they stand for.
- Off-field cards: swapped into a natural host (mostly the swaps used in earlier rounds);
  "vs replaced" = the swapped deck minus the host as it stands.
- Hybrid cards: blanked in the Round 11 hybrid decks built around them.
- Quests: a standalone swap, and blanked in the Quest Point Cape deck with that cape in play.
- Capes: test.py-style cape zone; the deck brings the cape, the opponent brings none; vs no cape.

Also counts USES: how often the tested card is played (or reacts) per game, by the deck under test.

    python sweep.py 100            # greedy, 100 games per opponent per seat
    python sweep.py 20 --smart --only "Granite Maul|Shark"
"""
import json
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

import cards
import hybrid_cards as hyb
import r19
import r20
from bot import HeuristicBot
from cards import CARDS, Card
from capes import CAPES
from engine import Game
from planner import RolloutBot
from tier3 import swap

SMART = "--smart" in sys.argv
BOT = RolloutBot if SMART else HeuristicBot
CARDS["Blank"] = Card("Blank", "spell", req=(("Blank Rune", 99),))   # can never be played

BASIC = {"Air Rune", "Water Rune", "Earth Rune", "Fire Rune", "Chaos Rune", "Death Rune", "Blood Rune",
         "Law Rune", "Bronze Bar", "Mithril Bar", "Rune Bar", "Normal Logs", "Willow Logs", "Yew Logs",
         "Cloth", "Leather"}


def field():
    f = r19.decks(False)
    f["Fire Mage"] = r20.fire_lists()["L1 Superheat -> Iban's"]
    return f


FIELD = field()
OPPS = list(FIELD)


def blank(deck, cards_out):
    """deck with these {card: n} replaced by Blanks."""
    d = dict(deck)
    k = 0
    for c, n in cards_out.items():
        assert d.get(c, 0) >= n, (c, n, d.get(c))
        d[c] -= n
        k += n
    d = {c: n for c, n in d.items() if n}
    d["Blank"] = d.get("Blank", 0) + k
    return d


def host_of(card):
    best = max(FIELD, key=lambda k: FIELD[k].get(card, 0))   # first deck with the most copies wins ties
    return best


def tests():
    """card -> list of tests. A test: dict(label, deck, base, host, cape, n_slots, usecard)."""
    T = defaultdict(list)
    F = FIELD
    lm, lr, mg, em, rm, rt = (F["Lean Melee"], F["Lean Ranged"], F["Lean Mage"], F["Earth Mage"],
                              F["Rune Melee"], F["Rune Thrown"])
    fire, water, smoke = F["Fire Mage"], F["Water Mage"], F["Smoke Mage"]
    prayer_melee = swap(lm, {"Burst of Strength": 3, "Stab": 2}, {"Piety": 3, "Smite": 2})

    def add(card, label, host_name, host, cut, put, cape=None, blank_out=None):
        deck = swap(host, cut, put) if cut is not None else dict(host)
        bo = blank_out or {card: deck[card]}
        T[card].append(dict(label=label, host_name=host_name, deck=deck, base=blank(deck, bo),
                            host=host if cut is not None else None, cape=cape, n_slots=sum(bo.values()),
                            copies=deck[card]))

    # ---- field cards ----
    in_field = sorted({c for d in F.values() for c, n in d.items() if n})
    for c in in_field:
        h = host_of(c)
        if c in BASIC:
            T[c].append(dict(label=f"{h}: 2 of {F[h][c]} blanked", host_name=h, deck=F[h],
                             base=blank(F[h], {c: 2}), host=None, cape=None, n_slots=2, copies=F[h][c]))
        else:
            add(c, f"{h} ({F[h][c]})", h, F[h], None, None)
        if CARDS[c].combo:
            a, b = CARDS[c].combo
            n = F[h][c]
            T[c][-1]["vs_basics"] = swap(F[h], {c: n}, {a: n // 2 + n % 2, b: n // 2})
    # ---- off-field cards: (host, cut, put) ----
    S = {
        "Staff of Air": ("Lean Mage", mg, {"Staff of Light": 2}, {"Staff of Air": 2}),
        "Staff of Earth": ("Earth Mage", em, {"Iban's Staff": 3}, {"Staff of Earth": 3}),
        "Superheat": ("Fire Mage", fire, {"Lobster": 3}, {"Superheat": 3}),
        "Gunnar's Crusade": ("Lean Melee", lm, {"Stab": 2}, {"Gunnar's Crusade": 2}),
        "Shortbow": ("Lean Ranged", lr, {"Magic Shortbow": 3}, {"Shortbow": 3}),
        "Willow Shortbow": ("Lean Ranged", lr, {"Magic Shortbow": 3}, {"Willow Shortbow": 3}),
        "Green D'hide Body": ("Lean Ranged", lr, {"Leather Body": 2}, {"Green D'hide Body": 2}),
        "Black D'hide Body": ("Lean Ranged", lr, {"Leather Body": 2, "Wooden Shield": 1},
                              {"Black D'hide Body": 2, "Leather": 1}),
        "Mithril Platebody": ("Lean Melee", lm, {"Bronze Platebody": 2}, {"Mithril Platebody": 2}),
        "Mystic Robe Top": ("Lean Mage", mg, {"Wizard Robe": 2}, {"Mystic Robe Top": 2}),
        "Shrimp": ("Fire Mage", fire, {"Lobster": 4}, {"Shrimp": 4}),
        "Swordfish": ("Fire Mage", fire, {"Lobster": 4}, {"Swordfish": 4}),
        "Shark": ("Fire Mage", fire, {"Lobster": 4}, {"Shark": 4}),
        "Karambwan": ("Fire Mage", fire, {"Lobster": 2}, {"Karambwan": 2}),
        "Protect from Magic": ("Lean Melee", lm, {"Stab": 2}, {"Protect from Magic": 2}),
        "Protect from Missiles": ("Lean Melee", lm, {"Stab": 2}, {"Protect from Missiles": 2}),
        "Protect from Melee": ("Lean Mage", mg, {"Wooden Shield": 2}, {"Protect from Melee": 2}),
        "Mystic Will": ("Lean Mage", mg, {"Augury": 2}, {"Mystic Will": 2}),
        "Piety": ("Lean Melee", lm, {"Burst of Strength": 3}, {"Piety": 3}),
        "Smite": ("Lean Melee", lm, {"Burst of Strength": 2}, {"Smite": 2}),
        "Redemption": ("Lean Melee", lm, {"Burst of Strength": 2}, {"Redemption": 2}),
        "Rune Mace": ("Rune Melee", rm, {"Rune Scimitar": 3, "Slash": 2}, {"Rune Mace": 3, "Crush": 2}),
        "Rune Darts": ("Rune Thrown", rt, {"Rune Knives": 3}, {"Rune Darts": 3}),
        "Dragon Dagger": ("Lean Melee", lm, {"Vanaka": 1}, {"Dragon Dagger": 1}),
        "Granite Maul": ("Lean Melee", lm, {"Vanaka": 1}, {"Granite Maul": 1}),
        "Dark Bow": ("Lean Ranged", lr, {"Wooden Shield": 1}, {"Dark Bow": 1}),
        "Staff of the Dead": ("Lean Mage", mg, {"Banker": 1}, {"Staff of the Dead": 1}),
        "Wise Old Man": ("Lean Melee", lm, {"Vanaka": 1}, {"Wise Old Man": 1}),
        "Fire Blast": ("Fire Mage", fire, {"Lobster": 2, "Kiteshield": 1, "Wooden Shield": 1},
                       {"Death Rune": 2, "Fire Blast": 2}),
        "Fire Wave": ("Fire Mage", fire, {"Lobster": 2, "Kiteshield": 1, "Wooden Shield": 1},
                      {"Blood Rune": 2, "Fire Wave": 2}),
        "Earth Blast": ("Earth Mage", em, {"Air Strike": 4}, {"Death Rune": 2, "Earth Blast": 2}),
        "Earth Wave": ("Earth Mage", em, {"Air Strike": 4}, {"Blood Rune": 2, "Earth Wave": 2}),
        "Water Wave": ("Water Mage", water, {"Death Rune": 3, "Water Blast": 3}, {"Blood Rune": 3, "Water Wave": 3}),
        "Tinderbox": ("Lean Mage", mg, {"Wooden Shield": 2}, {"Tinderbox": 2}),
        "Smith's Hammer": ("Lean Melee", lm, {"Stab": 3}, {"Smith's Hammer": 3}),
        "Anti-dragon Shield": ("Lean Melee", lm, {"Kiteshield": 3}, {"Anti-dragon Shield": 3}),
        "Horvik": ("Lean Melee", lm, {"Lobster": 1, "Doric": 1}, {"Horvik": 2}),
        "Thessalia": ("Lean Ranged", lr, {"Lowe": 1, "Wooden Shield": 1}, {"Thessalia": 2}),
        "Brother Jered": ("Lean Melee + Piety/Smite", prayer_melee, {"Stab": 2}, {"Brother Jered": 2}),
        "Wilderness": ("Lean Melee", lm, {"Stab": 2}, {"Wilderness": 2}),
        "Lumbridge": ("Lean Melee", lm, {"Stab": 2}, {"Lumbridge": 2}),
        "Grand Exchange": ("Lean Melee", lm, {"Stab": 2}, {"Grand Exchange": 2}),
        "Duel Arena": ("Lean Mage", mg, {"Wizards' Tower": 2}, {"Duel Arena": 2}),
        "Mage Arena": ("Lean Mage", mg, {"Wizards' Tower": 2}, {"Mage Arena": 2}),
    }
    for c, (hn, h, cut, put) in S.items():
        bo = {c: put[c]}
        sup = {k: v for k, v in put.items() if k != c and k not in ("Crush", "Leather")}
        if sup:
            bo.update(sup)                       # the package: the card and the runes it needs
        add(c, f"{hn}: {fmt(cut)} -> {fmt(put)}", hn, h, cut, put, blank_out=bo)
    # Varrock package: Varrock, the tablet, the spell and Law Runes
    vt = ("Lean Melee", lm, {"Stab": 3}, {"Varrock": 1, "Varrock Teleport Tablet": 2})
    add("Varrock", f"Lean Melee: {fmt(vt[2])} -> {fmt(vt[3])}", *vt, blank_out={"Varrock": 1, "Varrock Teleport Tablet": 2})
    add("Varrock Teleport Tablet", f"Lean Melee: {fmt(vt[2])} -> {fmt(vt[3])} (tablets blanked)", *vt)
    vs = ("Smoke Mage", smoke, {"Wizards' Tower": 2, "Wooden Shield": 1, "Rune Pouch": 2},
          {"Law Rune": 2, "Varrock Teleport": 2, "Varrock": 1})
    add("Varrock Teleport", f"Smoke Mage: {fmt(vs[2])} -> {fmt(vs[3])}", *vs,
        blank_out={"Varrock Teleport": 2, "Law Rune": 2})
    add("Law Rune", f"Smoke Mage + Varrock Teleport: 2 Law blanked", *vs, blank_out={"Law Rune": 2})
    # ---- hybrid decks (Round 11) ----
    HF = hyb.FIELD
    for c, hn in (("Magical Longsword", "Mage/Melee+"), ("Saradomin Sword", "Mage/Melee+"),
                  ("Shieldbow", "Melee/Ranged+"), ("Air Battlestaff", "Mage/Ranged+")):
        add(c, f"{hn} ({HF[hn][c]})", hn, HF[hn], None, None)
    for c, key in (("Enchanted Bar", "Mage/Melee+ + Enchanted Bar"), ("Mithril Grapple", "Melee/Ranged+ + Grapple"),
                   ("Enchanted Logs", "Mage/Ranged+ + Enchanted Logs"), ("Void Knight Top", "Mage/Melee+ + Void")):
        hn, d = hyb.tests()[key]
        T[c].append(dict(label=key, host_name=hn, deck=d, base=blank(d, {c: d[c]}), host=HF[hn], cape=None,
                         n_slots=d[c], copies=d[c]))
        if CARDS[c].combo:
            a, b = CARDS[c].combo
            T[c][-1]["vs_basics"] = swap(d, {c: d[c]}, {a: d[c] // 2, b: d[c] - d[c] // 2})
    # ---- quests ----
    qs = {"Cook's Assistant": ({"Stab": 4}, {"Cook's Assistant": 2, "Lobster": 2}),
          "Sheep Shearer": None, "Rune Mysteries": None,
          "Doric's Quest": ({"Stab": 2}, {"Doric's Quest": 2}),
          "Prince Ali Rescue": ({"Stab": 2}, {"Prince Ali Rescue": 2}),
          "Vampyre Slayer": ({"Stab": 2}, {"Vampyre Slayer": 2}),
          "Ernest the Chicken": ({"Stab": 2}, {"Ernest the Chicken": 2}),
          "Demon Slayer": ({"Stab": 2}, {"Demon Slayer": 2}),
          "Dragon Slayer": ({"Stab": 2}, {"Dragon Slayer": 2}),
          "Romeo & Juliet": ({"Stab": 2}, {"Romeo & Juliet": 2}),
          "Goblin Diplomacy": ({"Stab": 2}, {"Goblin Diplomacy": 2})}
    for c, v in qs.items():
        if v:
            add(c, f"Lean Melee: {fmt(v[0])} -> {fmt(v[1])}", "Lean Melee", lm, *v, blank_out={c: v[1][c]})
    add("Sheep Shearer", "Lean Mage: Banker 1, Wooden Shield 1 -> Sheep Shearer 2", "Lean Mage", mg,
        {"Banker": 1, "Wooden Shield": 1}, {"Sheep Shearer": 2})
    add("Rune Mysteries", "Lean Mage: Wooden Shield 2 -> Rune Mysteries 2", "Lean Mage", mg,
        {"Wooden Shield": 2}, {"Rune Mysteries": 2})
    add("The Restless Ghost", "Lean Melee + Piety/Smite: Stab 2 -> Restless Ghost 2", "Lean Melee + Piety/Smite",
        prayer_melee, {"Stab": 2}, {"The Restless Ghost": 2})
    # quests in a Quest Point Cape deck, cape in play: R&J 3 + Goblin 2 + Doric's 2 + one more quest 2
    qp = swap(lm, {"Stab": 4, "Bronze Dagger": 2, "Mithril Sword": 2, "Lobster": 1},
              {"Romeo & Juliet": 3, "Goblin Diplomacy": 2, "Doric's Quest": 2, "Vampyre Slayer": 2})
    for c in ("Romeo & Juliet", "Goblin Diplomacy", "Doric's Quest", "Vampyre Slayer"):
        add(c, "Quest deck + Quest Point Cape", "Quest deck", qp, None, None, cape="Quest Point Cape")
    return T, qp, prayer_melee


def cape_tests(qp, prayer_melee):
    F = FIELD
    lm, lr, mg = F["Lean Melee"], F["Lean Ranged"], F["Lean Mage"]
    return {
        "Quest Point Cape": ("Quest deck", qp),
        "Attack Cape": ("Lean Melee", lm),
        "Ranged Cape": ("Lean Ranged", lr),
        "Magic Cape": ("Lean Mage", mg),
        "Defence Cape": ("Lean Melee + Protects", swap(lm, {"Stab": 4, "Bronze Dagger": 1},
                                                        {"Parry": 1, "Protect from Magic": 2, "Protect from Missiles": 2})),
        "Prayer Cape": ("Lean Melee + Piety/Smite", prayer_melee),
        "Cooking Cape": ("Lean Melee + food", swap(lm, {"Stab": 4, "Bronze Dagger": 2, "Wooden Shield": 1},
                                                   {"Lobster": 3, "Swordfish": 4})),
        "Smithing Cape": ("Lean Melee", lm),
        "Woodcutting Cape": ("Lean Ranged", lr),
        "Runecrafting Cape": ("Lean Mage", mg),
    }


def fmt(d):
    return ", ".join(f"{k} {v}" for k, v in d.items())



class CGame(Game):
    """Counts the watched player's card plays and reactions. clone() builds a plain Game, so a
    smart bot's simulated futures are never counted and never touch this game."""

    def apply(self, i, action):
        if i == self.seat and action and action[0] == "play" and action[1] in self.watch:
            self.count[action[1]] += 1
        return super().apply(i, action)

    def offer_reaction(self, i, amount, style, src):
        choice = super().offer_reaction(i, amount, style, src)
        if choice and 1 - i == self.seat and choice in self.watch:
            self.count[choice] += 1
        return choice


# ---------------- running ----------------
DECKS = {}


def _job(args):
    key, cape, opp, seat, seeds, watch = args
    deck = DECKS[key]
    wins, games, cape_wins = 0, 0, 0
    uses = Counter()
    for s in seeds:
        decks = [deck, FIELD[opp]] if seat == 0 else [FIELD[opp], deck]
        rules = {"capes": ([cape, None] if seat == 0 else [None, cape])} if cape else None
        g = CGame(decks, [BOT(s * 2 + 1), BOT(s * 2 + 2)], seed=s, rules=rules)
        g.count, g.watch, g.seat = Counter(), watch, seat
        g.run()
        count = g.count
        games += 1
        wins += g.winner == seat
        cape_wins += g.winner == seat and g.end_reason == "cape"
        uses.update(count)
    return key, cape, opp, seat, wins, games, dict(uses), cape_wins


def _ijob(arg):
    j, job = arg
    return j, _job(job)


def runs_for(T, capes):
    """Unique (deck key, cape, host name, watch) runs."""
    R = {}
    for card, ts in T.items():
        for t in ts:
            for role in ("deck", "base", "host", "vs_basics"):
                d = t.get(role)
                if d is None:
                    continue
                key = json.dumps(sorted(d.items())) + "|" + str(t["cape"])
                t[role + "_key"] = key
                R.setdefault(key, [d, t["cape"], t["host_name"], set()])
                R[key][3].add(card)
    for cape, (hn, d) in capes.items():
        for c in (cape, None):
            key = json.dumps(sorted(d.items())) + "|" + str(c)
            R.setdefault(key, [d, c, hn, set()])
    return R


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 50
    only = None
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1].split("|")
    T, qp, pm = tests()
    capes = cape_tests(qp, pm)
    if only:
        T = {k: v for k, v in T.items() if k in only}
        capes = {k: v for k, v in capes.items() if k in only}
    R = runs_for(T, capes)
    for card, ts in T.items():
        for t in ts:
            cards.check_decks({card: t['deck']})
    for key, (d, cape, hn, watch) in R.items():
        DECKS[key] = d
    jobs = []
    for key, (d, cape, hn, watch) in R.items():
        for opp in OPPS:
            if opp == hn:
                continue
            for seat in (0, 1):
                base = 1_000_000 + OPPS.index(opp) * 20_000 + seat * 10_000
                jobs.append((key, cape, opp, seat, list(range(base, base + n)), frozenset(watch)))
    print(f"{len(R)} deck runs, {len(jobs)} jobs, {len(jobs) * n} games", file=sys.stderr, flush=True)
    bot = "smart" if SMART else "greedy"
    res = defaultdict(lambda: [0, 0, Counter(), 0])

    def load(path):
        try:
            ck = json.load(open(path))
        except FileNotFoundError:
            return set()
        for k, (w, g, u, cw) in ck["res"].items():
            r = res[k]
            r[0] += w; r[1] += g; r[2].update(u); r[3] += cw
        return set(ck["done"])

    if "--merge" in sys.argv:                  # combine shard files: --merge N
        parts = int(sys.argv[sys.argv.index("--merge") + 1])
        done = set()
        for k in range(parts):
            done |= load(f"sweep_{bot}_part{k}.json")
        assert len(done) == len(jobs), f"{len(done)} of {len(jobs)} jobs done"
    else:
        part, parts = 0, 1
        if "--part" in sys.argv:              # --part k/N: every Nth job, checkpointed, resumable
            part, parts = map(int, sys.argv[sys.argv.index("--part") + 1].split("/"))
        path = f"sweep_{bot}_part{part}.json"
        done = load(path)
        mine = [(j, job) for j, job in enumerate(jobs) if j % parts == part and j not in done]
        print(f"part {part}/{parts}: {len(mine)} jobs to run, {len(done)} already done", file=sys.stderr, flush=True)

        def save():
            json.dump({"done": sorted(done), "res": {k: [v[0], v[1], dict(v[2]), v[3]] for k, v in res.items()}},
                      open(path + ".tmp", "w"))
            import os
            os.replace(path + ".tmp", path)
        with Pool(2) as pool:
            for j, (key, cape, opp, seat, w, g, u, cw) in pool.imap_unordered(_ijob, mine, chunksize=1):
                r = res[key]
                r[0] += w; r[1] += g; r[2].update(u); r[3] += cw
                done.add(j)
                if len(done) % 10 == 0:
                    save()
                    print(f"  {len(done)} done", file=sys.stderr, flush=True)
        save()
        if parts > 1:
            print(f"wrote {path}", file=sys.stderr)
            return
    out = {"n": n, "bot": "smart" if SMART else "greedy", "cards": {}, "capes": {}}
    wr = {k: 100 * v[0] / v[1] for k, v in res.items()}
    for card, ts in T.items():
        rows = []
        for t in ts:
            k = t["deck_key"]
            row = dict(label=t["label"], copies=t["copies"], n_slots=t["n_slots"], cape=t["cape"],
                       deck_wr=wr[k], value=wr[k] - wr[t["base_key"]],
                       uses_per_game=res[k][2][card] / res[k][1])
            if t.get("host_key"):
                row["vs_replaced"] = wr[k] - wr[t["host_key"]]
            if t.get("vs_basics_key"):
                row["vs_basics"] = wr[k] - wr[t["vs_basics_key"]]
            rows.append(row)
        out["cards"][card] = rows
    for cape, (hn, d) in capes.items():
        k1 = json.dumps(sorted(d.items())) + "|" + cape
        k0 = json.dumps(sorted(d.items())) + "|None"
        out["capes"][cape] = dict(host=hn, with_cape=wr[k1], without=wr[k0], value=wr[k1] - wr[k0],
                                  cape_win_share=100 * res[k1][3] / max(1, res[k1][0]))
    path = "sweep_" + out["bot"] + ".json"
    json.dump(out, open(path, "w"), indent=1)
    print(f"wrote {path}", file=sys.stderr)


if __name__ == "__main__":
    main()
