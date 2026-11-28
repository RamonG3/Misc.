# NOTE (2026-11-27): this simulation ran on the engine *before* Necromancy was built (commit 7846dd4); it adds the
# Necromancy rules on top of that engine itself, so on the built engine it would apply them twice. To rerun it:
#     git worktree add /tmp/before 7846dd4 && cp necro.py necro_r2b.py /tmp/before/rstcg_sim/
# The built engine and bot are measured by web/test/parity_necro.py instead.
"""Necromancy (2026-11-27): the design in NECROMANCY_DESIGN.md (Project doc), simulated before anything is built.

SIMULATION ONLY. engine.py, cards.py and the web engine are untouched: the new cards are added to CARDS in memory
when this module is imported, and the new rules live in NecroGame (a Game subclass) and NecroBot (a HeuristicBot
subclass). Once Ramon settles the numbers, the rules go into both engines for real, with parity checks.

The rules simulated (Ramon's decisions, Claude's draft numbers):
- Ectoplasm: a material family of its own. The necrotic runes (Spirit, Bone, Flesh, Miasma) are runes.
- Summons are weapons (Weapon slot) marked as summons. They need Ectoplasm plus their style's material, deal
  their style's damage, and take no ability cards (weapon class "summon"). The Putrid Zombie is thrown (Rapid works).
  The Vengeful Ghost heals 2 with every swing; the Phantom Guardian is also a shield (soak 6, like the Shieldbow).
- Skull Lantern (conduit, an action): a summon from your discard pile straight into your Weapon slot.
- Necromancy Ritual (an action): from your deck, an Ectoplasm into play or a summon into your hand.
- Necrotic damage: the necrotic spells and Rasial's Greed deal it. No armour, reversal or Protect but Protect from
  Necromancy touches it; shields soak it. Decay (+1) and Accelerated Decay (+3, 3 turns) add to it.
- City of Um: Necrotic +2 (never a Punch), and a summon coming into play gets +1 durability.
- Necromancy Cape: 3 Necrotic hits. Demon Slayer never fetches a summon.

    python necro.py smoke                 # a few games of each Necromancy deck, with a rules self-check
    python necro.py field 150             # each Necromancy deck against the starter + tournament field
    python necro.py vob 150               # value over blank, every new card
    python necro.py <experiment> 150      # see EXPERIMENTS at the bottom
"""
import dataclasses
import json
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool

import cards
from bot import FUTURE, HeuristicBot
from capes import CAPES
from cards import CARDS, Card
from engine import NO_BUFF_SRC, Game

NEC = "necrotic"
TRIANGLE = ("melee", "ranged", "magic")


# ---------------------------------------------------------------- the cards (round 1 numbers, adopted by Ramon 2026-11-27)
def summon(name, kind, style, req, fam_req=(), **kw):
    styles = () if kind == "thrown" else (style,)
    return Card(name, kind, style, req=req, fam_req=fam_req, wclass="summon", styles=styles, **kw)


NEW = [
    Card("Ectoplasm", "material", mat="ectoplasm"),
    Card("Spirit Rune", "material", mat="rune"),
    Card("Bone Rune", "material", mat="rune"),
    Card("Flesh Rune", "material", mat="rune"),
    Card("Miasma Rune", "material", mat="rune"),
    summon("Putrid Zombie", "thrown", "ranged", (("Ectoplasm", 1),), (("log", 1),), hit=8, durability=2),
    summon("Skeleton Warrior", "weapon", "melee", (("Ectoplasm", 1),), (("bar", 1),), hit=7, durability=3),
    summon("Vengeful Ghost", "weapon", "magic", (("Ectoplasm", 1), ("Death Rune", 1)), hit=6, durability=3, heals=2),
    summon("Phantom Guardian", "weapon", "melee", (("Ectoplasm", 1), ("Mithril Bar", 2)), hit=7, durability=3,
           two_handed=True, soak=6),
    Card("Skull Lantern", "conduit", NEC),
    Card("Necromancy Ritual", "ritual", NEC),
    Card("Touch of Death", "spell", NEC, req=(("Spirit Rune", 1),), damage=6),      # 6: round 2a (Ramon, 2026-11-27)
    Card("Finger of Death", "spell", NEC, req=(("Bone Rune", 1), ("Spirit Rune", 2)), damage=9),
    Card("Spectral Scythe", "spell", NEC, req=(("Flesh Rune", 1), ("Spirit Rune", 3)), damage=12),
    Card("Death Skulls", "spell", NEC, req=(("Miasma Rune", 1), ("Spirit Rune", 4)), damage=16),
    Card("Rasial's Greed", "destroy", NEC, req=(("Spirit Rune", 2),), destroys="ectoplasm", damage=6),
    Card("Bone Shield", "shield", NEC, req=(("Bone Rune", 1), ("Spirit Rune", 1)), soak=7, free=True),
    Card("Decay", "buff", NEC, prayer=NEC, bonus=1),
    Card("Accelerated Decay", "buff", NEC, prayer=NEC, bonus=4, duration=3),
    Card("Protect from Necromancy", "prayer", prayer=NEC),
    Card("City of Um", "location"),
    # The armour (Ramon, 2026-11-27): Necrotic is the robes' specialty, Spirit Runes the cost; the Legendary's
    # special is "your summons hit +2" (SUMMON_BONUS), its cost Ectoplasm (Claude's draft, 2)
    Card("Deathwarden Robe", "armor", NEC, req=(("Spirit Rune", 1),),
         armor=(("melee", -1), ("ranged", -1), ("magic", -1), (NEC, -2))),
    Card("Adept Deathwarden Robe", "armor", NEC, req=(("Spirit Rune", 2),),
         armor=(("melee", -1), ("ranged", -1), ("magic", -1), (NEC, -3))),
    Card("Master Deathwarden Robe", "armor", NEC, req=(("Spirit Rune", 3),),
         armor=(("melee", -1), ("ranged", -1), ("magic", -1), (NEC, -4))),
    Card("Master Deathdealer Robe", "armor", NEC, req=(("Spirit Rune", 3),), bonus=2,
         armor=(("melee", -1), ("ranged", -1), ("magic", -1), (NEC, -2))),
    Card("Robes of the First Necromancer", "armor", NEC, req=(("Ectoplasm", 1),), legendary=True,
         armor=(("melee", -2), ("ranged", -2), ("magic", -2), (NEC, -4))),
]
SUMMON_BONUS = {"Robes of the First Necromancer": 2}     # your summons' swings and throws +2
SPELL_SRC = {"spell"}
SUMMONS = {c.name for c in NEW if c.wclass == "summon"}
CARDS["Blank"] = Card("Blank", "spell", req=(("Blank Rune", 99),))     # can never be played (value over blank)
CAPES["Necromancy Cape"] = ("event", "necro_hit", 3)    # Ramon, 2026-11-27 (round 2b): 3 hits by summons or Necrotic attacks
CAPES["Necromancy Cape (summons only)"] = ("event", "summon_hit", 3)   # round 2a's version
CAPES["Necromancy Cape (either)"] = ("event", "necro_hit", 3)          # the name round 2b tested it under
CAPES["Necromancy Cape (arrivals)"] = ("event", "summon", 2)   # the version tested beside it: summons coming into play
BASE_NEED = {k: v[2] for k, v in CAPES.items() if k.startswith("Necromancy Cape")}
# Every Set 1 armour but White Knight Armor and Cabbage Armor gets Necrotic -1 (Ramon, 2026-11-27)
ARMOR_NEC = {n: c for n, c in CARDS.items() if c.kind == "armor" and n not in ("White Knight Armor", "Cabbage Armor")}


