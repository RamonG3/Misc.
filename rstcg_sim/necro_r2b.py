# NOTE (2026-11-27): this simulation ran on the engine *before* Necromancy was built (commit 7846dd4); it adds the
# Necromancy rules on top of that engine itself, so on the built engine it would apply them twice. To rerun it:
#     git worktree add /tmp/before 7846dd4 && cp necro.py necro_r2b.py /tmp/before/rstcg_sim/
# The built engine and bot are measured by web/test/parity_necro.py instead.
"""Necromancy simulation, round 2b (2026-11-27): the Necrotic starter and the Necromancy bot decks.

Simulation only, like necro.py (which it builds on): round 2a's adopted numbers are necro.py's defaults.
    python necro_r2b.py <part> [n]
"""
import sys
from multiprocessing import Pool

import necro as N
from necro import CAPES, D, FIELD, game, install, rate, swap

FIELD_CAPE = {d["name"]: (d["capes"] or [None])[0] for d in N.DATA["decks"]}


def _vs_capes(args):
    """Both sides bring a cape (their deck's first), as in the Arena: win rate of `deck`."""
    deck, cape, opp, n, base, changes = args
    install(changes)
    od, oc = (D[opp], FIELD_CAPE[opp]) if isinstance(opp, str) else opp
    w = 0
    for s in range(n):
        for seat in (0, 1):
            ds, cs = [None, None], [None, None]
            ds[seat], ds[1 - seat] = deck, od
            cs[seat], cs[1 - seat] = cape, oc
            g = game(ds, base + s, {"capes": cs})
            w += g.winner == seat
    return w / (2 * n)


def rate_capes(pool, deck, cape, n, opps=None, changes=None, base=700_000):
    opps = opps or FIELD
    rs = pool.map(_vs_capes, [(deck, cape, o, n, base, changes) for o in opps])
    return 100 * sum(rs) / len(rs), dict(zip([o if isinstance(o, str) else "?" for o in opps], (100 * r for r in rs)))


def size(d):
    return sum(d.values())


# ---------------------------------------------------------------- the candidates
# The Necrotic starter, shaped like the Air Mage starter (60 cards, a little of everything, tier 1-2 only):
# its staves become more spells, its Wizard Robes the Deathwarden Robe, its Wizards' Tower the City of Um.
NS1 = {"Spirit Rune": 13, "Bone Rune": 5, "Flesh Rune": 3,
       "Touch of Death": 4, "Finger of Death": 4, "Spectral Scythe": 4, "Rasial's Greed": 2,
       "Rune Pouch": 3, "Deathwarden Robe": 2, "Bone Shield": 2, "Wooden Shield": 2, "Kiteshield": 2,
       "Shrimp": 1, "Lobster": 1, "Decay": 2, "Accelerated Decay": 2, "Mirror Shield": 2, "Protect from Melee": 1,
       "Archmage Sedridor": 2, "Banker": 1, "City of Um": 1, "Rune Mysteries": 1}
NS1_WIZ = swap(NS1, {"Deathwarden Robe": 2}, {"Wizard Robe": 2})
# ...with two Ghosts for the Necromancy Cape (round 1 found summons worth a blank in a spell deck; the cape may change that)
NS1_GHOSTS = swap(NS1, {"Mirror Shield": 2, "Banker": 1, "Rune Mysteries": 1, "Bone Rune": 1, "Spirit Rune": 2},
                  {"Ectoplasm": 3, "Vengeful Ghost": 2, "Death Rune": 2})

# Tournament-style (lean) Necromancy decks for the bots and the deck picker
T_NECROTIC = {"Spirit Rune": 14, "Bone Rune": 5, "Flesh Rune": 3,
              "Touch of Death": 4, "Finger of Death": 4, "Spectral Scythe": 4, "Rasial's Greed": 3,
              "Rune Pouch": 4, "Master Deathdealer Robe": 2, "Bone Shield": 3, "Kiteshield": 3, "Wooden Shield": 2,
              "Archmage Sedridor": 2, "City of Um": 2, "Accelerated Decay": 3, "Mirror Shield": 2}
