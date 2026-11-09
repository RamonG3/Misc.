"""A simple greedy bot: score every legal action, take the best.

It plays reasonably, not well. That's on purpose for balance testing: if a
deck only wins in the hands of a genius, the simulator shouldn't say it's fine.
"""
import random

from cards import CARDS
from engine import ELEMENTAL

FUTURE = 0.6   # how much a hit that hasn't happened yet is worth vs. one now


def deck_needs(decklist):
    """Most of each material any card in the deck asks for."""
    need = {}
    for name in decklist:
        for m, k in CARDS[name].req + CARDS[name].expend:
            need[m] = max(need.get(m, 0), k)
        if CARDS[name].kind == "prayer" and "Bones" in CARDS:
            need["Bones"] = max(need.get("Bones", 0), 2)
    return need


def deck_style(decklist):
    tally = {}
    for name, n in decklist.items():
        c = CARDS[name]
        if c.style in ("melee", "ranged", "magic") and c.kind != "armor":
            tally[c.style] = tally.get(c.style, 0) + n
    return max(tally, key=tally.get)


class HeuristicBot:
    def __init__(self, seed=None, noise=0.25):
        self.rng = random.Random(seed)
        self.noise = noise

    def setup(self, game):
        self.need = [deck_needs(p.decklist) for p in game.players]
        self.style = [deck_style(p.decklist) for p in game.players]
        self.styles = [{CARDS[n].style for n in p.decklist
                        if CARDS[n].kind in ("weapon", "thrown", "bow", "staff", "spell", "ability")}
                       for p in game.players]
        self.capes = [any(CARDS[n].kind == "cape" for n in p.decklist)
                      or "Quest Point Cape" in getattr(game, "cape_table", []) for p in game.players]
        self.buffs = [any(CARDS[n].kind == "buff" for n in p.decklist) for p in game.players]

    # ---------- choosing ----------
    def choose(self, g, i, legal):
        return max(legal, key=lambda a: self.score(g, i, a) + self.rng.random() * self.noise)

    def discard_choice(self, g, i):
        p = g.players[i]
        return min(p.hand, key=lambda n: self.keep_value(g, i, n) + self.rng.random() * 0.1)

    # ---------- estimates ----------
    def preview(self, g, i, amount, style, ignore_armor=False, ignore_prayer=False):
        to_lp, soaked = g.compute_damage(i, amount, style, ignore_armor, ignore_prayer, src="weapon")
        return to_lp, soaked

    def hit_value(self, g, i, amount, style, ignore_armor=False, ignore_prayer=False):
        to_lp, soaked = self.preview(g, i, amount, style, ignore_armor, ignore_prayer)
        if to_lp >= g.opp(i).lp:
            return 1000.0
        return to_lp + 0.5 * soaked

    def unlocks(self, g, i, mat, amount=1):
        """How many distinct hand cards become playable with `amount` more of `mat`."""
        p = g.players[i]
        before = {n for n in set(p.hand) if CARDS[n].req and p.meets(CARDS[n].req)}
        p.materials[mat] += amount
        after = {n for n in set(p.hand) if CARDS[n].req and p.meets(CARDS[n].req)}
        p.materials[mat] -= amount
        if p.materials[mat] <= 0:
            del p.materials[mat]
        return len(after - before)

    def incoming(self, g, i):
        """Rough damage the opponent threatens next turn, and its style."""
        o = g.opp(i)
        w = o.weapon_card()
        if w and w.kind == "weapon":
            return w.hit + 2, "melee"
        if w and w.kind == "thrown":
            return w.hit, "ranged"
        if w and w.kind == "bow" and o.ammo:
            return w.hit + o.ammo["bonus"], "ranged"
        style = self.style[1 - i]
        if style == "magic" and o.family_count("rune") >= 1:
            return 10 if o.count("Chaos Rune") else 6, "magic"
        return 0, style

    # ---------- scoring ----------
    CAPE_TICK = 4.0       # worth of the last tick toward a cape; earlier ticks are worth less

    DAMAGE_ACTS = ("punch", "swing", "free_swing", "spec")

    def score(self, g, i, a):
        o = g.opp(i)
        doomed = o.cape is not None and o.cape >= g.rules["cape_turns"] - 1   # they win at their next turn
        if a[0] == "claim":
            if doomed:
                return -10.0                     # too late: only a kill saves you now
            return 40.0 if o.lp > 6 else 15.0
        v = self.base_score(g, i, a)
        if getattr(g, "cape_table", None) and g.players[i].cape is None and not doomed:
            v += self.cape_bonus(g, i, a)
        if doomed:
            dmg = a[0] in self.DAMAGE_ACTS or (a[0] == "play" and CARDS[a[1]].kind == "spell" and CARDS[a[1]].damage)
            v = v * 2.0 + 5.0 if dmg else v - 4.0
        return v

    def cape_events(self, g, i, a):
        """What a move would count toward capes: [(event or state family, ticks)]."""
        p = g.players[i]
        if a[0] == "punch":
            return [("melee_hit", 1)]
        if a[0] in ("swing", "free_swing"):
            w = p.weapon_card()
            return [(a[1] + "_hit", w.hits if w else 1)]
        if a[0] == "spec":
            w = p.weapon_card()
            return [((w.styles or (w.style,))[0] + "_hit", 1)] if w else []
        if a[0] != "play":
            return []
        c = CARDS[a[1]]
        if c.kind == "spell" and c.damage:
            return [("magic_hit", 1)]
        if c.kind == "food":
            return [("food", 1)]
        if c.kind == "buff" and c.duration:
            return [("elite_prayer", 1)]
        if c.kind == "material":
            return [(CARDS[m].mat, 1) for m in (c.combo or (a[1],))]
        if c.kind == "shield":
            return [("soak", c.soak // 2)]
        return []

    def cape_bonus(self, g, i, a):
        from capes import CAPES, progress
        p = g.players[i]
        ev = self.cape_events(g, i, a)
        if not ev:
            return 0.0
        v = 0.0
        for cape in g.cape_table:
            if cape in g.cape_claimed:
                continue
            kind, what, _ = CAPES[cape]
            ticks = sum(n for e, n in ev if e == what)
            if not ticks:
                continue
            have, need = progress(p, cape, g.rules["cape_need"])
            left = need - have
            if left <= 0:
                continue
            v += self.CAPE_TICK * min(ticks, left) / left
        return v

    def base_score(self, g, i, a):
        p, o = g.players[i], g.opp(i)
        if a[0] == "draw":
            if not p.deck:
                return -50.0
            # Early on, digging for the missing material beats a 2-damage punch.
            v = 2.6 if g.own_turn(i) <= 8 else 1.6
            if p.deck_life:                  # drawing spends a point of life
                v -= 0.4 + (2.0 if p.lp <= 8 else 0.0)
            if len(p.hand) >= g.rules["hand_max"]:
                v -= 0.6
            if len(p.deck) <= 3 and p.respawns < g.rules["max_respawns"]:
                v -= 1.0
            return v
        if a[0] == "punch":
            return self.hit_value(g, i, g.rules["punch_damage"], "melee", True, True) * 0.9
        if a[0] == "swing":
            return self.swing_value(g, i, a[1])
        if a[0] == "free_swing":                 # costs no action: take it if it does anything
            v = self.swing_value(g, i, a[1])
            return v + 3.0 if v > 0.5 else -1.0
        if a[0] == "spec":
            return self.spec_value(g, i)
        if a[0] == "legend":
            return self.score(g, i, ("play", a[1], "equip"))
        if a[0] == "varrock":                    # discard a card to heal 3
            return 3.0 * self.urgency(p) - 0.6 * self.keep_value(g, i, a[1]) - 0.3
        if a[0] == "exchange":                   # free: swap a card from hand for the top of your deck
            v = 2.4 - self.keep_value(g, i, a[1])
            if p.deck_life:
                v -= 0.4 + (2.0 if p.lp <= 8 else 0.0)
            return v + 2.0 if v > 0 else v
        if a[0] == "quest":
            v = self.quest_step_value(g, i, a[1])
            free = g.players[i].free_steps < g.rules["free_quest_steps"]
            return v + 6.0 if free and v > 0 else v

        _, name, target = a
        c = CARDS[name]
        k = c.kind
        need = self.need[i]
        threat, threat_style = self.incoming(g, i)

        if k == "material":
            parts = c.combo or (name,)
            v = 3.0 if any(p.count(m) < need.get(m, 0) for m in parts) else 0.4
            if c.combo and sum(p.count(m) < need.get(m, 0) for m in parts) == 2:
                v = 4.5
            return v + 4.0 * self.unlocks(g, i, name)

        if k == "spell":
            v = self.hit_value(g, i, c.damage, "magic") if c.damage else 0.0
            v += 2.0 * c.draws
            if c.heals:
                v += min(c.heals, g.rules["max_lp"] - p.lp) * self.urgency(p)
            if c.fetch:
                v += self.fetch_value(g, i, c.fetch)
            if c.wears:
                w = o.weapon_card()
                if w and o.weapon["dur"]:
                    v += min(c.wears, o.weapon["dur"]) * (w.hit or w.bash_hit) * FUTURE
                elif o.ammo:
                    v += min(c.wears, o.ammo["shots"]) * 5 * FUTURE
            return v

        swing = g.rules["swing_action"]
        if c.legendary and g.rules["legend_telegraph"] == "reveal" and target != "equip":
            return 3.5 if not p.telegraph else -2.0      # reveal it early: it's a free threat
        if k in ("weapon", "thrown") and swing and c.equip_swing:
            return self.hit_value(g, i, c.hit, "melee") + 4.0 - self.current_weapon_value(p)
        if k in ("weapon", "thrown") and swing:
            spec = 2.5 if c.spec else 0.0
            spec += c.hit * c.durability * (c.hits - 1) * FUTURE
            if c.two_handed and p.shield:
                spec -= p.shield["soak"] * 0.3
            return c.hit * c.durability * FUTURE + 1.0 + spec - self.current_weapon_value(p)
        if k == "weapon":
            v = self.hit_value(g, i, c.hit, "melee")
            v += c.hit * (c.durability - 1) * FUTURE
            return v - self.current_weapon_value(p)

        if k == "staff" and swing:
            v = c.hit * c.durability * FUTURE + 1.0
            for m, n in c.counts_as:
                if m == "Elemental Rune":     # Staff of Light: counts as your main element
                    m = max(ELEMENTAL, key=lambda e: p.count(e, True))
                v += 4.0 * self.unlocks(g, i, m, n)
            return v - self.current_weapon_value(p)
        if k == "staff":
            if g.rules["staff_autocast"]:
                v = self.hit_value(g, i, c.hit, "magic") + c.hit * (c.durability - 1) * FUTURE
                return v + 4.0 * self.unlocks(g, i, "Air Rune", 2) - self.current_weapon_value(p)
            if p.weapon:
                return -5.0
            return 2.0 + 4.0 * self.unlocks(g, i, "Air Rune", 2)

        if k == "bow":
            cur = p.weapon_card()
            if cur and cur.kind == "bow":
                return (c.hit - cur.hit) * 3 * FUTURE - 1.0
            bonus = p.ammo["bonus"] if p.ammo and p.ammo["tier"] <= c.arrow_tier else 0
            if swing:
                v = c.hit * 3 * FUTURE + 4.0 + c.soak * 0.4 + c.bash_hit * c.durability * FUTURE * 0.5
                for m, n in c.counts_as:
                    v += 4.0 * self.unlocks(g, i, m, n)
                return v - self.current_weapon_value(p)
            v = self.hit_value(g, i, c.hit + bonus, "ranged")
            return v + c.hit * 2 * FUTURE + 3.0 - self.current_weapon_value(p)

        if k == "arrows":
            bow = p.weapon_card()
            new = 3 * (bow.hit + c.arrow_bonus)
            cur = p.ammo["shots"] * (bow.hit + p.ammo["bonus"]) if p.ammo else 0
            return (new - cur) * 0.5 + (2.0 if not p.ammo else 0.0)

        if k == "ability":
            w = p.weapon_card()
            v = self.hit_value(g, i, w.hit + c.damage, "melee", ignore_armor=c.ignores_armor)
            if swing:
                return v - 0.3 * w.hit - 1.0      # a swing that costs a card
            return v - FUTURE * w.hit + 0.5

        if k in ("rapid", "longrange"):
            bow = p.weapon_card()
            thrown = bow.kind == "thrown"
            left = p.weapon["dur"] if thrown else p.ammo["shots"]
            shot = bow.hit + (0 if thrown else p.ammo["bonus"]) + (-1 if k == "longrange" else 0)
            if swing:
                n = 2 if (k == "rapid" and left >= 2) else 1
                v = self.hit_value(g, i, shot * n, "ranged") - 0.2 * shot * n - 1.0
                if k == "longrange" and threat_style == "melee":
                    v += 2.0
                return v
            v = self.hit_value(g, i, shot, "ranged") - FUTURE * (shot + (1 if k == "longrange" else 0)) + 0.5
            if k == "longrange" and threat_style == "melee":
                v += 2.0
            return v

        if k == "teleport":
            f = self.fetch_value(g, i, c.fetch)
            return f if f > 0 else -2.0
        if k == "hammer":
            if target == "weapon":
                w = p.weapon_card()
                return c.bonus * w.hit * w.hits * FUTURE * 0.7
            return c.bonus * 0.45 + (1.0 if threat > p.shield["soak"] else 0.0)

        if k == "switch":
            v = self.score(g, i, ("play", target, None))
            return v + 2.0 if v > 1.0 else -1.0

        if k == "destroy":
            v = 0.0
            if target is not None:
                their_need = self.need[1 - i].get(target, 0)
                v = (5.0 if o.count(target, real=True) <= their_need else 1.0) * min(c.destroy_n, o.materials.get(target, 0))
            if c.damage:
                st = c.style if c.style in ("melee", "ranged", "magic") else "none"
                v += self.hit_value(g, i, c.damage, st)
            return v

        if k == "pouch":
            v = 2.0 + 4.0 * self.unlocks(g, i, target)
            return v + (2.0 if p.count(target) < need.get(target, 0) else 0.0)

        if k == "armor":
            mods = dict(c.armor)
            fit = -mods.get(threat_style, 0) * 0.75 + c.bonus * 1.6 * 0.7 * 3   # bonus: Void, Round 22 platebodies
            if not p.armor:
                return 3.0 + fit
            cur = dict(CARDS[p.armor].armor).get(threat_style, 0)
            return (cur - mods.get(threat_style, 0)) * 0.75 - 0.5

        if k == "shield":
            if not p.shield:
                v = 2.0 + min(c.soak, max(threat, 4)) * 0.4
            else:
                v = (c.soak - p.shield["soak"]) * 0.3 - 0.5
            return v + (2.0 if c.free and v > 0 else 0.0)

        if k == "food":
            missing = g.rules["max_lp"] - p.lp
            eff = min(c.heal, missing, len(p.discard) if p.deck_life else missing)
            urgency = 1.2 if p.lp <= 15 else 0.8 if p.lp <= 30 else 0.3
            return eff * urgency - (c.heal - eff) * 0.3

        if k == "prayer":
            if c.prayer != threat_style or threat <= 0:
                return -1.0
            return threat * 0.45

        if k == "npc":
            t = CARDS[target]
            if t.kind == "material":
                return 3.0 + 4.0 * self.unlocks(g, i, target)
            if t.kind in ("weapon", "bow", "staff") and not p.weapon:
                return 4.0
            return 2.5

        if k == "buff":
            v = self.buff_value(g, i, c)
            return v + 3.0 if g.rules.get("buff_free") and v > 0 else v
        if k == "quest":
            if not self.quest_feasible(g, i, c):
                return -5.0
            v = self.quest_worth(g, i, c) / (len(c.steps) + 1) - 0.5
            if p.quest:                       # abandoning the current one
                cur = CARDS[p.quest["name"]]
                if self.quest_feasible(g, i, cur):
                    v -= self.quest_worth(g, i, cur) * (p.quest["step"] + 1) / (len(cur.steps) + 1) + 2.0
            return v
        if k == "cape":
            if not g.rules["cape_turns"]:
                return 1000.0
            return 40.0 if g.players[1 - i].lp > 6 else 15.0

        if k == "location":
            v = 1.0 if g.location_owner == 1 - i else 0.0
            if name == "Wizards' Tower":
                v += 3.0 if self.style[i] == "magic" else -1.0
                v -= 1.5 if self.style[1 - i] == "magic" else 0.0
            elif name == "Wilderness":
                v += 2.0 if p.lp >= o.lp else 0.5
            else:
                v += self.location_value(g, i, name)
            return v

        return 0.0

    @staticmethod
    def urgency(p):
        if p.deck_life and not p.discard:
            return 0.0
        return 1.2 if p.lp <= 15 else 0.8 if p.lp <= 30 else 0.3

    def fetch_value(self, g, i, loc):
        p = g.players[i]
        if loc not in p.deck or g.location == loc:
            return 0.0
        return 1.0 + (1.0 if g.location_owner == 1 - i else 0.0) + self.location_value(g, i, loc)

    def location_value(self, g, i, name):
        """What a new location is worth to player i (on top of displacing the opponent's)."""
        p, o = g.players[i], g.opp(i)
        if name == "Mage Arena":
            f = {"magic": 3.0, "ranged": -2.5}
            return f.get(self.style[i], 0.0) - f.get(self.style[1 - i], 0.0)
        if name == "Duel Arena":
            kinds = lambda q: sum(n for c, n in q.decklist.items() if CARDS[c].kind in ("food", "buff", "prayer"))
            v = 0.12 * (kinds(o) - kinds(p))
            v += (3.0 if o.buff else 0.0) - (3.0 if p.buff else 0.0)
            return v
        if name == "Lumbridge":
            return 2.0 + 0.08 * (o.lp - p.lp)
        if name == "Varrock":
            return 1.0 + (1.0 if p.lp < o.lp else 0.0)
        if name == "Grand Exchange":
            return 2.5
        return 0.0

    def attacks_per_turn(self, g, i, style):
        """Rough attacks of this style the deck makes per turn, once set up."""
        if style == "any":                    # Smite (Round 22): every attack
            return 1.6
        if style not in self.styles[i]:
            return 0.0
        return 1.6 / len(self.styles[i] & {"melee", "ranged", "magic"})

    def buff_value(self, g, i, c):
        p, o = g.players[i], g.opp(i)
        turns = c.duration or 5
        if c.effect == "smite":
            v = 1.0 + (4.0 if o.buff else 1.0) + c.bonus * self.attacks_per_turn(g, i, c.prayer) * turns * 0.7
        elif c.effect == "redemption":
            v = 6.0 if p.lp <= 25 else 3.0 if p.lp <= 40 else 0.5
        else:
            v = c.bonus * self.attacks_per_turn(g, i, c.prayer) * turns * 0.7
        if p.buff:
            v -= self.buff_value_current(g, i) + 1.0
        return v - 1.0

    def buff_value_current(self, g, i):
        p = g.players[i]
        c = CARDS[p.buff]
        turns = getattr(p, "buff_left", 0) or 5
        if c.effect:
            return 2.0 + (c.bonus * self.attacks_per_turn(g, i, c.prayer) * turns * 0.7 if c.bonus else 0.0)
        return c.bonus * self.attacks_per_turn(g, i, c.prayer) * turns * 0.7

    def spec_value(self, g, i):
        p, o = g.players[i], g.opp(i)
        w = p.weapon_card()
        s = w.spec
        threat, threat_style = self.incoming(g, i)
        def hv(amount, style, **kw):
            return self.hit_value(g, i, amount, style, **kw)
        if s == "sanctuary":
            return 2.0 * w.spec_val * 0.6 if threat > 0 else -1.0
        if s == "light":
            return threat * 0.4 + 0.5 if threat_style == "melee" and threat > 0 else -1.0
        if s == "war_god":
            return hv(w.hit, "melee") + (3.0 if o.buff else 0) - 0.2 * w.hit
        if s == "sunder":
            v = hv(w.hit + w.spec_val, "melee") - 0.2 * w.hit
            return v + (o.shield["soak"] * 0.4 if o.shield and not o.shield.get("virtual") else 0)
        if s == "snapshot":
            shot = w.hit + p.ammo["bonus"]
            n = 2 if p.ammo["shots"] >= 2 else 1
            return hv(shot * n, "ranged") - 0.15 * shot * n
        if s == "soulshot":
            return hv(w.hit + p.ammo["bonus"] + w.spec_val, "ranged", ignore_armor=True) + 1.5
        if s == "iban":
            return hv(w.spec_val, "magic") - 0.2 * w.hit
        if s == "lightning":
            return hv(w.hit, "melee") + hv(w.spec_val, "magic") - 0.2 * w.hit
        return 0.0

    REWARD = {"heal": 0.6, "draw": 2.5, "fetch_rune": 4.0, "fetch_bar": 4.0, "fetch_prayer": 3.0,
              "none": 0.0, "lose_action": 5.0, "damage": 0.9, "chicken": 0.0,
              "silverlight": 8.0, "dragon_slayer": 6.0}

    def quest_worth(self, g, i, c):
        p, o = g.players[i], g.opp(i)
        need = (g.rules.get("cape_need") or {}).get("Quest Point Cape", g.rules["cape_qp"])
        if getattr(g, "cape_table", None):         # cape zone: the cape's own requirement (6 QP)
            from capes import progress
            need = progress(p, "Quest Point Cape", g.rules.get("cape_need"))[1]
        open_cape = self.capes[i] and p.cape is None and "Quest Point Cape" not in getattr(g, "cape_claimed", {})
        qp_val = 2.5 if open_cape else 0.4
        if open_cape and getattr(g, "cape_table", None):
            qp_val = 3.5                       # cape zone: the cape is always there to claim
        if open_cape and p.qp + c.qp >= need:
            qp_val = 4.0 if not getattr(g, "cape_table", None) else 6.0
        r = self.REWARD.get(c.effect, 0.0) * max(1, c.reward_val if c.effect not in ("lose_action",) else 1)
        if c.effect == "heal":
            r = 0.6 * min(c.reward_val, g.rules["max_lp"] - p.lp + 6)
        if c.effect == "chicken":
            r = self.current_weapon_value(o) + 2.0
        if c.effect == "silverlight" and p.weapon:
            r = 3.0
        return c.qp * qp_val + r

    GEAR = {"weapon": ("weapon", "thrown", "bow", "staff"), "armor": ("armor",), "shield": ("shield",)}

    def quest_feasible(self, g, i, c):
        """Can this deck finish the quest at all? (Gear it doesn't run, materials it doesn't play.)"""
        p = g.players[i]
        kinds = {CARDS[n].kind for n in p.decklist}
        fams = {CARDS[n].mat for n in p.decklist if CARDS[n].kind == "material"}
        for kind, arg in c.steps:
            if kind in self.GEAR and not kinds & set(self.GEAR[kind]) and not getattr(p, kind):
                return False
            if kind == "have" and arg[0] != "any" and arg[0] not in fams:
                return False
            if kind == "discard" and arg and arg not in kinds:
                return False
        return True

    def quest_step_value(self, g, i, target):
        p = g.players[i]
        c = CARDS[p.quest["name"]]
        left = len(c.steps) - p.quest["step"]
        v = self.quest_worth(g, i, c) / left
        kind, _ = c.steps[p.quest["step"]]
        if kind == "discard":
            v -= 0.6 * self.keep_value(g, i, target)
        elif kind == "spend":
            v -= 3.0 if p.count(target, real=True) <= self.need[i].get(target, 0) else 0.6
        return v

    def swing_value(self, g, i, style):
        """Swing now, or hold it? A swing spends durability (or a shot), so a hit
        that a shield would mostly soak, or a prayer would halve, is worth less
        now than next turn. Holding shows up as a low score, so the bot draws,
        casts or punches instead."""
        p, o = g.players[i], g.opp(i)
        w = p.weapon_card()
        if w.kind == "bow" and style == "ranged":
            amount, cost = w.hit + p.ammo["bonus"], 0.15
        elif w.kind == "bow":
            amount, cost = w.bash_hit, 0.2
        else:
            amount, cost = w.hit, 0.2
        to_lp, soaked = g.compute_damage(i, amount, style, src="weapon")
        to_lp, soaked = to_lp * w.hits, soaked * w.hits
        if to_lp >= o.lp:
            return 1000.0
        if w.legendary and o.shield and not o.shield.get("virtual"):
            soaked += o.shield["soak"]
        return to_lp + 0.45 * soaked - cost * amount

    def react(self, g, j, amount, style, src, opts):
        """Block or reverse an incoming attack? Worth it if it saves (or deals) enough."""
        me, att = g.players[j], 1 - j
        incoming, soak = g.compute_damage(att, amount, style)
        best, best_v = None, 0.0
        for name in opts:
            c = CARDS[name]
            v = incoming + 0.3 * soak
            if c.kind == "prayer" and g.rules.get("protect_halves") and getattr(self, "honest_protect", False):
                v = incoming - incoming // 2 + 0.3 * soak     # Round 24: a halving Protect only saves half
                if g.rules.get("protect_turn"):
                    v += 3.0                                  # and covers the rest of the turn
            if c.kind == "reversal":
                back, bsoak = g.compute_damage(j, amount, c.converts_to)
                v += 0.8 * back + 0.3 * bsoak
                if back >= g.players[att].lp:
                    v += 100
            if v > best_v:
                best, best_v = name, v
        lethal = incoming >= me.lp
        return best if best and (lethal or best_v >= 7) else None

    @staticmethod
    def current_weapon_value(p):
        w = p.weapon_card()
        if not w:
            return 0.0
        if w.kind in ("weapon", "thrown") or (w.kind == "staff" and p.weapon["dur"]):
            return w.hit * w.hits * p.weapon["dur"] * FUTURE
        if w.kind == "bow":
            return w.hit * 2 * FUTURE + 3.0
        return 6.0  # a staff a mage is relying on

    def keep_value(self, g, i, name):
        p = g.players[i]
        c = CARDS[name]
        if c.kind == "material":
            return 0.5 if p.count(name) >= self.need[i].get(name, 0) else 2.5
        if c.kind == "food":
            return 1.0 if p.lp >= 45 else 3.0
        if c.kind == "prayer":
            return 0.5 if c.prayer != self.style[1 - i] else 3.5
        if c.kind == "reversal":
            return 3.5 if c.reacts_to == self.style[1 - i] else 1.5
        if c.kind in ("spell", "weapon", "thrown", "bow", "arrows"):
            return 5.0
        if c.kind == "quest":
            return 0.2 if c.name in p.completed or p.quest else 3.0
        if c.kind == "cape":
            return 6.0
        if c.kind == "buff":
            return 3.5
        return 3.0
