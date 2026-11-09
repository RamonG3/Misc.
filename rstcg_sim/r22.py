"""Round 22, batch 1: number tweaks for the weak cards of Round 21.

Each card keeps its Round 21 test (sweep.py: same host deck, same swap, same seeds, the 19 other field decks,
both seats). For every variant the card's stats change, and we re-run the deck with the card: value = that deck
minus the same deck with the card's slots blank; vs replaced = minus the host deck as it stands. None of these
cards is in a field deck, so the host, the blank deck and the opponents don't change between variants.

Magic Shortbow IS in field decks (Lean Ranged, Yew Ranged), so it gets a full 20-deck round robin instead.

    python r22.py 100                 # card tests, greedy
    python r22.py 100 --msb           # Magic Shortbow round robin
    python r22.py 4 --smart --only "Smite|Fire Blast"
"""
import dataclasses
import json
import sys
from collections import defaultdict
from multiprocessing import Pool

import sweep
from cards import CARDS

ORIG = dict(CARDS)
ANY2 = {"prayer": "any", "bonus": 2}
VARIANTS = {
    "Smite": {"printed": {}, "+2 to all attacks": {"Smite": ANY2},
              "+3 to all attacks": {"Smite": {"prayer": "any", "bonus": 3}}},
    "Anti-dragon Shield": {"printed": {}, "soak 7": {"Anti-dragon Shield": {"soak": 7}},
                           "soak 8": {"Anti-dragon Shield": {"soak": 8}}},
    "Grand Exchange": {"printed": {}, "free to play": {"Grand Exchange": {"free": True}},
                       "trade draws 2": {"Grand Exchange": {"draws": 2}},
                       "free + trade draws 2": {"Grand Exchange": {"free": True, "draws": 2}}},
    "Fire Blast": {"printed": {}, "Death + 3 Fire": {"Fire Blast": {"req": (("Death Rune", 1), ("Fire Rune", 3))}},
                   "17 damage": {"Fire Blast": {"damage": 17}}},
    "Fire Wave": {"printed": {}, "Blood + 4 Fire": {"Fire Wave": {"req": (("Blood Rune", 1), ("Fire Rune", 4))}},
                  "21 damage": {"Fire Wave": {"damage": 21}}},
    "Earth Blast": {"printed": {}, "14 damage": {"Earth Blast": {"damage": 14}},
                    "Death + 2 Earth": {"Earth Blast": {"req": (("Death Rune", 1), ("Earth Rune", 2))}}},
    "Earth Wave": {"printed": {}, "18 damage": {"Earth Wave": {"damage": 18}},
                   "Blood + 3 Earth": {"Earth Wave": {"req": (("Blood Rune", 1), ("Earth Rune", 3))}}},
}
MSB = {"printed": {}, "hit 6": {"Magic Shortbow": {"hit": 6}},
       "1 Normal + 1 Willow": {"Magic Shortbow": {"req": (("Normal Logs", 1), ("Willow Logs", 1))}}}

JOB_CHANGES = {}


def apply(changes):
    CARDS.clear()
    CARDS.update(ORIG)
    for name, kw in changes.items():
        CARDS[name] = dataclasses.replace(CARDS[name], **kw)


def _job(args):
    vkey, deck_key, opp, seat, seeds = args
    apply(JOB_CHANGES[vkey])
    from engine import Game
    d = sweep.DECKS[deck_key]
    w = 0
    for s in seeds:
        decks = [d, sweep.FIELD[opp]] if seat == 0 else [sweep.FIELD[opp], d]
        g = Game(decks, [sweep.BOT(s * 2 + 1), sweep.BOT(s * 2 + 2)], seed=s)
        g.run()
        w += g.winner == seat
    return vkey, deck_key, w, len(seeds)