T_MELEE = swap(D["Lean Melee"], {"Bronze Dagger": 2, "Stab": 2, "Bronze Scimitar": 2, "Mithril Sword": 1, "Bronze Bar": 1},
               {"Ectoplasm": 4, "Skeleton Warrior": 3, "Skull Lantern": 1})
T_RANGED = swap(D["Lean Ranged"], {"Bronze Arrows": 2, "Longrange": 2, "Kiteshield": 1, "Lowe": 1, "Normal Logs": 1,
                                   "Wooden Shield": 1},
                {"Ectoplasm": 4, "Putrid Zombie": 3, "Skull Lantern": 1})
T_MAGE = swap(D["Lean Mage"], {"Air Strike": 2, "Rune Pouch": 1, "Wooden Shield": 1, "Banker": 1, "Air Rune": 1},
              {"Ectoplasm": 3, "Vengeful Ghost": 2, "Skull Lantern": 1})
# A summon deck proper: Skeletons and Zombies on bars and logs, like Melee/Ranged+
T_SUMMONER = {"Bronze Bar": 6, "Mithril Bar": 4, "Normal Logs": 6, "Willow Logs": 2, "Ectoplasm": 6,
              "Skeleton Warrior": 4, "Putrid Zombie": 4, "Phantom Guardian": 2,
              "Mithril Scimitar": 2, "Willow Shortbow": 2, "Mithril Arrows": 3, "Rapid": 3, "Slash": 2,
              "Skull Lantern": 2, "Necromancy Ritual": 2, "Kiteshield": 3, "Bronze Platebody": 2, "Lobster": 2,
              "Burst of Strength": 2, "Parry": 1}

CANDS = {
    "Necrotic starter (Deathwarden Robes)": (NS1, "Runecrafting Cape"),
    "Necrotic starter, Wizard Robes instead": (NS1_WIZ, "Runecrafting Cape"),
    "Necrotic starter + 2 Ghosts": (NS1_GHOSTS, "Necromancy Cape"),
    "Necrotic (tournament)": (T_NECROTIC, "Runecrafting Cape"),
    "Lean Melee + Skeletons": (T_MELEE, "Necromancy Cape"),
    "Lean Ranged + Zombies": (T_RANGED, "Necromancy Cape"),
    "Lean Mage + Ghosts": (T_MAGE, "Necromancy Cape"),
    "Summoner": (T_SUMMONER, "Necromancy Cape"),
}


def part_field(pool, n):
    print(f"-- The field as it stands (each against the other 11), n={n}; no capes, then both sides with their first cape")
    for f in FIELD:
        others = [o for o in FIELD if o != f]
        a, _ = rate(pool, D[f], n, opps=others)
        b, _ = rate_capes(pool, D[f], FIELD_CAPE[f], n, opps=others)
        print(f"{f:40s} {a:5.1f}%   with capes {b:5.1f}%   ({FIELD_CAPE[f]})", flush=True)


def part_cands(pool, n, names=None):
    print(f"-- The candidates against the field, n={n}; no capes, then both sides with a cape")
    for name, (d, cape) in CANDS.items():
        if names and name not in names:
            continue
        assert size(d) == 60, (name, size(d))
        a, per = rate(pool, d, n)
        b, _ = rate_capes(pool, d, cape, n)
        worst = sorted(per.items(), key=lambda kv: kv[1])
        print(f"{name:40s} {a:5.1f}%   with {cape}: {b:5.1f}%   worst {worst[0][0]} {worst[0][1]:.0f}, "
              f"best {worst[-1][0]} {worst[-1][1]:.0f}", flush=True)


# ---------------------------------------------------------------- round 2: tuning
PURE_DW = swap(N.PURE, {"Wizard Robe": 2}, {"Deathwarden Robe": 2})
NS_B = swap(NS1, {"Mirror Shield": 2}, {"Touch of Death": 1, "Rasial's Greed": 1})
NS_C = swap(NS1, {"Banker": 1, "Rune Mysteries": 1}, {"Bone Shield": 1, "Lobster": 1})
NS_D = swap(NS1, {"Mirror Shield": 1, "Banker": 1, "Rune Mysteries": 1, "Decay": 1},
            {"Touch of Death": 1, "Rasial's Greed": 1, "Bone Shield": 1, "Lobster": 1})
