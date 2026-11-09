"""Round 18 probes: card-stat variants for group 2 packages. python g2probe.py N"""
import dataclasses, sys
from multiprocessing import Pool
import cards, group2
from tier3 import swap

C = cards.CARDS
def setc(name, **kw):
    C[name] = dataclasses.replace(C[name], **kw)

VARIANTS = {
    "as printed": {},
    "Water heal 4": {n: {"heals": 4} for n in ("Water Strike", "Water Bolt", "Water Blast", "Water Wave")},
    "Water dmg = Air (6/10/13/17)": {"Water Strike": {"damage": 6}, "Water Bolt": {"damage": 10},
                                     "Water Blast": {"damage": 13}, "Water Wave": {"damage": 17}},
    "Flurries cost 1+2 (like Smoke)": {
        "Mist Flurry": {"req": (("Air Rune", 1), ("Water Rune", 2))},
        "Dust Flurry": {"req": (("Air Rune", 1), ("Earth Rune", 2))}},
    "Mist/Dust Flurry 7 damage": {"Mist Flurry": {"damage": 7}, "Dust Flurry": {"damage": 7}},
    "Elemental staves hit 3": {n: {"hit": 3} for n in ("Staff of Water", "Staff of Earth", "Staff of Fire")},
}
ONLY = ["Water Mage (Lean Mage on Water)", "Mist Mage (Air/Water flurry)", "Dust Mage (Air/Earth flurry)",
        "Earth Mage + Staff of Earth", "Fire Mage + Staff of Fire"]

def run(n):
    orig = dict(C)
    out = {}
    for vname, changes in VARIANTS.items():
        C.clear(); C.update(orig)
        for card, kw in changes.items():
            setc(card, **kw)
        p = group2.packages()
        decks = {k: p[k][1] for k in ONLY}
        decks["Water Mage + Iban/Light (2 Air)"] = swap(p[ONLY[0]][1], {"Staff of Water": 3, "Banker": 1, "Water Rune": 2},
                                                         {"Iban's Staff": 1, "Staff of Light": 2, "Air Rune": 3})
        jobs, seed = [], 1
        for name, deck in decks.items():
            for opp in group2.FIELD:
                jobs.append((name, deck, opp, list(range(seed, seed + n)))); seed += n
        tot = {}
        with Pool(2) as pool:
            for name, opp, w, g in pool.imap_unordered(group2._job, jobs):
                t = tot.setdefault(name, [0, 0]); t[0] += w; t[1] += g
        print(f"{vname}:")
        for k, (w, g) in tot.items():
            print(f"    {k:40} {100*w/g:4.0f}%")
        sys.stdout.flush()

if __name__ == "__main__":
    run(int(sys.argv[1]))