def install(changes=None):
    """Put the Necromancy cards into CARDS, with any {card: {field: value}} changes (experiments).
    changes["_armor_nec"]: the Necrotic number on the Set 1 armour (default -1; 0 = round 1's "goes through")."""
    nec = (changes or {}).get("_armor_nec", -1)
    for cn, (kind, what, need) in list(CAPES.items()):
        if cn.startswith("Necromancy Cape") and need != BASE_NEED[cn]:
            CAPES[cn] = (kind, what, BASE_NEED[cn])
    if (changes or {}).get("_need"):
        cn, k = changes["_need"]
        CAPES[cn] = CAPES[cn][:2] + (k,)
    for n, c in ARMOR_NEC.items():
        CARDS[n] = dataclasses.replace(c, armor=c.armor + ((NEC, nec),)) if nec else c
    for c in NEW:
        CARDS[c.name] = dataclasses.replace(c, **(changes or {}).get(c.name, {}))
    SUMMON_BONUS["Robes of the First Necromancer"] = 0 if (changes or {}).get("_nobonus") else 2


install()


# ---------------------------------------------------------------- the rules
class NecroGame(Game):
    """Game plus the Necromancy rules. Nothing here changes how any Set 1 card plays (checked by `smoke`)."""

    def compute_damage(self, i, amount, style, ignore_armor=False, ignore_prayer=False, src=None,
                       ignore_shield=False, punch=False):
        if style == NEC and self.location == "City of Um" and not (punch or src == "punch"):
            amount += 2
        return super().compute_damage(i, amount, style, ignore_armor, ignore_prayer, src, ignore_shield, punch)

    def deal(self, i, amount, style, src, **kw):
        """Counts a summon's hit for the Necromancy Cape (hits): one that reaches the deck, as the style capes count."""
        p, o = self.players[i], self.opp(i)
        by_summon = (src not in NO_BUFF_SRC and src not in SPELL_SRC and p.weapon and p.weapon["name"] in SUMMONS)
        before = o.lp
        r = super().deal(i, amount, style, src, **kw)
        if by_summon and o.lp < before:
            p.cape_prog["summon_hit"] += 1
        if (by_summon or style == NEC) and o.lp < before:
            p.cape_prog["necro_hit"] += 1                       # experiment: "Necromancy hits" of either kind
        return r

    def attack_bonus(self, i, style, src):
        b = super().attack_bonus(i, style, src)
        p = self.players[i]
        if (p.armor in SUMMON_BONUS and src not in NO_BUFF_SRC and src not in SPELL_SRC
                and p.weapon and p.weapon["name"] in SUMMONS):
            b += SUMMON_BONUS[p.armor]
        return b

    @staticmethod
    def summon_ok(p, c):
        return p.meets(c.req) and not any(p.family_count(f) < n for f, n in c.fam_req)

    def _card_actions(self, i, c, no_damage):
        p = self.players[i]
        if c.kind == "conduit":
            return [("play", c.name, t) for t in sorted(set(p.discard)) if t in SUMMONS and self.summon_ok(p, CARDS[t])]
        if c.kind == "ritual":
            return [("play", c.name, t) for t in sorted(set(p.deck)) if t == "Ectoplasm" or t in SUMMONS]
        return super()._card_actions(i, c, no_damage)

    def _apply_card(self, i, name, target):
        p, o = self.players[i], self.opp(i)
        c = CARDS[name]
        if c.kind == "spell" and c.style == NEC:
            self.stats[i]["played_spell"] += 1
            self.deal(i, c.damage, NEC, "spell")
            if c.heals and self.winner is None:
                p.lp = min(self.rules["max_lp"], p.lp + c.heals)
            p.discard.append(name)
            return
        if c.kind == "destroy" and c.style == NEC:
            self.stats[i]["played_destroy"] += 1
            self.deal(i, c.damage, NEC, "spell")
            if target is None or not o.materials.get(target):
                ts = [t for t in o.materials if o.materials[t] > 0 and CARDS[t].mat == c.destroys]
                target = max(ts, key=lambda t: o.materials[t]) if ts else None
            if target:
                self._lose_material(o, target)
                self.stats[i]["destroyed"] += 1
            p.discard.append(name)
            return
        if c.kind == "conduit":
            self.stats[i]["played_conduit"] += 1
            p.discard.remove(target)
            p.discard.append(name)
            self._apply_card(i, target, None)      # straight into the Weapon slot
            self.stats[i]["raised"] += 1
            return
        if c.kind == "ritual":
            self.stats[i]["played_ritual"] += 1
            p.deck.remove(target)
            if CARDS[target].kind == "material":
                p.materials[target] += 1
            else:
                p.hand.append(target)
            self.rng.shuffle(p.deck)
            p.discard.append(name)
            return
        super()._apply_card(i, name, target)
        if name in SUMMONS and p.weapon and p.weapon["name"] == name:     # a summon arrives
            self.stats[i]["summoned"] += 1
            p.cape_prog["summon"] += 1                                  # the Necromancy Cape
            if c.soak and c.kind == "weapon":                           # the Phantom Guardian is a shield too
                p.shield = {"name": name, "soak": c.soak, "virtual": True}
            if self.location == "City of Um":
                p.weapon["dur"] += 1

    def do_swing(self, i, style):
        p = self.players[i]
        w = p.weapon_card()
        super().do_swing(i, style)
        if w and w.name in SUMMONS and w.heals and self.winner is None:   # the Vengeful Ghost
            gain = min(w.heals, self.rules["max_lp"] - p.lp)
            p.lp += gain
            self.stats[i]["healed"] += max(0, gain)

    def quest_reward(self, i, c):
        if c.effect != "silverlight":
            return super().quest_reward(i, c)
        p = self.players[i]                       # Demon Slayer: any weapon but a summon (the bots pick the strongest)
        ws = [n for n in p.deck if CARDS[n].kind in ("weapon", "thrown", "bow", "staff") and n not in SUMMONS]
        if ws:
            best = max(ws, key=lambda n: CARDS[n].hit * max(1, CARDS[n].durability))
            p.deck.remove(best)
            p.hand.append(best)
            self._equip(i, best)
            self.rng.shuffle(p.deck)


# ---------------------------------------------------------------- the bot
def deck_style(decklist):
    """bot.deck_style, plus Necrotic."""
    tally = Counter()
    for name, n in decklist.items():
        c = CARDS[name]
        if c.style in TRIANGLE + (NEC,) and c.kind not in ("armor", "conduit", "ritual"):
            tally[c.style] += n
    return max(tally, key=tally.get)


