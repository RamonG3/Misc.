"""Round 22, batch 2: armor. Every armor card (not Void Knight Top) trades a reduction against one style for +3
damage taken from another, and in Round 21 the class was worth about zero. Variants apply to all 9 at once.

Armor is in most field decks, so each variant gets (1) a 20-deck round robin and (2) the Round 21 value test for
each armor card plus Horvik, Thessalia, Cloth and Leather, re-running the host, the blank deck and the card deck
(opponents change too).

    python r23.py 100 --cards        # value tests, greedy
    python r23.py 300 --rr           # round robins
"""
import json
import sys
from collections import defaultdict
from multiprocessing import Pool

import r22
import sweep
from cards import CARDS

ARMOR = [n for n, c in CARDS.items() if c.kind == "armor" and n != "Void Knight Top"]


def variant(fn):
    return {n: fn(CARDS[n]) for n in ARMOR}


TIER = {"Bronze Platebody": 1, "Wizard Robe": 1, "Leather Body": 1, "Mithril Platebody": 2, "Mystic Robe Top": 2,
        "Green D'hide Body": 2, "Rune Platebody": 3, "Infinity Top": 3, "Black D'hide Body": 3}
STYLES = ("melee", "ranged", "magic")


def main_style(c):
    return next(s for s, m in c.armor if m < 0)


def all_styles(c, main, other):
    m = main_style(c)
    return tuple((s, main if s == m else other) for s in STYLES)


VARIANTS = {
    "printed": {},
    # Round 22 batch 2a: the weakness and the action cost (no effect on anything)
    "no weakness": variant(lambda c: {"armor": tuple((s, m) for s, m in c.armor if m < 0)}),
    "weakness +1": variant(lambda c: {"armor": tuple((s, m if m < 0 else 1) for s, m in c.armor)}),
    "free to play": variant(lambda c: {"free": True}),
    "no weakness + free": variant(lambda c: {"armor": tuple((s, m) for s, m in c.armor if m < 0), "free": True}),
    # batch 2b: armor that counts against every style
    "main + -1 others": variant(lambda c: {"armor": all_styles(c, dict(c.armor)[main_style(c)], -1)}),
    "main + -1 others, free": variant(lambda c: {"armor": all_styles(c, dict(c.armor)[main_style(c)], -1), "free": True}),
    "flat -1/-2/-3 all": variant(lambda c: {"armor": all_styles(c, -TIER[c.name], -TIER[c.name])}),
}
FAM = {"Wizard Robe": "rune", "Mystic Robe Top": "rune", "Infinity Top": "rune",
       "Leather Body": "log", "Green D'hide Body": "log", "Black D'hide Body": "log"}


def style_mats(c):
    return {"req": (), "fam_req": ((FAM[c.name], TIER[c.name]),)} if c.name in FAM else {}


VARIANTS.update({
    # batch 2c: armor costs your style's own material (robes: runes, d'hide: logs; platebodies keep bars)
    "style materials": variant(style_mats),
    "style materials + -1 others": variant(lambda c: {**style_mats(c),
                                                      "armor": all_styles(c, dict(c.armor)[main_style(c)], -1)}),
    "no cost + flat -1/-2/-3 (ceiling)": variant(lambda c: {"req": (), "armor": all_styles(c, -TIER[c.name], -TIER[c.name])}),
})
if "--c" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "style materials", "style materials + -1 others",
                                                             "no cost + flat -1/-2/-3 (ceiling)")}
PLATE = ("Bronze Platebody", "Mithril Platebody", "Rune Platebody")


def plate_bonus(k):
    def f(c):
        if c.name in PLATE:
            return {"armor": all_styles(c, dict(c.armor)[main_style(c)], -1), "bonus": k}
        return {**style_mats(c), "armor": all_styles(c, dict(c.armor)[main_style(c)], -1)}
    return f


VARIANTS.update({"style mats -1 others, plate +1 attack": variant(plate_bonus(1)),
                 "style mats -1 others, plate +2 attack": variant(plate_bonus(2)),
                 "style mats -1 others, plate +3 attack": variant(plate_bonus(3))})
if "--e" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "style mats -1 others, plate +2 attack",
                                                             "style mats -1 others, plate +3 attack")}
if "--d" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "style materials + -1 others",
                                                             "style mats -1 others, plate +1 attack",
                                                             "style mats -1 others, plate +2 attack")}
