"""Round 25: destroy cards (Tinderbox, Superheat, Gunnar's Crusade). Each goes in a deck that loses to the style
whose material it destroys. Card variants change only the card under test (no field deck runs these).

vs replaced = deck with the card - host as it stands, same seeds, vs the 19 other field decks, both seats; also
split out vs the decks that run the material it destroys.

    python r26.py 100
    python r26.py 8 --smart --final
"""
import sys
from collections import defaultdict
from multiprocessing import Pool

import r22
import sweep
from cards import CARDS
from tier3 import swap

F = sweep.FIELD
TESTS = {   # card: [(host, cut)]
    "Tinderbox": [("Lean Mage", {"Wizards' Tower": 2}), ("Water Mage", {"Wizards' Tower": 2})],
    "Superheat": [("Fire Mage", {"Lobster": 2}), ("Smoke Mage", {"Wizards' Tower": 2})],
    "Gunnar's Crusade": [("Lean Melee", {"Stab": 2}), ("Rune Melee", {"Stab": 2})],
}
if "--splash" in sys.argv:          # Tinderbox in decks it isn't meant for: is it an auto-include?
    TESTS = {"Tinderbox": [("Lean Melee", {"Stab": 2}), ("Rune Thrown", {"Longrange": 1, "Doric": 1}),
                           ("Yew Ranged", {"Longrange": 2}), ("Steam Mage", {"Wizards' Tower": 2})]}
FAM = {"Tinderbox": "log", "Superheat": "bar", "Gunnar's Crusade": "rune"}
D = {"printed": 0, "+4 damage, always playable": 4, "+6 damage, always playable": 6}
VARIANTS = {k: {c: {"damage": d} for c in TESTS} if d else {} for k, d in D.items()}
VARIANTS["destroy 2"] = {c: {"destroy_n": 2} for c in TESTS}
VARIANTS["+4 damage + destroy 2"] = {c: {"damage": 4, "destroy_n": 2} for c in TESTS}
if "--splash" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "+6 damage, always playable", "+4 damage + destroy 2")}
if "--final" in sys.argv:
    VARIANTS = {k: v for k, v in VARIANTS.items() if k in ("printed", "+6 damage, always playable")}


def fam_decks(f):
    return {k for k, d in F.items() if any(CARDS[m].kind == "material" and CARDS[m].mat == f for m in d)}


def main():
    n = int([a for a in sys.argv[1:] if not a.startswith("--")][0])
    jobs = []
    for card, hs in TESTS.items():
        for host, cut in hs:
            sweep.DECKS[f"{host}|host"] = F[host]
            sweep.DECKS[f"{card}|{host}"] = swap(F[host], cut, {card: 2})
            for vn, ch in VARIANTS.items():
                vkey = f"{card}|{vn}"
                r22.JOB_CHANGES[vkey] = ch
                r22.JOB_CHANGES["host"] = {}
                for vk, dk in ((vkey, f"{card}|{host}"),) + ((("host", f"{host}|host"),) if vn == "printed" else ()):
                    for opp in sweep.OPPS:
                        if opp == host:
                            continue
                        for seat in (0, 1):
                            b = 1_000_000 + sweep.OPPS.index(opp) * 20_000 + seat * 10_000
                            jobs.append((vk, dk, opp, seat, list(range(b, b + n))))
    jobs = list({(j[0], j[1], j[2], j[3]): j for j in jobs}.values())
    print(f"{len(jobs)} jobs", file=sys.stderr, flush=True)
    res = defaultdict(lambda: [0, 0])
    with Pool(2) as p:
        for job, (vk, dk, w, g) in zip(jobs, p.imap(r22._job, jobs, chunksize=2)):
            r = res[(vk, dk, job[2])]
            r[0] += w
            r[1] += g

    def wr(vk, dk, opps):
        return 100 * sum(res[(vk, dk, o)][0] for o in opps) / sum(res[(vk, dk, o)][1] for o in opps)
    print(("Smart" if sweep.SMART else "Greedy") + f" bot, {n} games per opponent per seat. "
          "Cells: vs replaced, all opponents / vs decks running the material it destroys\n")
    print(f"{'card in host':34}" + "".join(f"{v[:26]:>28}" for v in VARIANTS))
    for card, hs in TESTS.items():
        tgt_all = fam_decks(FAM[card])
        for host, cut in hs:
            opps = [o for o in sweep.OPPS if o != host]
            tgt = [o for o in opps if o in tgt_all]
            h_a, h_t = wr("host", f"{host}|host", opps), wr("host", f"{host}|host", tgt)
            cells = [f"{wr(f'{card}|{v}', f'{card}|{host}', opps) - h_a:+5.1f} / "
                     f"{wr(f'{card}|{v}', f'{card}|{host}', tgt) - h_t:+5.1f}" for v in VARIANTS]
            print(f"{card + ' in ' + host:34}" + "".join(f"{c:>28}" for c in cells))
        print(f"{'':34}(target decks: {len(tgt)})")


if __name__ == "__main__":
    main()