class NecroBot(HeuristicBot):
    def setup(self, game):
        # HeuristicBot.setup, with this module's deck_style (bot.deck_style fails on a deck with no Melee, Ranged
        # or Magic card: the same fix goes into bot.py and bot.js at the build)
        from bot import deck_needs
        self.need = [deck_needs(p.decklist) for p in game.players]
        self.capes = [any(CARDS[n].kind == "cape" for n in p.decklist)
                      or "Quest Point Cape" in getattr(game, "cape_table", []) for p in game.players]
        self.buffs = [any(CARDS[n].kind == "buff" for n in p.decklist) for p in game.players]
        self.style = [deck_style(p.decklist) for p in game.players]
        self.styles = [{CARDS[n].style for n in p.decklist
                        if CARDS[n].kind in ("weapon", "thrown", "bow", "staff", "spell", "ability", "destroy")
                        and CARDS[n].style in TRIANGLE + (NEC,)} for p in game.players]

    def attacks_per_turn(self, g, i, style):
        if style == "any":
            return 1.6
        if style not in self.styles[i]:
            return 0.0
        return 1.6 / len(self.styles[i])

    def incoming(self, g, i):
        o = g.opp(i)
        w = o.weapon_card()
        if w and w.kind == "weapon":
            st = (w.styles or ("melee",))[0]
            return w.hit + (2 if st == "melee" else 0), st
        if self.style[1 - i] == NEC and o.family_count("rune") >= 1 and not (w and w.kind in ("thrown", "bow")):
            return 8, NEC
        return super().incoming(g, i)

    def cape_events(self, g, i, a):
        if a[0] == "play" and (a[1] in SUMMONS or CARDS[a[1]].kind == "conduit"):
            return [("summon", 1)]
        if a[0] == "play" and CARDS[a[1]].style == NEC and CARDS[a[1]].kind in ("spell", "destroy") and CARDS[a[1]].damage:
            return [("necrotic_hit", 1), ("necro_hit", 1)]    # not the Magic Cape's magic_hit (the base bot's guess)
        p = g.players[i]
        if a[0] in ("swing", "free_swing") and p.weapon and p.weapon["name"] in SUMMONS:
            w = CARDS[p.weapon["name"]]
            return super().cape_events(g, i, a) + [("summon_hit", w.hits or 1), ("necro_hit", w.hits or 1)]
        return super().cape_events(g, i, a)

    def summon_score(self, g, i, c):
        """A summon going into the Weapon slot (from hand, or from the discard by the Skull Lantern)."""
        p = g.players[i]
        dur = c.durability + (1 if g.location == "City of Um" else 0)
        v = (c.hit + SUMMON_BONUS.get(p.armor, 0)) * dur * FUTURE + 1.0 - self.current_weapon_value(p)
        if c.heals:
            v += c.heals * dur * max(0.4, self.urgency(p))
        if c.soak:
            v += c.soak * 0.4 - (p.shield["soak"] * 0.3 if p.shield and not p.shield.get("virtual") else 0.0)
        return v

    def base_score(self, g, i, a):
        if a[0] != "play":
            return super().base_score(g, i, a)
        _, name, target = a
        c = CARDS[name]
        p, o = g.players[i], g.opp(i)
        if name in SUMMONS:
            return self.summon_score(g, i, c)
        if name in SUMMON_BONUS:               # the Legendary robes: worth its +2 on the share of attacks summons make
            share = min(1.0, sum(k for n, k in p.decklist.items() if n in SUMMONS) / 6)
            return super().base_score(g, i, a) + SUMMON_BONUS[name] * 1.6 * 0.7 * 3 * share
        if c.kind == "spell" and c.style == NEC:
            v = self.hit_value(g, i, c.damage, NEC)
            if c.heals:
                v += min(c.heals, g.rules["max_lp"] - p.lp) * self.urgency(p)
            return v
        if c.kind == "destroy" and c.style == NEC:
            v = 0.0
            if target is not None:
                v = 5.0 if o.count(target, real=True) <= self.need[1 - i].get(target, 0) else 1.0
            return v + self.hit_value(g, i, c.damage, NEC)
        if c.kind == "conduit":
            return self.summon_score(g, i, CARDS[target]) + 2.0      # and it didn't cost a card from hand
        if c.kind == "ritual":
            if target == "Ectoplasm":
                return 2.0 + 4.0 * self.unlocks(g, i, target) + (2.0 if p.count(target) < self.need[i].get(target, 0) else 0.0)
            t = CARDS[target]
            v = 2.0 + (2.0 if not p.weapon and not any(n in SUMMONS for n in p.hand) else 0.0)
            return v + (1.0 if NecroGame.summon_ok(p, t) else 0.0)
        return super().base_score(g, i, a)

    def location_value(self, g, i, name):
        if name != "City of Um":
            return super().location_value(g, i, name)
        def worth(j):
            q = g.players[j]
            nec = 3.0 if self.style[j] == NEC else (1.0 if NEC in self.styles[j] else 0.0)
            return nec + 0.12 * sum(n for c, n in q.decklist.items() if c in SUMMONS)
        return worth(i) - worth(1 - i)

    def keep_value(self, g, i, name):
        c = CARDS[name]
        if c.kind in ("conduit", "ritual"):
            return 3.5
        if c.kind == "destroy" and c.style == NEC:
            return 4.0
        return super().keep_value(g, i, name)


# ---------------------------------------------------------------- decks
DATA = json.load(open("web/cards.json"))
SIM = {"Varrock Teleport": "Varrock Teleport Tablet"}
D = {d["name"]: {SIM.get(k, k): v for k, v in d["cards"].items()} for d in DATA["decks"]}
FIELD = [d["name"] for d in DATA["decks"] if d["group"] in ("Starter decks", "Tournament decks")]


def swap(deck, out, add):
    d = dict(deck)
    for k, n in out.items():
        assert d.get(k, 0) >= n, (k, n, d.get(k))
        d[k] -= n
        if not d[k]:
            del d[k]
    for k, n in add.items():
        d[k] = d.get(k, 0) + n
    assert sum(d.values()) == 60, sum(d.values())
    return d


NECRO = {
    # The Melee starter with a Necromancy package: Skeleton Warriors on its bars, Phantom Guardians on its Mithril.
    "Melee + Necro": swap(D["Melee"],
                          {"Bronze Bar": 3, "Mithril Bar": 2, "Bronze Scimitar": 2, "Bronze Dagger": 2, "Stab": 2,
                           "Bronze Mace": 1, "Crush": 1},
                          {"Ectoplasm": 5, "Skeleton Warrior": 3, "Phantom Guardian": 2, "Skull Lantern": 1,
                           "Necromancy Ritual": 2}),
    # The Ranged starter with Putrid Zombies on its logs.
    "Ranged + Necro": swap(D["Ranged"],
                           {"Normal Logs": 3, "Willow Logs": 2, "Shortbow": 2, "Bronze Arrows": 2, "Longrange": 1,
                            "Sheep Shearer": 1, "Tinderbox": 1},
                           {"Ectoplasm": 5, "Putrid Zombie": 4, "Skull Lantern": 1, "Necromancy Ritual": 2}),
    # The Air Mage starter with Vengeful Ghosts on its Death Runes (in place of its staves).
    "Mage + Necro": swap(D["Air Mage"],
                         {"Air Rune": 2, "Iban's Staff": 2, "Staff of Air": 1, "Air Strike": 2, "Banker": 1,
                          "Rune Mysteries": 1, "Tinderbox": 1, "Shrimp": 1},
                         {"Ectoplasm": 5, "Vengeful Ghost": 3, "Skull Lantern": 1, "Necromancy Ritual": 2}),
    # A Necrotic-spell deck, built like the Air Mage (tier 1-2: no Miasma), with Ghosts for a weapon.
    "Necrotic": {
        "Spirit Rune": 12, "Bone Rune": 5, "Flesh Rune": 3, "Ectoplasm": 4, "Death Rune": 2,
        "Touch of Death": 4, "Finger of Death": 4, "Spectral Scythe": 3, "Rasial's Greed": 2,
        "Vengeful Ghost": 2, "Skull Lantern": 1, "Necromancy Ritual": 2,
        "Bone Shield": 3, "Wizard Robe": 2,
        "Decay": 2, "Accelerated Decay": 2, "Protect from Melee": 1,
        "Rune Pouch": 2, "Archmage Sedridor": 1, "City of Um": 1, "Lobster": 1, "Shrimp": 1,
    },
}
NECRO_HEAVY = NECRO
NECRO = {   # second builds (2026-11-27): a lighter splash that keeps every bar, log and rune the host had
    "Melee + Necro": swap(D["Melee"],
                          {"Bronze Scimitar": 3, "Bronze Dagger": 2, "Stab": 2, "Crush": 1, "Bronze Mace": 1},
                          {"Ectoplasm": 4, "Skeleton Warrior": 3, "Skull Lantern": 1, "Necromancy Ritual": 1}),
    "Ranged + Necro": swap(D["Ranged"],
                           {"Shortbow": 2, "Bronze Arrows": 2, "Longrange": 2, "Sheep Shearer": 1, "Tinderbox": 1},
                           {"Ectoplasm": 4, "Putrid Zombie": 3, "Skull Lantern": 1}),
    "Mage + Necro": swap(D["Air Mage"],
                         {"Staff of Air": 1, "Air Strike": 2, "Banker": 1, "Rune Mysteries": 1, "Tinderbox": 1, "Shrimp": 1},
                         {"Ectoplasm": 4, "Vengeful Ghost": 2, "Skull Lantern": 1}),
    "Necrotic": NECRO_HEAVY["Necrotic"],
    # The Melee starter with Phantom Guardians: 2 for its Kiteshields, Ectoplasm for Stabs and bronze.
    "Guardian Melee": swap(D["Melee"],
                           {"Kiteshield": 2, "Bronze Dagger": 2, "Stab": 2, "Crush": 1, "Bronze Mace": 1},
                           {"Ectoplasm": 5, "Phantom Guardian": 2, "Necromancy Ritual": 1}),
}
for k, v in {**NECRO, **NECRO_HEAVY}.items():
    assert sum(v.values()) == 60, (k, sum(v.values()))