if "--force" in sys.argv:          # both players put armor on whenever they legally can (tests the bot, not the card)
    from bot import HeuristicBot

    class ForceArmorBot(HeuristicBot):
        def score(self, g, i, a):
            if a[0] == "play" and CARDS[a[1]].kind == "armor" and not g.players[i].armor:
                return 500.0
            return super().score(g, i, a)
    sweep.BOT = ForceArmorBot
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "style materials + -1 others")}
if "--b" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "main + -1 others", "main + -1 others, free",
                                                             "flat -1/-2/-3 all")}
TESTED = ARMOR + ["Horvik", "Thessalia", "Cloth", "Leather"]


def cards_main(n, only_variants=None):
    T, _, _ = sweep.tests()
    jobs, meta = [], {}
    for card in TESTED:
        t = T[card][0]
        keys = {}
        for role in ("deck", "base", "host"):
            if t.get(role) is not None:
                k = f"{card}|{role}"
                sweep.DECKS[k] = t[role]
                keys[role] = k
        meta[card] = (t["label"], t["n_slots"], keys)
        for vname, ch in VARIANTS.items():
            if only_variants and vname not in only_variants:
                continue
            vkey = f"{card}|{vname}"
            r22.JOB_CHANGES[vkey] = ch
            for role, k in keys.items():
                for opp in sweep.OPPS:
                    if opp == t["host_name"]:
                        continue
                    for seat in (0, 1):
                        base = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                        jobs.append((vkey, k, opp, seat, list(range(base, base + n))))
    print(f"{len(jobs)} jobs, {len(jobs) * n} games", file=sys.stderr, flush=True)
    res = defaultdict(lambda: [0, 0])
    with Pool(2) as pool:
        for vkey, dk, w, g in pool.imap_unordered(r22._job, jobs, chunksize=2):
            r = res[(vkey, dk)]
            r[0] += w
            r[1] += g
    wr = {k: 100 * w / g for k, (w, g) in res.items()}
    out = {}
    vnames = [v for v in VARIANTS if not only_variants or v in only_variants]
    print(("Smart" if sweep.SMART else "Greedy") + f" bot, {n} games per opponent per seat. "
          "Each cell: value per copy / vs replaced\n")
    print(f"{'card':20}" + "".join(f"{v:>22}" for v in vnames))
    for card in TESTED:
        label, slots, keys = meta[card]
        cells = []
        for v in vnames:
            d = wr[(f"{card}|{v}", keys["deck"])]
            val = (d - wr[(f"{card}|{v}", keys["base"])]) / slots
            vr = d - wr[(f"{card}|{v}", keys["host"])] if "host" in keys else None
            out[f"{card}|{v}"] = {"value_per_copy": val, "vs_replaced": vr, "deck": d}
            cells.append(f"{val:+.1f}" + ("" if vr is None else f" / {vr:+.1f}"))
        print(f"{card:20}" + "".join(f"{c:>22}" for c in cells))
    json.dump(out, open("r23_cards_" + ("smart" if sweep.SMART else "greedy") + ".json", "w"), indent=1)


def rr_main(n):
    import bestof
    import r20
    r20.BOT = sweep.BOT                     # the forced-armor bot when --force
    rows = {}
    for vname, ch in VARIANTS.items():
        rows[vname], _ = r20.run(ch, r20.fire_lists()["L1 Superheat -> Iban's"], n)
        print(f"done {vname}", file=sys.stderr, flush=True)
    names = list(next(iter(rows.values())))
    style = {k: s for k, (s, _) in bestof.D.items()}
    print(f"Greedy bot, 20-deck round robin, {n} games per ordered pairing\n")
    print(f"{'deck':18}" + "".join(f"{v:>20}" for v in rows))
    for a in names:
        print(f"{a:18}" + "".join(f"{rows[v][a]:20.1f}" for v in rows))
    print()
    for st in ("melee", "ranged"):
        ds = [a for a in names if style.get(a) == st]
        print(f"{st + ' avg':18}" + "".join(f"{sum(rows[v][a] for a in ds) / len(ds):20.1f}" for v in rows))
    ds = [a for a in names if style.get(a, "magic") == "magic"]
    print(f"{'magic avg':18}" + "".join(f"{sum(rows[v][a] for a in ds) / len(ds):20.1f}" for v in rows))
    print(f"{'spread':18}" + "".join(f"{max(rows[v].values()) - min(rows[v].values()):20.1f}" for v in rows))
    json.dump(rows, open("r23_rr.json", "w"), indent=1)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 100
    if "--rr" in sys.argv:
        rr_main(n)
    else:
        cards_main(n)