def cards_main(n, only):
    T, _, _ = sweep.tests()
    jobs = []
    meta = {}
    for card, variants in VARIANTS.items():
        if only and card not in only:
            continue
        t = T[card][0]
        keys = {}
        for role in ("deck", "base", "host"):
            if t.get(role) is None:
                continue
            k = f"{card}|{role}"
            sweep.DECKS[k] = t[role]
            keys[role] = k
        meta[card] = (t["label"], t["n_slots"], keys)
        for vname, ch in variants.items():
            vkey = f"{card}|{vname}"
            JOB_CHANGES[vkey] = ch
            roles = ["deck"] if vname != "printed" else list(keys)   # base and host don't depend on the card
            for role in roles:
                hn = t["host_name"]
                for opp in sweep.OPPS:
                    if opp == hn:
                        continue
                    for seat in (0, 1):
                        base = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                        jobs.append((vkey, keys[role], opp, seat, list(range(base, base + n))))
    res = defaultdict(lambda: [0, 0])
    print(f"{len(jobs)} jobs, {len(jobs) * n} games", file=sys.stderr, flush=True)
    with Pool(2) as pool:
        for vkey, dk, w, g in pool.imap_unordered(_job, jobs, chunksize=2):
            r = res[(vkey, dk)]
            r[0] += w
            r[1] += g
    wr = {k: 100 * w / g for k, (w, g) in res.items()}
    out = {}
    print(("Smart" if sweep.SMART else "Greedy") + f" bot, {n} games per opponent per seat\n")
    for card, variants in VARIANTS.items():
        if card not in meta:
            continue
        label, slots, keys = meta[card]
        base = wr[(f"{card}|printed", keys["base"])]
        host = wr.get((f"{card}|printed", keys.get("host")))
        print(f"{card}  ({label})")
        for vname in variants:
            d = wr[(f"{card}|{vname}", keys["deck"])]
            row = {"deck": d, "value": d - base, "vs_replaced": None if host is None else d - host}
            out[f"{card}|{vname}"] = row
            vr = "" if host is None else f"   vs replaced {d - host:+5.1f}"
            print(f"   {vname:24} deck {d:5.1f}   value {d - base:+5.1f} ({(d - base) / slots:+.1f}/slot){vr}")
        print()
    json.dump(out, open("r22_" + ("smart" if sweep.SMART else "greedy") + ".json", "w"), indent=1)


def msb_main(n):
    import r20
    rows = {}
    for vname, ch in MSB.items():
        avg, _ = r20.run(ch, r20.fire_lists()["L1 Superheat -> Iban's"], n)
        rows[vname] = avg
        print(f"done {vname}", file=sys.stderr, flush=True)
    names = list(next(iter(rows.values())))
    import bestof
    style = {k: s for k, (s, _) in bestof.D.items()}
    print(f"Greedy bot, 20-deck round robin, {n} games per ordered pairing\n")
    print(f"{'deck':18}" + "".join(f"{v:>22}" for v in rows))
    for a in ("Lean Ranged", "Yew Ranged", "Thrown Ranged", "Rune Thrown"):
        print(f"{a:18}" + "".join(f"{rows[v][a]:22.1f}" for v in rows))
    for st in ("melee", "ranged"):
        ds = [a for a in names if style.get(a) == st]
        print(f"{st + ' avg':18}" + "".join(f"{sum(rows[v][a] for a in ds) / len(ds):22.1f}" for v in rows))
    ds = [a for a in names if style.get(a, "magic") == "magic"]
    print(f"{'magic avg':18}" + "".join(f"{sum(rows[v][a] for a in ds) / len(ds):22.1f}" for v in rows))
    print(f"{'spread':18}" + "".join(f"{max(rows[v].values()) - min(rows[v].values()):22.1f}" for v in rows))


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n = int(args[0]) if args else 100
    only = sys.argv[sys.argv.index("--only") + 1].split("|") if "--only" in sys.argv else None
    if "--msb" in sys.argv:
        msb_main(n)
    else:
        cards_main(n, only)