# ---------------------------------------------------------------- running games
def game(decks, seed, rules=None):
    g = NecroGame(decks, [NecroBot(seed * 2 + 1), NecroBot(seed * 2 + 2)], seed=seed, rules=rules)
    g.run()
    return g


def _vs(args):
    """Win rate of `deck` against `opp` (a field deck's name, or a decklist), both seats, n seeds each.
    cape: the cape `deck` brings (the opponent brings none), for cape-zone tests."""
    deck, opp, n, base, changes, rules, cape = (args + (None,))[:7]
    install(changes)
    od = D[opp] if isinstance(opp, str) else opp
    w = 0
    for s in range(n):
        for seat in (0, 1):
            ds = [None, None]
            ds[seat], ds[1 - seat] = deck, od
            r = dict(rules or {})
            if cape:
                r["capes"] = [cape, None] if seat == 0 else [None, cape]
            w += game(ds, base + s, r).winner == seat
    return w / (2 * n)


def rate(pool, deck, n, base=700_000, changes=None, rules=None, opps=None, cape=None):
    """Average win rate (%) against the field (or `opps`: names or (label, decklist) pairs), and per opponent."""
    opps = opps or FIELD
    keys = [o if isinstance(o, str) else o[0] for o in opps]
    decks = [o if isinstance(o, str) else o[1] for o in opps]
    rs = pool.map(_vs, [(deck, o, n, base, changes, rules, cape) for o in decks])
    return 100 * sum(rs) / len(rs), dict(zip(keys, (100 * r for r in rs)))


def blank(deck, card, k=None):
    k = deck[card] if k is None else k
    return swap(deck, {card: k}, {"Blank": k})


# ---------------------------------------------------------------- commands
def smoke():
    """A self-check: Set 1 plays exactly as before under NecroGame/NecroBot (same moves, same winners), then a
    handful of Necromancy games with the new mechanics counted."""
    from bot import HeuristicBot as HB
    same = 0
    for s in range(60):
        a, b = FIELD[s % len(FIELD)], FIELD[(s * 5 + 3) % len(FIELD)]
        g1 = Game([D[a], D[b]], [HB(2 * s + 1), HB(2 * s + 2)], seed=s)
        g1.run()
        g2 = NecroGame([D[a], D[b]], [NecroBot(2 * s + 1), NecroBot(2 * s + 2)], seed=s)
        g2.run()
        same += (g1.winner, g1.turn, g1.players[0].lp, g1.players[1].lp) == (g2.winner, g2.turn, g2.players[0].lp, g2.players[1].lp)
    print(f"Set 1 decks, plain engine vs NecroGame: {same}/60 games identical")
    for name, deck in NECRO.items():
        st = Counter()
        wins = 0
        for s in range(40):
            g = game([deck, D[FIELD[s % len(FIELD)]]], 5000 + s)
            wins += g.winner == 0
            for k, v in g.stats[0].items():
                if k in ("summoned", "raised", "played_ritual", "played_conduit", "played_spell", "played_destroy",
                         "destroyed", "healed", "turns") or k.startswith("dmg_"):
                    st[k] += v
        print(f"{name:16s} {wins}/40 wins · per game: " + ", ".join(f"{k} {v / 40:.1f}" for k, v in sorted(st.items())))


def field(n):
    with Pool() as pool:
        rows = []
        for name in ("Melee", "Ranged", "Air Mage"):
            r, _ = rate(pool, D[name], n, opps=[o for o in FIELD if o != name])
            rows.append((name + " (starter, for scale)", r, None))
        for name, deck in NECRO.items():
            r, per = rate(pool, deck, n)
            rows.append((name, r, per))
        for name, r, per in rows:
            print(f"{name:34s} {r:5.1f}%")
        print()
        print("per opponent:  " + "  ".join(f"{o[:10]:>10s}" for o in FIELD))
        for name, r, per in rows:
            if per:
                print(f"{name:14s} " + "  ".join(f"{per[o]:10.0f}" for o in FIELD))


VOB = {   # card: host deck (value over blank: every copy of the card in its host replaced by a Blank)
    "Ectoplasm": "Melee + Necro", "Skeleton Warrior": "Melee + Necro", "Phantom Guardian": "Guardian Melee",
    "Skull Lantern": "Melee + Necro", "Necromancy Ritual": "Melee + Necro",
    "Putrid Zombie": "Ranged + Necro", "Vengeful Ghost": "Mage + Necro",
    "Touch of Death": "Necrotic", "Finger of Death": "Necrotic", "Spectral Scythe": "Necrotic",
    "Rasial's Greed": "Necrotic", "Bone Shield": "Necrotic", "Decay": "Necrotic", "Accelerated Decay": "Necrotic",
    "City of Um": "Necrotic", "Spirit Rune": "Necrotic", "Bone Rune": "Necrotic", "Flesh Rune": "Necrotic",
}
VOB_K = {"Ectoplasm": 2, "Spirit Rune": 2, "Bone Rune": 2, "Flesh Rune": 1}   # materials: their marginal value


def vob(n, only=None):
    with Pool() as pool:
        base = {name: rate(pool, deck, n)[0] for name, deck in NECRO.items()}
        print("hosts: " + ", ".join(f"{k} {v:.1f}%" for k, v in base.items()))
        print(f"{'card':22s} {'host':16s} {'copies':>6s} {'with':>6s} {'blank':>6s} {'value':>6s} {'/copy':>6s}")
        for card, host in VOB.items():
            if only and card not in only:
                continue
            k = VOB_K.get(card, NECRO[host][card])
            r, _ = rate(pool, blank(NECRO[host], card, k), n)
            d = base[host] - r
            print(f"{card:22s} {host:16s} {k:6d} {base[host]:6.1f} {r:6.1f} {d:+6.1f} {d / k:+6.1f}")


REF = {   # Set 1 cards measured the same way in their own decks, for scale (host plays the field without itself)
    "Air Mage": {"Air Strike": None, "Air Bolt": None, "Air Blast": None, "Iban's Staff": None, "Mystic Will": None,
                 "Augury": None, "Rune Pouch": None, "Kiteshield": None, "Wooden Shield": None, "Wizards' Tower": None,
                 "Archmage Sedridor": None, "Chaos Rune": 2, "Air Rune": 2},
    "Melee": {"Bronze Scimitar": None, "Mithril Scimitar": None, "Mithril Sword": None, "Bronze Dagger": None,
              "Slash": None, "Vanaka": None, "Burst of Strength": None, "Piety": None, "Kiteshield": None,
              "Gunnar's Crusade": None, "Bronze Bar": 2},
    "Ranged": {"Willow Shortbow": None, "Rapid": None, "Mithril Arrows": None, "Sharp Eye": None, "Rigour": None,
               "Tinderbox": None, "Normal Logs": 2},
    "Thrown Ranged": {"Bronze Throwing Axe": None, "Mithril Throwing Axe": None, "Mithril Knives": None},
}


PURE = {"Spirit Rune": 13, "Bone Rune": 5, "Flesh Rune": 3,
        "Touch of Death": 5, "Finger of Death": 4, "Spectral Scythe": 4, "Rasial's Greed": 3,
        "Bone Shield": 3, "Kiteshield": 2, "Wooden Shield": 2, "Wizard Robe": 2,
        "Decay": 2, "Accelerated Decay": 2, "Protect from Melee": 1,
        "Rune Pouch": 3, "Archmage Sedridor": 2, "City of Um": 1, "Lobster": 2, "Shrimp": 1}