NECROTIC_CAPES = ("Runecrafting Cape", "Prayer Cape", "Defence Cape", "Cooking Cape")
NS_E = swap(NS_D, {"Protect from Melee": 1}, {"Protect from Magic": 1})
NS_F = swap(NS_D, {"Decay": 1, "Spirit Rune": 1}, {"Mirror Shield": 1, "Protect from Magic": 1})
NS_G = swap(NS_D, {"Decay": 1}, {"Accelerated Decay": 1})
T2 = swap(T_NECROTIC, {"Wooden Shield": 1}, {"Protect from Magic": 1})
GRID2 = [
    ("Starter D", NS_D, ("Runecrafting Cape", "Prayer Cape", "Necromancy Cape (either)")),
    ("Starter E: Protect from Magic for Protect from Melee", NS_E, ("Runecrafting Cape", "Prayer Cape")),
    ("Starter F: + Mirror Shield, Protect from Magic", NS_F, ("Runecrafting Cape", "Prayer Cape")),
    ("Starter G: 3 Accelerated Decay, no Decay", NS_G, ("Runecrafting Cape", "Prayer Cape")),
    ("Necrotic (tournament)", T_NECROTIC, ("Runecrafting Cape", "Prayer Cape", "Necromancy Cape (either)")),
    ("Necrotic (tournament) + Protect from Magic", T2, ("Runecrafting Cape", "Prayer Cape")),
]
MAGIC_NUMS = N.spells(6, 10, 13, 17)
GRID3 = [
    ("A. Starter D, the cape counting either kind of hit", NS_D, (("Necromancy Cape (either)", 3), ("Necromancy Cape (either)", 4))),
    ("A. Necrotic (tournament), either kind", T_NECROTIC, (("Necromancy Cape (either)", 3), ("Necromancy Cape (either)", 4))),
    ("B. Starter D, spells at 6/10/13", NS_D, ("Runecrafting Cape", "Prayer Cape"), MAGIC_NUMS),
    ("B. Necrotic (tournament), spells at 6/10/13", T_NECROTIC, ("Runecrafting Cape", "Prayer Cape"), MAGIC_NUMS),
]
GRID4 = [
    ("Starter: NS1 (Air Mage's shape)", NS1, ("Necromancy Cape",)),
    ("Starter C", NS_C, ("Necromancy Cape",)),
    ("Starter D", NS_D, ("Necromancy Cape",)),
    ("Necrotic (tournament)", T_NECROTIC, ("Necromancy Cape", "Prayer Cape")),
]
S_X = swap(NS_C, {"Mirror Shield": 1}, {"Touch of Death": 1})
S_Y = swap(NS_C, {"Decay": 1}, {"Rasial's Greed": 1})
GRID5 = [
    ("Starter C", NS_C, ("Necromancy Cape",)),
    ("Starter X: C, a Mirror Shield -> Touch of Death", S_X, ("Necromancy Cape",)),
    ("Starter Y: C, a Decay -> Rasial's Greed", S_Y, ("Necromancy Cape",)),
    ("Starter D", NS_D, ("Necromancy Cape",)),
]
GRID = [
    ("Necrotic (round 2a's test deck), Deathwarden Robes", PURE_DW, NECROTIC_CAPES),
    ("Starter B: Mirror Shields -> Touch, Greed", NS_B, NECROTIC_CAPES),
    ("Starter C: Banker, Rune Mysteries -> Bone Shield, Lobster", NS_C, NECROTIC_CAPES),
    ("Starter D: both halves", NS_D, NECROTIC_CAPES),
    ("Necrotic (tournament)", T_NECROTIC, NECROTIC_CAPES),
    ("Lean Melee + Skeletons", T_MELEE, ("Necromancy Cape", "Attack Cape", "Smithing Cape")),
    ("Lean Ranged + Zombies", T_RANGED, ("Necromancy Cape", "Ranged Cape", "Woodcutting Cape")),
    ("Lean Mage + Ghosts", T_MAGE, ("Necromancy Cape", "Magic Cape", "Runecrafting Cape")),
]