CANDIDATE_R1 = {   # round 1's proposal (2026-11-27): adopted, now the defaults in NEW
    "Touch of Death": {"damage": 5}, "Finger of Death": {"damage": 9}, "Spectral Scythe": {"damage": 12},
    "Death Skulls": {"damage": 16},
    "Skeleton Warrior": {"hit": 7},
    "Vengeful Ghost": {"req": (("Ectoplasm", 1), ("Death Rune", 1))},
    "Phantom Guardian": {"req": (("Ectoplasm", 2), ("Mithril Bar", 2))},
}
HOSTS2 = {
    "Melee + Necro": swap(NECRO["Melee + Necro"], {"Burst of Strength": 1}, {"Phantom Guardian": 1}),
    "Ranged + Necro": NECRO["Ranged + Necro"],
    "Mage + Necro": swap(NECRO["Mage + Necro"], {"Lobster": 1}, {"Necromancy Ritual": 1}),
    "Necrotic": PURE,
}
VOB2 = {"Ectoplasm": "Melee + Necro", "Skeleton Warrior": "Melee + Necro", "Phantom Guardian": "Melee + Necro",
        "Skull Lantern": "Melee + Necro", "Necromancy Ritual": "Melee + Necro",
        "Putrid Zombie": "Ranged + Necro", "Vengeful Ghost": "Mage + Necro",
        "Touch of Death": "Necrotic", "Finger of Death": "Necrotic", "Spectral Scythe": "Necrotic",
        "Rasial's Greed": "Necrotic", "Bone Shield": "Necrotic", "Decay": "Necrotic", "Accelerated Decay": "Necrotic",
        "City of Um": "Necrotic", "Spirit Rune": "Necrotic", "Bone Rune": "Necrotic"}


def vob2(n, changes):
    with Pool() as pool:
        base = {h: rate(pool, d, n, changes=changes)[0] for h, d in HOSTS2.items()}
        print("hosts: " + ", ".join(f"{k} {v:.1f}%" for k, v in base.items()), flush=True)
        print(f"{'card':22s} {'host':16s} {'copies':>6s} {'with':>6s} {'blank':>6s} {'value':>6s} {'/copy':>6s}")
        for card, host in VOB2.items():
            k = VOB_K.get(card, HOSTS2[host][card])
            r, _ = rate(pool, blank(HOSTS2[host], card, k), n, changes=changes)
            d = base[host] - r
            print(f"{card:22s} {host:16s} {k:6d} {base[host]:6.1f} {r:6.1f} {d:+6.1f} {d / k:+6.1f}", flush=True)


def round2(n):
    with Pool() as pool:
        def show(label, deck, **kw):
            r, _ = rate(pool, deck, n, **kw)
            print(f"{label:62s} {r:5.1f}%", flush=True)
            return r
        AM = D["Air Mage"]
        print("-- tier 3: Death Skulls (16, Miasma + 4 Spirit) against Air Wave (17, Blood + 4 Air)")
        a = show("Air Mage", AM, opps=[o for o in FIELD if o != "Air Mage"])
        b = show("Air Mage, 2 Air Wave + 2 Blood Rune for 2 Air Blast + 2 Air Rune",
                 swap(AM, {"Air Blast": 2, "Air Rune": 2}, {"Air Wave": 2, "Blood Rune": 2}), opps=[o for o in FIELD if o != "Air Mage"])
        c = show("Necrotic", PURE)
        d = show("Necrotic, 2 Death Skulls + 2 Miasma for 2 Spectral Scythe + 2 Spirit",
                 swap(PURE, {"Spectral Scythe": 2, "Spirit Rune": 2}, {"Death Skulls": 2, "Miasma Rune": 2}))
        print(f"   gain: Air Wave {b - a:+.1f}, Death Skulls {d - c:+.1f}")
        print("-- Necromancy Ritual in each summon deck (one copy, against a blank)")
        for h, out in (("Melee + Necro", None), ("Ranged + Necro", "Grand Exchange"), ("Mage + Necro", None)):
            deck = HOSTS2[h]
            if "Necromancy Ritual" not in deck:
                deck = swap(deck, {out: 1}, {"Necromancy Ritual": 1})
            x = show(f"{h} with a Ritual", deck)
            y = show(f"   ... the Ritual blanked", blank(deck, "Necromancy Ritual"))
            print(f"   Ritual: {x - y:+.1f}")
        print("-- City of Um in the summon decks")
        for h, out in (("Melee + Necro", "Wilderness"), ("Ranged + Necro", "Grand Exchange"), ("Mage + Necro", "Wizards' Tower")):
            deck = HOSTS2[h]
            x = show(f"{h} (with its {out})", deck)
            y = show(f"   ... City of Um for its {out}", swap(deck, {out: 1}, {"City of Um": 1}))


def capes_test(n):
    """Cape zone: the deck brings the cape, the other side brings none (cape_zone.py's way)."""
    with Pool() as pool:
        def show(label, deck, cape, need=None, opps=None):
            rules = {"cape_need": {"Necromancy Cape": need}} if need else None
            r, _ = rate(pool, deck, n, cape=cape, rules=rules, opps=opps)
            print(f"{label:52s} {r:5.1f}%", flush=True)
        AMo = [o for o in FIELD if o != "Air Mage"]
        show("Air Mage, no cape", D["Air Mage"], None, opps=AMo)
        show("Air Mage, Magic Cape (3)", D["Air Mage"], "Magic Cape", opps=AMo)
        show("Air Mage, Runecrafting Cape (7)", D["Air Mage"], "Runecrafting Cape", opps=AMo)
        show("Necrotic, no cape", PURE, None)
        show("Necrotic, Magic Cape (counts nothing)", PURE, "Magic Cape")
        show("Necrotic, Runecrafting Cape (7)", PURE, "Runecrafting Cape")
        show("Necrotic, Prayer Cape (2)", PURE, "Prayer Cape")
        for k in (2, 3, 4):
            show(f"Necrotic, Necromancy Cape ({k})", PURE, "Necromancy Cape", need=k)


def pfn(n):
    """Protect from Necromancy: each starter with its Protect prayer swapped for it, against the Necrotic deck
    and against the field."""
    with Pool() as pool:
        for st, pr in (("Melee", "Protect from Magic"), ("Ranged", "Protect from Melee"), ("Air Mage", "Protect from Missiles")):
            k = D[st][pr]
            alt = swap(D[st], {pr: k}, {"Protect from Necromancy": k})
            opps = [o for o in FIELD if o != st]
            a, _ = rate(pool, PURE, n, opps=[(st, D[st])])
            b, _ = rate(pool, PURE, n, opps=[(st, alt)])
            c, _ = rate(pool, D[st], n, opps=opps)
            d, _ = rate(pool, alt, n, opps=opps)
            print(f"{st}: Necrotic beats it {a:.1f}% → {b:.1f}% with {k} Protect from Necromancy for {pr}; "
                  f"{st} against the field {c:.1f}% → {d:.1f}%", flush=True)


def exp(n, tests):
    """tests: [(label, deck, changes)] -> win rate against the field for each."""
    with Pool() as pool:
        for label, deck, changes in tests:
            r, _ = rate(pool, deck, n, changes=changes)
            print(f"{label:58s} {r:5.1f}%", flush=True)


def spells(*dmg):
    return {k: {"damage": v} for k, v in zip(("Touch of Death", "Finger of Death", "Spectral Scythe", "Death Skulls"), dmg)}


def ref(n):
    with Pool() as pool:
        print(f"{'card':22s} {'host':16s} {'copies':>6s} {'with':>6s} {'blank':>6s} {'value':>6s} {'/copy':>6s}")
        for host, cs in REF.items():
            opps = [o for o in FIELD if o != host]
            base, _ = rate(pool, D[host], n, opps=opps)
            for card, k in cs.items():
                k = D[host][card] if k is None else k
                r, _ = rate(pool, blank(D[host], card, k), n, opps=opps)
                d = base - r
                print(f"{card:22s} {host:16s} {k:6d} {base:6.1f} {r:6.1f} {d:+6.1f} {d / k:+6.1f}")


# ---------------------------------------------------------------- round 2a: the armour and the summon cape (2026-11-27)
def _vs_cape(args):
    """_vs, also counting how often `deck` claims its cape: (win rate, claim rate, claim turn)."""
    deck, opp, n, base, changes, rules, cape = args
    install(changes)
    od = D[opp] if isinstance(opp, str) else opp
    w = cl = ct = 0
    for s in range(n):
        for seat in (0, 1):
            ds = [None, None]
            ds[seat], ds[1 - seat] = deck, od
            r = dict(rules or {})
            if cape:
                r["capes"] = [cape, None] if seat == 0 else [None, cape]
            g = game(ds, base + s, r)
            w += g.winner == seat
            if g.stats[seat]["cape_claimed"]:
                cl += 1
                ct += g.stats[seat]["cape_claim_turn"]
    return w / (2 * n), cl / (2 * n), (ct / cl if cl else 0)


def rate_cape(pool, deck, n, cape, need=None, changes=None, base=700_000):
    rules = {"cape_need": {cape: need}} if (cape and need) else None
    rs = pool.map(_vs_cape, [(deck, o, n, base, changes, rules, cape) for o in FIELD])
    w = 100 * sum(r[0] for r in rs) / len(rs)
    c = 100 * sum(r[1] for r in rs) / len(rs)
    t = [r[2] for r in rs if r[1]]
    return w, c, (sum(t) / len(t) if t else 0)


R1 = spells(5, 9, 12, 16)
MAGIC_NUMS = spells(6, 10, 13, 17)
OFF = {"_armor_nec": 0}
ROBES = ("Wizard Robe", "Deathwarden Robe", "Adept Deathwarden Robe", "Master Deathwarden Robe", "Master Deathdealer Robe")
LEG = "Robes of the First Necromancer"