def part_grid(pool, n, rows):
    print(f"-- Each deck against the field (n={n}): no capes, then with each cape it might bring (the field brings its own)")
    for row in rows:
        label, d, capes = row[:3]
        ch = row[3] if len(row) > 3 else None
        assert size(d) == 60, (label, size(d))
        a, _ = rate(pool, d, n, changes=ch)
        line = f"{label:58s} {a:5.1f}% |"
        for c in capes:
            need = None
            if isinstance(c, tuple):
                c, need = c
            b, _ = rate_capes(pool, d, c, n, changes={**(ch or {}), **({"_need": (c, need)} if need else {})})
            line += f" {c.replace(' Cape', '')}{'@' + str(need) if need else ''} {b:4.1f}"
        print(line, flush=True)


BOT_CAPES = {"Lean Melee + Skeletons": ("Necromancy Cape", "Attack Cape"),
             "Lean Ranged + Zombies": ("Necromancy Cape", "Ranged Cape"),
             "Lean Mage + Ghosts": ("Necromancy Cape", "Magic Cape")}
BOT_DECKS = {"Lean Melee + Skeletons": T_MELEE, "Lean Ranged + Zombies": T_RANGED, "Lean Mage + Ghosts": T_MAGE}
T_MELEE_LEG = swap(T_MELEE, {"Lobster": 1}, {"Robes of the First Necromancer": 1})


def rate_bot(pool, d, capes, n, changes=None):
    """A bot deck brings one of its capes at random in the Arena: the average of its capes."""
    return sum(rate_capes(pool, d, c, n, changes=changes)[0] for c in capes) / len(capes)


def part_checks1(pool, n):
    print(f"-- 1. Value over blank in the summon bot decks, with capes (the deck's two, averaged; the field its own), n={n}")
    tests = [("Lean Melee + Skeletons", "Skeleton Warrior"), ("Lean Melee + Skeletons", "Skull Lantern"),
             ("Lean Melee + Skeletons", "Ectoplasm", 2), ("Lean Ranged + Zombies", "Putrid Zombie"),
             ("Lean Ranged + Zombies", "Skull Lantern"), ("Lean Mage + Ghosts", "Vengeful Ghost"),
             ("Lean Mage + Ghosts", "Skull Lantern")]
    base = {}
    for h in BOT_DECKS:
        base[h] = rate_bot(pool, BOT_DECKS[h], BOT_CAPES[h], n)
        print(f"{h:28s} {base[h]:5.1f}%", flush=True)
    for t in tests:
        h, card = t[:2]
        k = t[2] if len(t) > 2 else BOT_DECKS[h][card]
        r = rate_bot(pool, N.blank(BOT_DECKS[h], card, k), BOT_CAPES[h], n)
        print(f"   {card:22s} in {h:24s} ({k} blanked): {(base[h] - r) / k:+.1f} a copy", flush=True)
    print("-- 2. A Legendary deck: Lean Melee + Skeletons with the Robes of the First Necromancer for its Lobster")
    r = rate_bot(pool, T_MELEE_LEG, BOT_CAPES["Lean Melee + Skeletons"], n)
    print(f"   {r:5.1f}% (without: {base['Lean Melee + Skeletons']:.1f}%)", flush=True)
    print("-- 3. The old decks against the three summon bot decks (each brings its cape; the summon deck a random one of its two)")
    for f in FIELD:
        tot = 0
        for h, d in BOT_DECKS.items():
            for c in BOT_CAPES[h]:
                tot += 100 - rate_capes(pool, d, c, n, opps=[f])[0]
        print(f"   {f:16s} {tot / 6:5.1f}%", flush=True)


STARTER = S_X
NEC_DECKS = {"Necrotic starter": (STARTER, ("Necromancy Cape",)),
             "Necrotic (tournament)": (T_NECROTIC, ("Necromancy Cape", "Prayer Cape"))}