def r2a(n, part):
    with Pool() as pool:
        def show(label, deck, **kw):
            r, per = rate(pool, deck, n, **kw)
            print(f"{label:66s} {r:5.1f}%", flush=True)
            return r
        if part in ("a", "all"):
            print("-- A. Necrotic -1 on the Set 1 armour: each Necromancy deck with it off (round 1) and on")
            for h, d in HOSTS2.items():
                x = show(f"{h}, armour lets Necrotic through", d, changes=OFF)
                y = show(f"{h}, Necrotic -1", d)
                print(f"   change {y - x:+.1f}")
            print("-- B. The Necrotic spell numbers, with Necrotic -1")
            show("Necrotic, 5/9/12 (round 1)", PURE, changes=R1)
            show("Necrotic, 6/10/13 (the Magic numbers)", PURE, changes=MAGIC_NUMS)
            show("Necrotic, 6/9/12", PURE, changes=spells(6, 9, 12, 16))
        if part in ("c", "all"):
            print("-- C. The robes in the Necrotic deck (2 copies in place of its 2 Wizard Robes), against the field and")
            print("   against the Necrotic deck itself (a mirror, 3x the games). Spells at 5/9/12.")
            mirror = [("Necrotic", PURE)]
            res = {}
            for robe in ("Blank",) + ROBES:
                d = PURE if robe == "Wizard Robe" else swap(PURE, {"Wizard Robe": 2}, {robe: 2})
                f, _ = rate(pool, d, n)
                m, _ = rate(pool, d, 3 * n, opps=mirror)
                res[robe] = (f, m)
                print(f"{robe:26s} field {f:5.1f}%   mirror {m:5.1f}%", flush=True)
            b = res["Blank"]
            for robe in ROBES:
                f, m = res[robe]
                print(f"   {robe:26s} per copy: field {(f - b[0]) / 2:+.1f}, mirror {(m - b[1]) / 2:+.1f}")
        if part in ("d", "all"):
            print("-- D. Robes of the First Necromancer (1 copy, for a Lobster or the like) in each summon deck")
            outs = {"Melee + Necro": "Lobster", "Ranged + Necro": "Lobster", "Mage + Necro": "Shrimp"}
            for h in ("Melee + Necro", "Ranged + Necro", "Mage + Necro"):
                d0 = HOSTS2[h]
                out = outs[h] if d0.get(outs[h]) else next(k for k in d0 if CARDS[k].kind == "food")
                bl = show(f"{h}, a Blank for its {out}", swap(d0, {out: 1}, {"Blank": 1}))
                wl = swap(d0, {out: 1}, {LEG: 1})
                for k in (1, 2, 3):
                    r = show(f"   the Legendary at {k} Ectoplasm", wl, changes={LEG: {"req": (("Ectoplasm", k),)}})
                    print(f"      value {r - bl:+.1f}")
                r = show(f"   the Legendary at 2 Ectoplasm, no summon bonus", wl,
                         changes={LEG: {"req": (("Ectoplasm", 2),)}, "_nobonus": 1})
        if part == "d2":
            print("-- D2. The Legendary robes: the Mage deck, the heavier summon builds, and Legendary weapons for scale")
            cases = [("Mage + Necro (light)", HOSTS2["Mage + Necro"], "Wooden Shield"),
                     ("Melee + Necro (heavy)", NECRO_HEAVY["Melee + Necro"], "Lobster"),
                     ("Ranged + Necro (heavy)", NECRO_HEAVY["Ranged + Necro"], "Lobster"),
                     ("Mage + Necro (heavy)", NECRO_HEAVY["Mage + Necro"], "Wooden Shield")]
            for h, d0, out in cases:
                if not d0.get(out):
                    out = next(k for k in d0 if CARDS[k].kind in ("food", "shield"))
                bl = show(f"{h}, a Blank for its {out}", swap(d0, {out: 1}, {"Blank": 1}))
                wl = swap(d0, {out: 1}, {LEG: 1})
                for k in (1, 2):
                    r = show(f"   the Legendary at {k} Ectoplasm", wl, changes={LEG: {"req": (("Ectoplasm", k),)}})
                    print(f"      value {r - bl:+.1f}")
            print("-- Legendary weapons in the starters (1 copy for a Lobster), for scale")
            for st, leg in (("Melee", "Dragon Dagger"), ("Melee", "Granite Maul"), ("Ranged", "Dark Bow"),
                            ("Air Mage", "Staff of the Dead")):
                d0 = D[st]
                out = "Lobster" if d0.get("Lobster") else next(k for k in d0 if CARDS[k].kind == "food")
                opps = [o for o in FIELD if o != st]
                bl = show(f"{st}, a Blank for its {out}", swap(d0, {out: 1}, {"Blank": 1}), opps=opps)
                r = show(f"   {leg}", swap(d0, {out: 1}, {leg: 1}), opps=opps)
                print(f"      value {r - bl:+.1f}")
        if part == "f":
            print("-- F1. The Master Deathdealer at Necrotic -2 (power armour trading defence for damage), as in C (n=200)")
            dd = "Master Deathdealer Robe"
            ch = {dd: {"armor": (("melee", -1), ("ranged", -1), ("magic", -1), (NEC, -2))}}
            d = swap(PURE, {"Wizard Robe": 2}, {dd: 2})
            f, _ = rate(pool, d, n, changes=ch)
            m, _ = rate(pool, d, 3 * n, changes=ch, opps=[("Necrotic", PURE)])
            print(f"{dd + ' (-2)':26s} field {f:5.1f}%   mirror {m:5.1f}%", flush=True)
        if part == "g":
            print("-- F2. The Necrotic deck's spell numbers again, by the robes it wears (n as given)")
            for robes in ({"Wizard Robe": 2}, {"Master Deathdealer Robe": 2}, {"Deathwarden Robe": 1, "Master Deathdealer Robe": 1}):
                d = PURE if robes == {"Wizard Robe": 2} else swap(PURE, {"Wizard Robe": 2}, robes)
                for nums in ((5, 9, 12, 16), (6, 9, 12, 16), (6, 10, 13, 17)):
                    show(f"{', '.join(f'{v} {k}' for k, v in robes.items())}; spells {nums[0]}/{nums[1]}/{nums[2]}",
                         d, changes=spells(*nums))
        if part == "h":
            print("-- H. Death Skulls with Necrotic -1: 2 Death Skulls + 2 Miasma for 2 Spectral Scythe + 2 Spirit")
            ds = swap(PURE, {"Spectral Scythe": 2, "Spirit Rune": 2}, {"Death Skulls": 2, "Miasma Rune": 2})
            for nums in ((6, 9, 12, 16), (6, 9, 12, 17)):
                a = show(f"Necrotic, spells {nums}", PURE, changes=spells(*nums))
                b = show(f"   with 2 Death Skulls at {nums[3]}", ds, changes=spells(*nums))
                print(f"      gain {b - a:+.1f} (Air Wave in the Air Mage, round 2: -4.6)")
        if part == "cape2":
            print("-- Cape2. The Necromancy Cape two ways (the deck brings it, the other side none), at round 2a's numbers:")
            print("   'arrive' counts summons coming into play; 'hits' counts summon hits that reach the deck")
            print(f"{'deck':30s} {'cape':26s} {'wins':>6s} {'lift':>6s} {'claims':>7s} {'turn':>5s}")
            style_cape = {"Melee + Necro": "Attack Cape", "Ranged + Necro": "Ranged Cape", "Mage + Necro": "Magic Cape"}
            for label, decks in (("light", HOSTS2), ("heavy", NECRO_HEAVY)):
                for h in ("Melee + Necro", "Ranged + Necro", "Mage + Necro"):
                    d = decks[h]
                    base_w = None
                    for cape, need, tag in ((None, None, "none"), (style_cape[h], None, style_cape[h]),
                                            ("Necromancy Cape (arrivals)", 2, "arrive, need 2"), ("Necromancy Cape (arrivals)", 3, "arrive, need 3"),
                                            ("Necromancy Cape", 2, "hits, need 2"), ("Necromancy Cape", 3, "hits, need 3"),
                                            ("Necromancy Cape", 4, "hits, need 4"), ("Necromancy Cape", 5, "hits, need 5")):
                        w, c, t = rate_cape(pool, d, n, cape, need)
                        if base_w is None:
                            base_w = w
                        print(f"{h + ' (' + label + ')':30s} {tag:26s} {w:6.1f} {w - base_w:+6.1f} {c:6.1f}% {t:5.1f}", flush=True)
        if part in ("e", "all"):
            print("-- E. The Necromancy Cape counting summons (the deck brings it, the other side none)")
            print(f"{'deck':34s} {'cape':24s} {'wins':>6s} {'claims':>7s} {'turn':>5s}")
            style_cape = {"Melee + Necro": "Attack Cape", "Ranged + Necro": "Ranged Cape", "Mage + Necro": "Magic Cape"}
            for label, decks in (("light", HOSTS2), ("heavy", NECRO_HEAVY)):
                for h in ("Melee + Necro", "Ranged + Necro", "Mage + Necro"):
                    d = decks[h]
                    for cape, need in ((None, None), (style_cape[h], None), ("Necromancy Cape", 2),
                                       ("Necromancy Cape", 3), ("Necromancy Cape", 4)):
                        w, c, t = rate_cape(pool, d, n, cape, need)
                        cl = f"{cape} ({need})" if need else (cape or "none")
                        print(f"{h + ' (' + label + ')':34s} {cl:24s} {w:6.1f} {c:6.1f}% {t:5.1f}", flush=True)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "smoke"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 100
    if cmd == "smoke":
        smoke()
    elif cmd == "r2a":
        r2a(n, sys.argv[3] if len(sys.argv) > 3 else "all")
    elif cmd == "field":
        field(n)
    elif cmd == "vob":
        vob(n, set(sys.argv[3].split("|")) if len(sys.argv) > 3 else None)
    elif cmd == "ref":
        ref(n)
    elif cmd == "round2":
        round2(n)
    elif cmd == "capes":
        capes_test(n)
    elif cmd == "pfn":
        pfn(n)
    elif cmd == "vob2":
        vob2(n, {})
    elif cmd == "exp7":      # starter candidates at round 1's numbers (every summon on 1 Ectoplasm, Accelerated Decay +4)
        R1 = {**CANDIDATE_R1, "Phantom Guardian": {"req": (("Ectoplasm", 1), ("Mithril Bar", 2))},
              "Accelerated Decay": {"bonus": 4}}
        MIX = {"Spirit Rune": 12, "Bone Rune": 4, "Flesh Rune": 2, "Death Rune": 2, "Ectoplasm": 3,
               "Touch of Death": 4, "Finger of Death": 4, "Spectral Scythe": 3, "Rasial's Greed": 2,
               "Vengeful Ghost": 2, "Skull Lantern": 1, "Necromancy Ritual": 1,
               "Bone Shield": 3, "Kiteshield": 2, "Wooden Shield": 1, "Wizard Robe": 2,
               "Decay": 2, "Accelerated Decay": 2, "Protect from Melee": 1,
               "Rune Pouch": 3, "Archmage Sedridor": 1, "City of Um": 1, "Lobster": 2}
        assert sum(MIX.values()) == 60
        exp(n, [("Necrotic (spells only)", PURE, R1),
                ("Starter idea: spells + 2 Ghosts on 1 Ectoplasm", MIX, R1),
                ("Melee + Necro (with a Guardian)", HOSTS2["Melee + Necro"], R1),
                ("Ranged + Necro", HOSTS2["Ranged + Necro"], R1),
                ("Mage + Necro", HOSTS2["Mage + Necro"], R1)])
    elif cmd == "exp6":      # the Guardian, and Accelerated Decay at +4
        H = HOSTS2["Melee + Necro"]
        G = lambda **kw: {**CANDIDATE_R1, "Phantom Guardian": {**CANDIDATE_R1["Phantom Guardian"], **kw}}
        tests = []
        for label, ch in (("2 Ectoplasm, hit 7, soak 6", G()), ("2 Ectoplasm, hit 8, soak 8", G(hit=8, soak=8)),
                          ("1 Ectoplasm + 2 Mithril, hit 7, soak 6", G(req=(("Ectoplasm", 1), ("Mithril Bar", 2)))),
                          ("2 Ectoplasm, hit 8, soak 8, durability 4", G(hit=8, soak=8, durability=4))):
            tests += [(f"Melee + Necro, Guardian {label}", H, ch), (f"   ... blanked", blank(H, "Phantom Guardian"), ch)]
        tests += [("Necrotic, Accelerated Decay +3", PURE, CANDIDATE_R1),
                  ("Necrotic, Accelerated Decay +4", PURE, {**CANDIDATE_R1, "Accelerated Decay": {"bonus": 4}}),
                  ("   ... blanked", blank(PURE, "Accelerated Decay"), CANDIDATE_R1)]
        exp(n, tests)
    elif cmd == "exp2":      # is Necrotic damage itself worth less than Magic? The Air Mage with its spells made Necrotic
        for s in ("Air Strike", "Air Bolt", "Air Blast"):
            CARDS["Nec " + s] = dataclasses.replace(CARDS[s], name="Nec " + s, style=NEC)
            NEW.append(CARDS["Nec " + s])
        AM = D["Air Mage"]
        necam = swap(AM, {"Air Strike": 4, "Air Bolt": 4, "Air Blast": 4},
                     {"Nec Air Strike": 4, "Nec Air Bolt": 4, "Nec Air Blast": 4})
        nec_nosummon = swap(NECRO["Necrotic"],
                            {"Ectoplasm": 4, "Death Rune": 2, "Vengeful Ghost": 2, "Skull Lantern": 1, "Necromancy Ritual": 2},
                            {"Spirit Rune": 1, "Touch of Death": 0, "Kiteshield": 2, "Wooden Shield": 2, "Spectral Scythe": 1,
                             "Iban's Staff": 2, "Death Rune": 3})
        exp(n, [
            ("Air Mage", AM, None),
            ("Air Mage, its 12 spells dealing Necrotic instead of Magic", necam, None),
            ("Necrotic, no summon package (+2 Iban's Staff, shields)", nec_nosummon, spells(6, 10, 13, 17)),
        ])
    elif cmd == "exp5":      # what would make a summon splash pay for its Ectoplasm?
        ML, RL, MG = NECRO["Melee + Necro"], NECRO["Ranged + Necro"], NECRO["Mage + Necro"]
        rfree = {"Necromancy Ritual": {"free": True}}
        lfree = {"Skull Lantern": {"free": True}}
        g2 = {"Phantom Guardian": {"req": (("Ectoplasm", 2), ("Mithril Bar", 2))}}
        exp(n, [
            ("Melee + Necro", ML, None),
            ("Melee + Necro, Ritual free", ML, rfree),
            ("Melee + Necro, Lantern free", ML, lfree),
            ("Melee + Necro, Skeleton hit 7", ML, {"Skeleton Warrior": {"hit": 7}}),
            ("Melee + Necro, Skeleton durability 4", ML, {"Skeleton Warrior": {"durability": 4}}),
            ("Melee + Necro, Skeleton hit 7, Ritual and Lantern free", ML, {"Skeleton Warrior": {"hit": 7}, **rfree, **lfree}),
            ("Ranged + Necro", RL, None),
            ("Ranged + Necro, Lantern free", RL, lfree),
            ("Mage + Necro", MG, None),
            ("Mage + Necro, Ghost hit 7", MG, {"Vengeful Ghost": {"hit": 7}}),
            ("Mage + Necro, Ghost heals 3", MG, {"Vengeful Ghost": {"heals": 3}}),
            ("Mage + Necro, Ghost 1 Ectoplasm + 1 Death Rune", MG, {"Vengeful Ghost": {"req": (("Ectoplasm", 1), ("Death Rune", 1))}}),
            ("Guardian Melee, Guardian 2 Ectoplasm, Ritual free", NECRO["Guardian Melee"], {**g2, **rfree}),
        ])
    elif cmd == "exp4":      # a proper Necrotic deck, and Necromancy starter candidates
        PURE = {"Spirit Rune": 13, "Bone Rune": 5, "Flesh Rune": 3,
                "Touch of Death": 5, "Finger of Death": 4, "Spectral Scythe": 4, "Rasial's Greed": 3,
                "Bone Shield": 3, "Kiteshield": 2, "Wooden Shield": 2, "Wizard Robe": 2,
                "Decay": 2, "Accelerated Decay": 2, "Protect from Melee": 1,
                "Rune Pouch": 3, "Archmage Sedridor": 2, "City of Um": 1, "Lobster": 2, "Shrimp": 1}
        S1 = {"Spirit Rune": 11, "Bone Rune": 4, "Flesh Rune": 2, "Death Rune": 2, "Ectoplasm": 4,
              "Touch of Death": 4, "Finger of Death": 4, "Spectral Scythe": 3, "Rasial's Greed": 2,
              "Vengeful Ghost": 3, "Skull Lantern": 1, "Necromancy Ritual": 2,
              "Bone Shield": 2, "Kiteshield": 2, "Wooden Shield": 2, "Wizard Robe": 2,
              "Decay": 2, "Accelerated Decay": 1, "Protect from Melee": 1,
              "Rune Pouch": 2, "Archmage Sedridor": 1, "City of Um": 1, "Lobster": 1, "Shrimp": 1}
        S2 = swap(S1, {"Death Rune": 2}, {"Spirit Rune": 2})
        anyrune = {"Vengeful Ghost": {"req": (("Ectoplasm", 2),), "fam_req": (("rune", 1),)}}
        g2 = {"Phantom Guardian": {"req": (("Ectoplasm", 2), ("Mithril Bar", 2))}}
        ML = swap(NECRO["Melee + Necro"], {"Burst of Strength": 1}, {"Phantom Guardian": 1})
        for k, v in (("PURE", PURE), ("S1", S1)):
            assert sum(v.values()) == 60, (k, sum(v.values()))
        exp(n, [
            ("Necrotic (no summons), 5/9/12", PURE, spells(5, 9, 12, 16)),
            ("Necrotic (no summons), 6/10/13", PURE, spells(6, 10, 13, 17)),
            ("Starter idea: spells + Ghosts on Death Runes, 5/9/12", S1, spells(5, 9, 12, 16)),
            ("Starter idea: spells + Ghosts on Death Runes, 6/10/13", S1, spells(6, 10, 13, 17)),
            ("Starter idea: Ghosts on any rune, 5/9/12", S2, {**spells(5, 9, 12, 16), **anyrune}),
            ("Starter idea: Ghosts on any rune, 6/10/13", S2, {**spells(6, 10, 13, 17), **anyrune}),
            ("Melee + Necro light + 1 Guardian (3 Ectoplasm)", ML, None),
            ("Melee + Necro light + 1 Guardian (2 Ectoplasm)", ML, g2),
            ("Guardian Melee, Guardian 2 Ectoplasm", NECRO["Guardian Melee"], g2),
            ("Mage + Necro light, Ghost on any rune", NECRO["Mage + Necro"], anyrune),
        ])
    elif cmd == "exp3":      # the Necrotic deck without its summon package
        N = NECRO["Necrotic"]
        B = swap(N, {"Ectoplasm": 4, "Death Rune": 2, "Vengeful Ghost": 2, "Skull Lantern": 1, "Necromancy Ritual": 2},
                 {"Kiteshield": 2, "Wooden Shield": 2, "Spirit Rune": 1, "Spectral Scythe": 1, "Lobster": 1,
                  "Rune Pouch": 1, "Archmage Sedridor": 1, "Rasial's Greed": 1, "Touch of Death": 1})
        C = swap(B, {"Kiteshield": 2, "Wooden Shield": 2}, {"Bone Shield": 1, "Decay": 0, "Finger of Death": 0,
                                                            "Lobster": 1, "Touch of Death": 0, "Spectral Scythe": 0,
                                                            "Rune Pouch": 1, "Spirit Rune": 1})
        exp(n, [
            ("Necrotic as built, Magic numbers", N, spells(6, 10, 13, 17)),
            ("Necrotic, no summons (shields, spells), drafted 4/8/11", B, None),
            ("Necrotic, no summons, Magic numbers 6/10/13", B, spells(6, 10, 13, 17)),
            ("Necrotic, no summons, 5/9/12", B, spells(5, 9, 12, 16)),
            ("Necrotic, no summons or Kite/Wooden, Magic numbers", C, spells(6, 10, 13, 17)),
        ])
    elif cmd == "exp1":      # the necrotic deck's numbers, and the lighter hybrid builds
        N = NECRO["Necrotic"]
        exp(n, [
            ("Necrotic, spells 4/8/11", N, None),
            ("Necrotic, spells 6/10/13 (the Magic numbers)", N, spells(6, 10, 13, 17)),
            ("Necrotic, spells 7/11/14", N, spells(7, 11, 14, 18)),
            ("Necrotic, spells 6/10/13, Accelerated Decay +4", N, {**spells(6, 10, 13, 17), "Accelerated Decay": {"bonus": 4}}),
            ("Melee + Necro (light)", NECRO["Melee + Necro"], None),
            ("Ranged + Necro (light)", NECRO["Ranged + Necro"], None),
            ("Mage + Necro (light)", NECRO["Mage + Necro"], None),
            ("Guardian Melee, as drafted", NECRO["Guardian Melee"], None),
            ("Guardian Melee, Guardian 2 Ectoplasm + 2 Mithril", NECRO["Guardian Melee"],
             {"Phantom Guardian": {"req": (("Ectoplasm", 2), ("Mithril Bar", 2))}}),
            ("Guardian Melee, Guardian soak 8, hit 8", NECRO["Guardian Melee"], {"Phantom Guardian": {"soak": 8, "hit": 8}}),
        ])