def part_checks2(pool, n):
    print(f"-- 5. Value over blank in the Necrotic decks, with capes (the field brings its own), n={n}")
    for h, (d, capes) in NEC_DECKS.items():
        base = rate_bot(pool, d, capes, n)
        print(f"{h:28s} {base:5.1f}%  (no capes: {rate(pool, d, n)[0]:.1f}%)", flush=True)
        cards = (["Touch of Death", "Finger of Death", "Spectral Scythe", "Rasial's Greed", "Bone Shield", "Deathwarden Robe",
                  "Decay", "Accelerated Decay", "City of Um", ("Spirit Rune", 2), ("Bone Rune", 2)]
                 if h == "Necrotic starter" else ["Master Deathdealer Robe", "Rasial's Greed", "City of Um"])
        for c in cards:
            c, k = c if isinstance(c, tuple) else (c, d[c])
            r = rate_bot(pool, N.blank(d, c, k), capes, n)
            print(f"   {c:24s} ({k} blanked): {(base - r) / k:+.1f} a copy", flush=True)
    print("-- 6. Rasial's Greed against the summon decks: the tournament Necrotic deck with and without its 3 Greeds")
    d, capes = NEC_DECKS["Necrotic (tournament)"]
    for h, sd in BOT_DECKS.items():
        opp = [(sd, c) for c in BOT_CAPES[h]]
        a = sum(rate_capes(pool, d, c, n, opps=opp)[0] for c in capes) / len(capes)
        b = sum(rate_capes(pool, N.blank(d, "Rasial's Greed"), c, n, opps=opp)[0] for c in capes) / len(capes)
        print(f"   against {h:24s} {a:5.1f}% with, {b:5.1f}% without: {(a - b) / 3:+.1f} a copy", flush=True)
    print("-- 7. Protect from Necromancy in the starters (one, for a Lobster), with capes")
    for st in ("Melee", "Ranged", "Air Mage"):
        d0 = D[st]
        d1 = swap(d0, {"Lobster": 1}, {"Protect from Necromancy": 1})
        c = FIELD_CAPE[st]
        line = f"   {st:9s}"
        for h, (nd, ncapes) in NEC_DECKS.items():
            opp = [(nd, x) for x in ncapes]
            a = rate_capes(pool, d0, c, 2 * n, opps=opp)[0]
            b = rate_capes(pool, d1, c, 2 * n, opps=opp)[0]
            line += f" | vs {h}: {a:4.1f} -> {b:4.1f}"
        others = [o for o in FIELD if o != st]
        a = rate_capes(pool, d0, c, n, opps=others)[0]
        b = rate_capes(pool, d1, c, n, opps=others)[0]
        print(line + f" | vs the field: {a:4.1f} -> {b:4.1f}", flush=True)
    print("-- 8. The old decks against the two Necrotic decks (each brings its cape)")
    for f in FIELD:
        rs = []
        for h, (nd, ncapes) in NEC_DECKS.items():
            rs.append(sum(100 - rate_capes(pool, nd, x, n, opps=[f])[0] for x in ncapes) / len(ncapes))
        print(f"   {f:16s} vs the starter {rs[0]:5.1f}%, vs the tournament deck {rs[1]:5.1f}%", flush=True)


def demon_slayer(n=150):
    """Demon Slayer in a summon deck: the reward never picks a summon, and what leaving them out costs."""
    d = swap(T_MELEE, {"Lobster": 1}, {"Demon Slayer": 1})
    picked = {"summon": 0, "other": 0}
    orig = N.NecroGame._equip
    def spy(self, i, name, *a, **k):
        import traceback
        if any("quest_reward" in fr.name for fr in traceback.extract_stack()):
            picked["summon" if name in N.SUMMONS else "other"] += 1
        return orig(self, i, name, *a, **k)
    N.NecroGame._equip = spy
    w = 0
    for s in range(n):
        g = game([d, D[FIELD[s % 12]]], 900_000 + s)
        w += g.winner == 0
    N.NecroGame._equip = orig
    print(f"-- 4. Demon Slayer in Lean Melee + Skeletons, {n} games: the reward equipped {picked['other']} weapons, "
          f"{picked['summon']} summons")


if __name__ == "__main__":
    part = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    with Pool() as pool:
        if part == "field":
            part_field(pool, n)
        elif part == "cands":
            part_cands(pool, n)
        elif part == "grid":
            part_grid(pool, n, GRID)
        elif part == "grid3":
            part_grid(pool, n, GRID3)
        elif part == "checks1":
            part_checks1(pool, n)
        elif part == "checks2":
            part_checks2(pool, n)
        elif part == "ds":
            demon_slayer()
        elif part == "grid4":
            part_grid(pool, n, GRID4)
        elif part == "grid5":
            part_grid(pool, n, GRID5)
        elif part == "grid2":
            part_grid(pool, n, GRID2)
