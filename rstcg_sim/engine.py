"""Rules engine for the RuneScape TCG.

The engine knows the rules and nothing about strategy. Any agent (the
simulator's heuristic bot today, a smarter AI opponent or a human UI later)
plays by calling legal_actions() and handing one back to apply().

Actions are tuples:
    ("draw",)
    ("punch",)
    ("swing", style)              attack with the equipped weapon (style: melee/ranged/magic)
    ("free_swing", style)         Magical Longsword's swing after a spell; costs no action
    ("play", card_name, target)   target is None, or a card name
                                  (destroy target, Rune Pouch rune, NPC pick)
    ("spec",)                     the equipped weapon's special attack
    ("quest", target)             do the next step of your quest; target is the card
                                  to discard or the material to spend, if the step needs one
"""
import random
from collections import Counter, defaultdict

from cards import CARDS

DEFAULT_RULES = {
    "start_lp": 50,
    "opening_hand": 6,
    "hand_max": 7,
    "actions": 2,
    "punch_damage": 2,
    "respawn_cost": 10,
    "max_respawns": 1,
    "prayer_bones": 0,         # Bones are gone: Protect prayers cost nothing
    "max_lp": 50,          # assumption: food can't heal above starting LP
    "arrow_shots": 4,
    "bow_shots": 4,
    "staff_autocast": False,   # experiment: staves cast a free Strike each turn and wear out
    "swing_action": True,      # weapons, bows and staves attack only as an action (adopted)
    "reversal_fraction": 1.0,  # share of a reversed attack's damage that comes back
    "max_energy": 4,           # special attack energy a weapon can hold
    "capes": None,             # cape zone: [player 0's cape, player 1's cape], both on the table
    "cape_need": None,         # tuning overrides: {cape: count}
    "cape_qp": 5,              # quest points the Quest Point Cape needs
    "cape_turns": 3,           # the cape's owner wins at the start of their turn when it has this many counters
                               # (0: the old instant win). The cape can't be removed.
    "buff_upkeep": 0,          # Bones a buff prayer burns each turn (0: it lasts while its Bones are in play)
    "player_energy": True,     # special energy belongs to the player, not the weapon
    "free_quest_steps": 1,     # quest steps per turn that cost no action
    "buff_free": True,         # buff prayers cost no action to play
    "protect_halves": False,   # Round 24: Protect prayers block the attack (halving was Round 9-23)
    "protect_discard": False,  # experiment: a Protect prayer also costs a card from hand
    # Telegraph on Legendaries. "sick": it can't attack on the turn it's played (adopted, Round 14).
    # "reveal": put it face up as an action, equip it as an action on a later turn (Round 14 draft).
    "legend_telegraph": "sick",
    "deck_life": True,         # adopted (Round 10): your deck is your life (see Player.lp)
    # Round 16 fixes, found porting the engine to the browser game:
    "elite_fix": True,         # elite prayers last 3 of your turns counting the one you play it (was 4)
    "halve_fix": True,         # a Protect prayer halves the whole attack; buffs aren't added back after
}
DEFAULT_RULES.update({"max_respawns": 0, "max_lp": 60})   # no Respawn; healing can't push a deck past 60

DECK_LIFE = {"deck_life": True, "max_respawns": 0, "max_lp": 60}    # the deck-as-life game (now the default)
LIFE_POINTS = {"deck_life": False, "max_respawns": 1, "max_lp": 50, "start_lp": 50}   # the old 50 LP game

FREE_SPECS = {"sanctuary", "light"}
ELEMENTAL = ("Air Rune", "Water Rune", "Earth Rune", "Fire Rune")
NO_BUFF_SRC = {"punch", "reversal", "quest"}

DAMAGE_KINDS = {"spell", "weapon", "thrown", "bow", "arrows", "ability", "rapid", "longrange"}


class Player:
    def __init__(self, name, decklist, rng, deck_life=False):
        self.deck_life = deck_life
        self.rng = rng
        self.name = name
        self.decklist = dict(decklist)
        self.deck = [c for c, n in decklist.items() for _ in range(n)]
        rng.shuffle(self.deck)
        self.hand = []
        self.discard = []
        self.removed = []
        self.materials = Counter()
        self.weapon = None      # {"name", "dur"}  dur is None for staves and bows
        self.shield = None      # {"name", "soak"}
        self.armor = None       # card name
        self.ammo = None        # {"card", "bonus", "shots", "tier"}  card None = built-in shots
        self._lp = 0            # (setting .lp here would mill the deck in deck-as-life mode)
        self.prayer = None
        self.longrange = False
        self.ate_food = False
        self.ate_karambwan = False
        self.respawns = 0
        self.reacted_round = -1
        self.free_swing_ready = False
        self.free_swing_used = False
        self.buff = None        # buff prayer card name
        self.buff_new = False   # played this turn: no upkeep until next turn
        self.quest = None       # {"name", "step"}
        self.completed = []
        self.qp = 0
        self.sanctuary = 0      # Excalibur: damage you take reduced by this until your next turn
        self.light = False      # Staff of Light: melee damage you take halved until your next turn
        self.lost_actions = 0   # Prince Ali Rescue
        self.cape = None        # Quest Point Cape counters, once it's in play
        self.cape_name = None   # the cape you're wearing (cape zone)
        self.cape_prog = Counter()  # events counted toward capes: melee_hit, food, reaction, ...
        self.fresh = Counter()  # materials played this turn
        self.telegraph = {}     # Legendary put face up: name -> the turn it was revealed
        self.energy = 0         # special attack energy (player_energy rule)
        self.free_steps = 0
        self.lumbridge_used = False   # Lumbridge: has this player's deck been saved once?
        self.exchanged = False        # Grand Exchange used this turn

    # Life points. In the deck-as-life game they are the cards left in your deck: losing
    # life discards cards from the top, healing puts random cards from your discard pile
    # on the bottom (as many as there are), and an empty deck is 0 LP.
    @property
    def lp(self):
        return len(self.deck) if self.deck_life else self._lp

    @lp.setter
    def lp(self, value):
        if not self.deck_life:
            self._lp = value
            return
        delta = value - len(self.deck)
        for _ in range(-delta):                  # damage: mill from the top
            if not self.deck:
                break
            self.discard.append(self.deck.pop())
        for _ in range(min(delta, len(self.discard))):   # healing: discards to the bottom
            k = self.rng.randrange(len(self.discard))
            self.deck.insert(0, self.discard.pop(k))

    def clone(self):
        q = Player.__new__(Player)
        q.__dict__.update(self.__dict__)
        q.deck, q.hand, q.discard, q.removed = list(self.deck), list(self.hand), list(self.discard), list(self.removed)
        q.materials = Counter(self.materials)
        q.fresh = Counter(self.fresh)
        q.telegraph = dict(self.telegraph)
        q.cape_prog = Counter(self.cape_prog)
        q.completed = list(self.completed)
        for k in ("weapon", "shield", "ammo", "quest"):
            v = getattr(self, k)
            setattr(q, k, dict(v) if v else v)
        return q

    def count(self, mat, real=False):
        if mat == "Elemental Rune":                     # Round 19: any Air, Water, Earth or Fire rune
            n = sum(k for m, k in self.materials.items()
                    if k and (m in ELEMENTAL or set(ELEMENTAL) & set(CARDS[m].combo)))
            if not real and self.weapon:
                n += sum(k for m, k in CARDS[self.weapon["name"]].counts_as if m in ELEMENTAL)
            return n
        n = self.materials[mat]
        for name, k in self.materials.items():          # specialty runes count as both elements
            if k and mat in CARDS[name].combo:
                n += k
        if not real and self.weapon:
            for m, k in CARDS[self.weapon["name"]].counts_as:
                if m == mat or (m == "Elemental Rune" and mat in self.main_elements()):
                    n += k
        return n

    def main_elements(self):
        """Staff of Light (Round 19): the Air/Water/Earth/Fire runes you have the most of in play.
        Combination runes count for both their elements. On a tie you pick one each time you cast,
        which here means every tied element qualifies. With none in play it counts as nothing."""
        c = {e: self.count(e, True) for e in ELEMENTAL}
        top = max(c.values())
        return {e for e, v in c.items() if v == top and v > 0}

    def meets(self, req, real=False):
        return all(self.count(m, real) >= k for m, k in req)

    def family_count(self, fam):
        return sum(k for m, k in self.materials.items() if fam == "any" or CARDS[m].mat == fam)

    def weapon_card(self):
        return CARDS[self.weapon["name"]] if self.weapon else None


class Game:
    def __init__(self, decks, agents, seed=None, rules=None, names=("P1", "P2")):
        self.rng = random.Random(seed)
        self.rules = {**DEFAULT_RULES, **(rules or {})}
        self.players = [Player(names[i], decks[i], self.rng, self.rules["deck_life"]) for i in (0, 1)]
        self.agents = agents
        self.location = None
        self.location_owner = None
        self.turn = 0            # total turns taken; player 0 goes first
        self.active = 0
        self.winner = None
        self.end_reason = None
        self.stats = [defaultdict(int), defaultdict(int)]
        self.first_damage_turn = [None, None]
        self.bolt_ready_turn = [None, None]
        self.ran_dry = [False, False]
        self.log = []            # notable events (reactions), for replays
        self.cape_table = [c for c in dict.fromkeys(self.rules["capes"] or []) if c]   # same cape twice: one cape
        self.cape_claimed = {}   # cape -> who claimed it
        for p in self.players:
            if not p.deck_life:
                p.lp = self.rules["start_lp"]
            for _ in range(self.rules["opening_hand"]):
                p.hand.append(p.deck.pop())
        for a in agents:
            if hasattr(a, "setup"):
                a.setup(self)

    # ---------- helpers ----------
    def own_turn(self, i):
        """1-based count of player i's own turns, for the turn in progress."""
        return self.turn // 2 + 1

    def opp(self, i):
        return self.players[1 - i]

    def prayer_cost(self):
        if not self.rules["prayer_bones"]:
            return 0
        return self.rules["prayer_bones"] + (1 if self.location == "Wizards' Tower" else 0)

    # ---------- damage ----------
    def attack_bonus(self, i, style, src):
        p = self.players[i]
        extra = 0
        if p.armor and CARDS[p.armor].bonus and src not in NO_BUFF_SRC:     # Void: your attacks +1
            extra = CARDS[p.armor].bonus
        if p.buff and src not in NO_BUFF_SRC:
            c = CARDS[p.buff]
            if c.prayer == style or c.prayer == "any":     # "any": Round 22 experiment (Smite)
                return c.bonus + extra
        return extra

    def compute_damage(self, i, amount, style, ignore_armor=False, ignore_prayer=False, src=None,
                       ignore_shield=False):
        """Return (damage to LP, damage soaked by shield) without changing anything.
        Pass src to include the attacker's buff prayer (deal() adds it itself)."""
        o = self.opp(i)
        if src is not None:
            amount += self.attack_bonus(i, style, src)
        if self.location == "Wizards' Tower" and style == "magic":
            amount += 2
        if self.location == "Wilderness":
            amount += 2
        if self.location == "Mage Arena":
            amount += 2 if style == "magic" else -2 if style == "ranged" else 0
        if not ignore_armor and o.armor:
            for s, m in CARDS[o.armor].armor:
                if s == style:
                    amount += m
        if o.longrange and style == "melee":
            amount -= 2
        if o.light and style == "melee":
            amount //= 2
        if o.sanctuary and style != "none":
            amount -= o.sanctuary
        if o.cape is not None and self.rules.get("cape_guard"):     # experiment: the cape protects its wearer
            amount -= self.rules["cape_guard"]
        amount = max(0, amount)
        if not ignore_prayer and o.prayer == style:
            amount //= 2
        soaked = 0
        if o.shield and amount > 0 and not ignore_shield:
            soaked = min(amount, o.shield["soak"])
            amount -= soaked
        return amount, soaked

    def offer_reaction(self, i, amount, style, src):
        """Player 1-i may answer this attack with one reaction per round.
        Returns the card played, or None."""
        j = 1 - i
        d = self.players[j]
        rnd = self.turn // 2
        if d.reacted_round == rnd or not hasattr(self.agents[j], "react"):
            return None
        opts = []
        for name in set(d.hand):
            c = CARDS[name]
            if (c.kind == "prayer" and (c.prayer == style or self.rules.get("protect_any")) and src != "punch"
                    and self.location != "Duel Arena"
                    and (not self.prayer_cost() or d.count("Bones") >= self.prayer_cost())):
                opts.append(name)
            elif c.kind == "reversal" and c.reacts_to == style:
                opts.append(name)
        if self.rules.get("protect_discard"):       # a Protect prayer costs a second card from hand
            opts = [n for n in opts if CARDS[n].kind != "prayer" or len(d.hand) >= 2]
        if not opts:
            return None
        choice = self.agents[j].react(self, j, amount, style, src, opts)
        if not choice:
            return None
        c = CARDS[choice]
        d.hand.remove(choice)
        d.discard.append(choice)
        d.reacted_round = rnd
        self.stats[j]["reactions_" + c.kind] += 1
        d.cape_prog["reaction"] += 1
        if c.kind == "prayer" and self.rules.get("protect_discard") and d.hand:
            cost = min(d.hand, key=lambda n: self.agents[j].keep_value(self, j, n)) \
                if hasattr(self.agents[j], "keep_value") else d.hand[0]
            d.hand.remove(cost)
            d.discard.append(cost)
        if c.kind == "prayer" and self.rules.get("protect_halves"):
            self.log.append(f"{d.name} reacts with {choice}, halving a {amount}-damage {style} attack")
            if amount // 2 > 0:
                self.deal(i, amount // 2, style, src, unreactable=True,
                          no_bonus=self.rules.get("halve_fix", False))
            if self.rules.get("protect_turn"):      # Round 24 experiment: the rest of this turn's attacks of that style are halved too
                d.prayer = style
            return choice
        if c.kind == "prayer":
            self.log.append(f"{d.name} reacts with {choice}, blocking a {amount}-damage {style} attack")
            if self.rules.get("protect_turn"):
                d.prayer = style
        else:
            self.log.append(f"{d.name} reacts with {choice}: the {amount}-damage {style} attack "
                            f"comes back as {c.converts_to}")
            back = int(amount * self.rules["reversal_fraction"])
            if back > 0:
                self.deal(j, back, c.converts_to, "reversal")
        return choice

    def deal(self, i, amount, style, src, ignore_armor=False, ignore_prayer=False,
             unreactable=False, shatter=False, no_bonus=False, ignore_shield=False):
        if self.winner is not None:
            return 0
        bonus = 0 if no_bonus else self.attack_bonus(i, style, src)
        amount += bonus
        self.stats[i]["buff_bonus"] += bonus
        if amount > 0 and not unreactable and self.offer_reaction(i, amount, style, src):
            return 0
        o = self.opp(i)
        to_lp, soaked = self.compute_damage(i, amount, style, ignore_armor, ignore_prayer,
                                            ignore_shield=ignore_shield)
        if shatter and o.shield and not o.shield.get("virtual") and not CARDS[o.shield["name"]].unshatter:
            soaked = o.shield["soak"] if soaked else soaked
        if o.shield and soaked:
            o.shield["soak"] -= soaked
            if o.shield["soak"] <= 0:
                if not o.shield.get("virtual"):
                    o.discard.append(o.shield["name"])
                o.shield = None
                self.stats[1 - i]["shields_broken"] += 1
        if (self.location == "Lumbridge" and o.deck_life and to_lp >= o.lp > 0
                and not o.lumbridge_used):     # Lumbridge: keep the last card, once per player
            o.lumbridge_used = True
            to_lp = o.lp - 1
            self.stats[1 - i]["lumbridge_saves"] += 1
            self.log.append(f"Lumbridge saves {o.name}'s last card")
        o.lp -= to_lp
        self.stats[i]["dmg_" + src] += to_lp
        if to_lp > 0:
            self.players[i].cape_prog[style + "_hit"] += 1
        self.stats[1 - i]["soaked"] += soaked
        o.cape_prog["soak"] += soaked
        if (to_lp or soaked) and self.first_damage_turn[i] is None:
            self.first_damage_turn[i] = self.own_turn(i)
        if o.lp <= 0:
            self.winner = i
            self.end_reason = "damage"
            return to_lp
        p = self.players[i]
        if to_lp and p.buff and CARDS[p.buff].effect == "smite" and o.buff:
            o.discard.append(o.buff)       # Smite: damage ends the opponent's prayer
            o.buff = None
            self.stats[i]["smited"] += 1
        if to_lp and o.buff and CARDS[o.buff].effect == "redemption" and o.lp <= 10:
            o.lp = min(self.rules["max_lp"], o.lp + CARDS[o.buff].reward_val)
            self.log.append(f"{o.name}'s Redemption heals {CARDS[o.buff].reward_val}")
            self.stats[1 - i]["redeemed"] += 1
            o.discard.append(o.buff)
            o.buff = None
        return to_lp

    @staticmethod
    def _lose_material(p, name):
        p.materials[name] -= 1
        if p.materials[name] <= 0:
            del p.materials[name]
        p.discard.append(name)
        Game.check_buff(p)

    @staticmethod
    def check_buff(p):
        """A buff prayer ends when its Bones are no longer in play."""
        if p.buff and not p.meets(CARDS[p.buff].req, real=True):
            p.discard.append(p.buff)
            p.buff = None

    def energy(self, p):
        return p.energy if self.rules["player_energy"] else p.weapon.get("energy", 0)

    def melee_hit(self, i, src="weapon"):
        p = self.players[i]
        c = p.weapon_card()
        self.deal(i, c.hit, "melee", src)
        self.wear(i)

    def autocast(self, i):
        c = self.players[i].weapon_card()
        self.deal(i, c.hit, "magic", "autocast")
        self.wear(i)

    def wear(self, i):
        p = self.players[i]
        p.weapon["dur"] -= 1
        if p.weapon["dur"] <= 0:
            self._unequip(p)
            self.stats[i]["weapons_broken"] += 1

    def wear_opponent(self, i, k):
        """Earth spells and Crumble: the opponent's weapon loses durability, or their ammo loses shots."""
        o = self.opp(i)
        if o.weapon and o.weapon["dur"]:
            for _ in range(k):
                if o.weapon:
                    self.wear(1 - i)
                    self.stats[i]["durability_removed"] += 1
        elif o.ammo:
            o.ammo["shots"] -= k
            self.stats[i]["durability_removed"] += k
            if o.ammo["shots"] <= 0:
                if o.ammo["card"]:
                    o.discard.append(o.ammo["card"])
                o.ammo = None

    def throw(self, i, mod=0, src="thrown"):
        c = self.players[i].weapon_card()
        self.deal(i, c.hit + mod, "ranged", src)
        self.wear(i)

    def swing_options(self, p):
        w = p.weapon_card()
        if not w:
            return []
        if w.legendary and self.rules["legend_telegraph"] == "sick" and p.weapon.get("played") == self.turn:
            return []                              # Telegraph: can't attack the turn it's played
        if w.kind == "bow":
            opts = ["ranged"] if p.ammo else []
            if w.bash_hit and p.weapon["dur"]:
                opts.append("melee")
            return opts
        if not p.weapon["dur"]:
            return []
        if w.kind == "thrown":
            return ["ranged"]
        if w.kind == "staff":
            return ["magic"]
        return list(w.styles or ("melee",))

    def do_swing(self, i, style):
        p = self.players[i]
        w = p.weapon_card()
        if w.kind == "bow":
            if style == "ranged":
                self.shoot(i)
            else:
                self.deal(i, w.bash_hit, "melee", "weapon")
                self.wear(i)
        elif w.kind == "thrown":
            self.throw(i)
        elif w.kind == "staff":
            self.autocast(i)
        else:
            for _ in range(w.hits):
                self.deal(i, w.hit, style, "weapon", shatter=w.legendary)
            if p.weapon:
                self.wear(i)

    def shoot(self, i, mod=0, src="bow", ignore_armor=False, unreactable=False):
        p = self.players[i]
        if not p.ammo or p.ammo["shots"] <= 0:
            return
        bow = p.weapon_card()
        dmg = bow.hit + p.ammo["bonus"] + mod
        p.ammo["shots"] -= 1
        if p.ammo["shots"] <= 0:
            if p.ammo["card"]:
                p.discard.append(p.ammo["card"])
            p.ammo = None
        self.deal(i, dmg, "ranged", src, ignore_armor=ignore_armor, unreactable=unreactable)

    # ---------- drawing ----------
    def draw(self, i):
        p = self.players[i]
        if not p.deck:
            if p.respawns < self.rules["max_respawns"] and p.discard:
                p.deck = p.discard
                p.discard = []
                self.rng.shuffle(p.deck)
                p.respawns += 1
                p.lp -= self.rules["respawn_cost"]
                if p.quest:
                    p.discard.append(p.quest["name"])
                    p.quest = None
                self.stats[i]["respawns"] += 1
                if p.lp <= 0:
                    self.winner, self.end_reason = 1 - i, "respawn_cost"
                    return
            else:
                self.winner, self.end_reason = 1 - i, "deck_out"
                return
        p.hand.append(p.deck.pop())

    # ---------- legal actions ----------
    def legal_actions(self, i):
        p, o = self.players[i], self.opp(i)
        no_damage = self.turn == 0
        acts = [("draw",)]
        if not no_damage:
            acts.append(("punch",))
            if self.rules["swing_action"]:
                acts.extend(("swing", s) for s in self.swing_options(p))
                if p.free_swing_ready:
                    acts.extend(("free_swing", s) for s in self.swing_options(p))
            if self.can_spec(p):
                acts.append(("spec",))
        acts.extend(self.quest_actions(p))
        acts.extend(("claim", c) for c in self.claimable(i))
        for name, t in p.telegraph.items():        # equip a revealed Legendary on a later turn
            c = CARDS[name]
            if self.turn > t and all(p.family_count(f) >= n for f, n in c.fam_req) \
                    and not (c.equip_swing and no_damage):
                acts.append(("legend", name))
        for name in set(p.hand):
            acts.extend(self._card_actions(i, CARDS[name], no_damage))
        if self.location == "Varrock" and p.lp < self.rules["max_lp"]:
            acts.extend(("varrock", n) for n in set(p.hand))      # discard a card, heal 3
        if self.location == "Grand Exchange" and not p.exchanged and p.deck:
            acts.extend(("exchange", n) for n in set(p.hand))     # free: discard a card, draw one
        return acts

    def _card_actions(self, i, c, no_damage):
        p, o = self.players[i], self.opp(i)
        k, n = c.kind, c.name
        play = [("play", n, None)]
        if k == "material":
            return play
        if k == "spell":
            return play if (not no_damage or not c.damage) and p.meets(c.req) else []
        if k == "teleport":
            return play
        if k == "hammer":
            w = p.weapon_card()
            t = []
            if w and not w.legendary and p.weapon["dur"]:
                t.append(("play", n, "weapon"))
            if p.shield and not p.shield.get("virtual"):
                t.append(("play", n, "shield"))
            return t
        if k in ("weapon", "bow", "thrown"):
            ok = not no_damage or self.rules["swing_action"]
            if c.equip_swing and no_damage:
                ok = False
            if c.expend and any(p.materials[m] - p.fresh[m] < n for m, n in c.expend):
                ok = False
            if c.legendary and self.rules["legend_telegraph"] == "reveal":
                return play                       # revealing it needs nothing; equipping it later does
            if c.fam_req and any(p.family_count(f) < n for f, n in c.fam_req):
                ok = False
            return play if ok and p.meets(c.req) else []
        if k == "staff":
            if self.rules["staff_autocast"] and no_damage:
                return []
            return play if p.meets(c.req, real=True) else []
        if k == "arrows":
            w = p.weapon_card()
            return play if w and w.kind == "bow" and c.arrow_tier <= w.arrow_tier else []
        if k == "ability":
            w = p.weapon_card()
            return play if not no_damage and w and w.kind == "weapon" and w.wclass in c.allowed else []
        if k in ("rapid", "longrange"):
            w = p.weapon_card()
            loaded = w and ((w.kind == "bow" and p.ammo) or (w.kind == "thrown" and p.weapon["dur"]))
            return play if not no_damage and loaded else []
        if k == "destroy":
            if not p.meets(c.req):
                return []
            if c.needs_melee_weapon and not (p.weapon and p.weapon_card().kind == "weapon"):
                return []
            acts = [("play", n, t) for t in o.materials if o.materials[t] > 0
                    and CARDS[t].mat == c.destroys]
            if not acts and c.damage:            # Round 25: a destroy card with damage is never a blank
                acts = [("play", n, None)]
            return acts
        if k == "pouch":
            return [("play", n, t) for t in set(p.deck) if CARDS[t].mat == "rune"]
        if k == "armor":
            if c.families and len({CARDS[m].mat for m in p.materials}) < c.families:
                return []
            if c.fam_req and any(p.family_count(f) < n for f, n in c.fam_req):   # Round 22 experiment
                return []
            return play if p.meets(c.req) else []
        if k == "switch":
            w = p.weapon_card()
            if not w or no_damage and not self.rules["swing_action"]:
                return []
            cur = set(w.styles or (w.style,))
            return [("play", n, t) for t in set(p.hand) if CARDS[t].kind in ("weapon", "thrown", "bow", "staff")
                    and not ({CARDS[t].style} | set(CARDS[t].styles)) <= cur
                    and (p.meets(CARDS[t].req, real=CARDS[t].kind == "staff"))]
        if k == "shield":
            w = p.weapon_card()
            return [] if w and w.two_handed else play
        if k == "food":
            if self.location == "Duel Arena":
                return []
            if p.lp >= self.rules["max_lp"] or (p.deck_life and not p.discard):
                return []
            used = p.ate_karambwan if c.stacks_with_food else p.ate_food
            return [] if used else play
        if k in ("prayer", "reversal"):
            return []                       # reactions are only played in answer to an attack
        if k == "npc":
            return [("play", n, t) for t in set(p.discard) if self._recoverable(c, CARDS[t])]
        if k == "location":
            return play if self.location != n else []
        if k == "buff":
            if self.location == "Duel Arena":
                return []
            return play if p.meets(c.req) and p.buff != n else []
        if k == "quest":     # starting a new quest abandons the one in progress
            return play if n not in p.completed and (not p.quest or p.quest["name"] != n) else []
        if k == "cape":
            return play if p.qp >= self.rules["cape_qp"] and p.cape is None else []
        return []

    # ---------- special attacks ----------
    def can_spec(self, p):
        w = p.weapon_card()
        if not w or not w.spec or self.energy(p) < w.spec_cost:
            return False
        if w.legendary and self.rules["legend_telegraph"] == "sick" and p.weapon.get("played") == self.turn:
            return False
        if w.spec in FREE_SPECS:
            return not p.weapon.get("spec_used")
        if w.kind == "bow":
            return bool(p.ammo)
        return bool(p.weapon["dur"])

    def do_spec(self, i):
        p, o = self.players[i], self.opp(i)
        w = p.weapon_card()
        if self.rules["player_energy"]:
            p.energy -= w.spec_cost
        else:
            p.weapon["energy"] -= w.spec_cost
        self.stats[i]["specs"] += 1
        s = w.spec
        if s == "sanctuary":
            p.sanctuary = w.spec_val
            p.weapon["spec_used"] = True
        elif s == "light":
            p.light = True
            p.weapon["spec_used"] = True
        elif s == "war_god":
            self.deal(i, w.hit, "melee", "spec")
            if o.buff:                     # Favour of the War God: ends the opponent's prayer
                o.discard.append(o.buff)
                o.buff = None
            self.wear(i)
        elif s == "sunder":
            self.deal(i, w.hit + w.spec_val, "melee", "spec", shatter=True)
            self.wear(i)
        elif s == "snapshot":
            self.shoot(i, src="spec")
            if p.ammo:
                self.shoot(i, src="spec")
        elif s == "soulshot":
            self.shoot(i, mod=w.spec_val, src="spec", ignore_armor=True, unreactable=True)
        elif s == "iban":
            self.deal(i, w.spec_val, "magic", "spec")
            self.wear(i)
        elif s == "lightning":
            self.deal(i, w.hit, "melee", "spec")
            self.deal(i, w.spec_val, "magic", "spec")
            if p.weapon:
                self.wear(i)

    # ---------- quests ----------
    def quest_actions(self, p):
        if not p.quest:
            return []
        kind, arg = CARDS[p.quest["name"]].steps[p.quest["step"]]
        if kind == "discard":
            return [("quest", n) for n in set(p.hand)
                    if arg is None or CARDS[n].kind == arg]
        if kind == "spend":
            return [("quest", m) for m in p.materials]
        ok = {"have": lambda: p.family_count(arg[0]) >= arg[1],
              "weapon": lambda: p.weapon is not None,
              "armor": lambda: p.armor is not None,
              "shield": lambda: p.shield is not None}[kind]()
        return [("quest", None)] if ok else []

    def do_quest_step(self, i, target):
        p = self.players[i]
        c = CARDS[p.quest["name"]]
        kind, _ = c.steps[p.quest["step"]]
        if kind == "discard":
            p.hand.remove(target)
            p.discard.append(target)
        elif kind == "spend":
            self._lose_material(p, target)
        p.quest["step"] += 1
        self.stats[i]["quest_steps"] += 1
        if p.quest["step"] >= len(c.steps):
            p.completed.append(c.name)
            p.removed.append(c.name)
            p.quest = None
            p.qp += c.qp
            self.stats[i]["quests_done"] += 1
            self.log.append(f"{p.name} completes {c.name} ({p.qp} QP)")
            self.quest_reward(i, c)

    def _fetch(self, p, fam, n):
        for _ in range(n):
            pick = [m for m in p.deck if CARDS[m].kind == "material" and CARDS[m].mat == fam]
            if fam == "bones":
                pick += [m for m in p.discard if m == "Bones"]
            if not pick:
                return
            m = pick[0]
            (p.deck if m in p.deck else p.discard).remove(m)
            p.materials[m] += 1
        self.rng.shuffle(p.deck)

    def quest_reward(self, i, c):
        p, o = self.players[i], self.opp(i)
        e = c.effect
        if e == "heal":
            p.lp = min(self.rules["max_lp"], p.lp + c.reward_val)
        elif e == "draw":
            for _ in range(c.reward_val):
                if p.deck:
                    p.hand.append(p.deck.pop())
        elif e == "fetch_rune":
            self._fetch(p, "rune", c.reward_val)
        elif e == "fetch_bar":
            self._fetch(p, "bar", c.reward_val)
        elif e == "fetch_prayer":          # search your deck for a prayer, into your hand
            pick = [n for n in p.deck if CARDS[n].kind in ("buff", "prayer")]
            if pick:
                best = max(pick, key=lambda n: CARDS[n].bonus)
                p.deck.remove(best)
                p.hand.append(best)
                self.rng.shuffle(p.deck)
        elif e == "lose_action":
            o.lost_actions = c.reward_val
        elif e == "damage":
            self.deal(i, c.reward_val, "none", "quest", ignore_armor=True, unreactable=True)
        elif e == "chicken":
            if o.weapon:
                self._unequip(o)
        elif e == "silverlight":       # search your deck for a weapon and equip it, ignoring requirements
            ws = [n for n in p.deck if CARDS[n].kind in ("weapon", "thrown", "bow", "staff")]
            if ws:
                best = max(ws, key=lambda n: CARDS[n].hit * max(1, CARDS[n].durability))
                p.deck.remove(best)
                p.hand.append(best)
                self._equip(i, best)
                self.rng.shuffle(p.deck)
        elif e == "dragon_slayer":     # search your deck for armor and a shield; both into play
            for kind in ("armor", "shield"):
                pick = [n for n in p.deck if CARDS[n].kind == kind]
                if pick:
                    p.deck.remove(pick[0])
                    p.hand.append(pick[0])
                    self._equip(i, pick[0])
            self.rng.shuffle(p.deck)

    def _equip(self, i, name):
        """Put an equipment card from hand into play without the usual checks."""
        self.players[i].hand.remove(name)
        self._apply_card(i, name, None)

    @staticmethod
    def _recoverable(npc, card):
        for spec in npc.recovers:
            if spec == "any":
                return True
            if spec == "item" and card.item:
                return True
            if card.kind == "material" and card.mat == spec:
                return True
            if card.kind == spec:
                return True
        return False

    # ---------- applying actions ----------
    def apply(self, i, action):
        p, o = self.players[i], self.opp(i)
        if action[0] == "draw":
            self.stats[i]["draw_actions"] += 1
            self.draw(i)
            return
        if action[0] in ("swing", "free_swing"):
            self.stats[i][action[0] + "s"] += 1
            if action[0] == "free_swing":
                p.free_swing_ready, p.free_swing_used = False, True
            self.do_swing(i, action[1])
            return
        if action[0] == "punch":
            self.stats[i]["punches"] += 1
            self.deal(i, self.rules["punch_damage"], "melee", "punch",
                      ignore_armor=True, ignore_prayer=True)
            return
        if action[0] == "spec":
            self.do_spec(i)
            return
        if action[0] == "quest":
            self.do_quest_step(i, action[1])
            return
        if action[0] == "claim":
            self.cape_claimed[action[1]] = i
            p.cape, p.cape_name = 0, action[1]
            self.stats[i]["cape_claimed"] += 1
            self.stats[i]["cape_claim_turn"] = self.own_turn(i)
            if action[1] != (self.rules["capes"] or [None, None])[i]:
                self.stats[i]["cape_stolen"] += 1
            self.log.append(f"{p.name} claims the {action[1]}")
            return
        if action[0] == "varrock":
            p.hand.remove(action[1])
            p.discard.append(action[1])
            gain = min(3, self.rules["max_lp"] - p.lp)
            p.lp += gain
            self.stats[i]["healed"] += gain
            self.stats[i]["varrock"] += 1
            return
        if action[0] == "exchange":
            p.exchanged = True
            p.hand.remove(action[1])
            p.discard.append(action[1])
            self.stats[i]["exchanges"] += 1
            for _ in range(max(1, CARDS["Grand Exchange"].draws)):   # Round 22 experiment: draws > 1
                if self.winner is None:
                    self.draw(i)
            return
        if action[0] == "legend":
            del p.telegraph[action[1]]
            self.stats[i]["legend_equipped"] += 1
            self._apply_card(i, action[1], "equip")
            return

        _, name, target = action
        p.hand.remove(name)
        self._apply_card(i, name, target)

    def _apply_card(self, i, name, target):
        p, o = self.players[i], self.opp(i)
        c = CARDS[name]
        k = c.kind
        self.stats[i]["played_" + k] += 1

        if k == "material":
            p.materials[name] += 1
            p.fresh[name] += 1
        elif k == "spell":
            if c.damage:
                self.deal(i, c.damage, "magic", "spell", shatter=c.burns == "shatter",
                          ignore_shield=c.burns == "pierce")
            if c.wears:
                self.wear_opponent(i, c.wears)
            if c.heals and self.winner is None:
                gain = min(c.heals, self.rules["max_lp"] - p.lp)
                p.lp += gain
                self.stats[i]["healed"] += max(0, gain)
            p.discard.append(name)
            if c.fetch:
                self._teleport(i, c.fetch)
            for _ in range(c.draws):
                if p.deck:
                    p.hand.append(p.deck.pop())
            w = p.weapon_card()
            if w and w.free_swing_after_spell and not p.free_swing_used:
                p.free_swing_ready = True
        elif k == "weapon" and c.legendary and self.rules["legend_telegraph"] == "reveal" and target != "equip":
            p.telegraph[name] = self.turn          # Telegraph: face up now, equip on a later turn
            self.log.append(f"{p.name} reveals {name}")
        elif k == "weapon":
            self._unequip(p)
            for m, n in c.expend:                 # Legendary materials are removed from the game
                for _ in range(n):
                    p.materials[m] -= 1
                    p.removed.append(m)
                if p.materials[m] <= 0:
                    del p.materials[m]
                self.stats[i]["expended"] += n
            if c.two_handed and p.shield and not p.shield.get("virtual"):
                p.discard.append(p.shield["name"])
                p.shield = None
            p.weapon = {"name": name, "dur": c.durability, "energy": 0, "played": self.turn}
            if c.legendary and self.rules["legend_telegraph"] == "sick":
                self.stats[i]["legend_equipped"] += 1
            if c.equip_swing:
                self.do_swing(i, (c.styles or ("melee",))[0])
            if not self.rules["swing_action"]:
                self.melee_hit(i)
        elif k == "thrown":
            self._unequip(p)
            p.weapon = {"name": name, "dur": c.durability}
        elif k == "staff":
            self._unequip(p)
            if self.rules["swing_action"]:
                p.weapon = {"name": name, "dur": c.durability, "energy": 0}
            elif self.rules["staff_autocast"]:
                p.weapon = {"name": name, "dur": c.durability}
                self.autocast(i)
            else:
                p.weapon = {"name": name, "dur": None}
        elif k == "bow":
            self._unequip(p)
            p.weapon = {"name": name, "dur": c.durability if c.bash_hit else None, "energy": 0}
            if c.two_handed:
                if p.shield and not p.shield.get("virtual"):
                    p.discard.append(p.shield["name"])
                p.shield = {"name": name, "soak": c.soak, "virtual": True}
            if not p.ammo or p.ammo["tier"] > c.arrow_tier:
                if p.ammo and p.ammo["card"]:
                    p.discard.append(p.ammo["card"])
                p.ammo = {"card": None, "bonus": 0, "shots": self.rules["bow_shots"], "tier": 1}
            if not self.rules["swing_action"]:
                self.shoot(i)
        elif k == "arrows":
            if p.ammo and p.ammo["card"]:
                p.discard.append(p.ammo["card"])
            p.ammo = {"card": name, "bonus": c.arrow_bonus, "shots": self.rules["arrow_shots"], "tier": c.arrow_tier}
        elif k == "ability":
            w = p.weapon_card()
            self.deal(i, w.hit + c.damage, "melee", "ability", ignore_armor=c.ignores_armor)
            for _ in range(c.durability or 1):     # Crush uses 2 durability
                if p.weapon:
                    self.wear(i)
            p.discard.append(name)
        elif k == "rapid":                       # two shots (or throws) for one action
            for _ in range(2 if self.rules["swing_action"] else 1):
                if p.weapon and p.weapon_card().kind == "thrown":
                    self.throw(i, src="ability")
                elif p.weapon:
                    self.shoot(i, src="ability")
            p.discard.append(name)
        elif k == "longrange":
            if p.weapon_card().kind == "thrown":
                self.throw(i, mod=-1, src="ability")
            else:
                self.shoot(i, mod=-1, src="ability")
            p.longrange = True
            p.discard.append(name)
        elif k == "destroy":
            if c.damage:
                self.deal(i, c.damage, c.style if c.style in ("melee", "ranged", "magic") else "none", "spell")
            for _ in range(c.destroy_n):
                if target is None or not o.materials.get(target):
                    targets = [t for t in o.materials if o.materials[t] > 0 and CARDS[t].mat == c.destroys]
                    if not targets:
                        break
                    target = max(targets, key=lambda t: o.materials[t])
                o.materials[target] -= 1
                if o.materials[target] <= 0:
                    del o.materials[target]
                o.discard.append(target)
                self.stats[i]["destroyed"] += 1
                target = None if not o.materials.get(target or "", 0) else target
            p.discard.append(name)
        elif k == "pouch":
            p.deck.remove(target)
            p.materials[target] += 1
            self.rng.shuffle(p.deck)
            p.discard.append(name)
        elif k == "armor":
            if p.armor:
                p.discard.append(p.armor)
            p.armor = name
        elif k == "shield":
            if p.shield:
                p.discard.append(p.shield["name"])
            p.shield = {"name": name, "soak": c.soak}
        elif k == "food":
            gain = min(c.heal, self.rules["max_lp"] - p.lp)
            p.lp += gain
            p.cape_prog["food"] += 1
            self.stats[i]["healed"] += gain
            if c.stacks_with_food:
                p.ate_karambwan = True
            else:
                p.ate_food = True
            p.discard.append(name)
        elif k == "prayer":
            p.prayer = c.prayer
            p.discard.append(name)
        elif k == "npc":
            p.discard.remove(target)
            if CARDS[target].kind == "material":
                p.materials[target] += 1
            else:
                p.hand.append(target)
            p.discard.append(name)
        elif k == "teleport":
            p.discard.append(name)
            self._teleport(i, c.fetch)
        elif k == "hammer":
            if target == "weapon":
                p.weapon["dur"] += c.bonus
            else:
                p.shield["soak"] += c.bonus
            p.discard.append(name)
        elif k == "location":
            if self.location:
                self.players[self.location_owner].discard.append(self.location)
            self.location, self.location_owner = name, i
            if name == "Duel Arena":           # no prayers: active buff prayers end
                for q in self.players:
                    if q.buff:
                        q.discard.append(q.buff)
                        q.buff = None
        elif k == "switch":
            p.discard.append(name)
            p.hand.remove(target)
            self._apply_card(i, target, None)
        elif k == "buff":
            if p.buff:
                p.discard.append(p.buff)
            p.buff, p.buff_new = name, True
            p.buff_left = c.duration
            if c.duration:
                p.cape_prog["elite_prayer"] += 1
        elif k == "quest":
            if p.quest:
                p.discard.append(p.quest["name"])
                self.stats[i]["quests_abandoned"] += 1
            p.quest = {"name": name, "step": 0}
        elif k == "cape":
            if self.rules["cape_turns"]:
                p.cape = 0                  # counters go on at the start of each of your turns
                self.log.append(f"{p.name} puts on the Quest Point Cape")
            else:
                self.winner, self.end_reason = i, "quest_cape"

    def _teleport(self, i, loc):
        """Play a location from your deck (Varrock Teleport). Nothing if it isn't there or is in play."""
        p = self.players[i]
        if loc in p.deck and self.location != loc:
            p.deck.remove(loc)
            self.rng.shuffle(p.deck)
            self.stats[i]["teleports"] += 1
            self._apply_card(i, loc, None)

    @staticmethod
    def _unequip(p):
        if p.weapon:
            (p.removed if CARDS[p.weapon["name"]].legendary else p.discard).append(p.weapon["name"])
            if p.shield and p.shield.get("virtual"):
                p.shield = None
            p.weapon = None

    # ---------- turn loop ----------
    def play_turn(self):
        i = self.active
        p = self.players[i]
        p.prayer = None
        p.longrange = False
        p.ate_food = p.ate_karambwan = False
        p.free_swing_ready = p.free_swing_used = False
        p.sanctuary, p.light = 0, False
        p.free_steps = 0
        p.exchanged = False
        p.fresh = Counter()
        if p.cape is not None:
            p.cape += 1
            if p.cape >= self.rules["cape_turns"]:
                self.winner, self.end_reason = i, "cape" if p.cape_name else "quest_cape"
                return
        if self.rules["player_energy"]:
            p.energy = min(self.rules["max_energy"], p.energy + 1)
        if p.weapon:
            w = p.weapon_card()
            p.weapon.pop("spec_used", None)
            if w.spec and not self.rules["player_energy"]:
                p.weapon["energy"] = min(self.rules["max_energy"], p.weapon.get("energy", 0) + 1)
        if p.buff and (self.rules.get("elite_fix") or not p.buff_new) and getattr(p, "buff_left", 0):
            p.buff_left -= 1
            if p.buff_left <= 0:            # elite prayers last 3 of your turns
                p.discard.append(p.buff)
                p.buff = None
        if p.buff and not p.buff_new and self.rules["buff_upkeep"]:
            if p.materials.get("Bones", 0) >= self.rules["buff_upkeep"]:
                for _ in range(self.rules["buff_upkeep"]):
                    self._lose_material(p, "Bones")
            else:
                p.discard.append(p.buff)
                p.buff = None
        p.buff_new = False

        w = p.weapon_card() if not self.rules["swing_action"] else None
        if w and w.kind == "weapon":
            self.melee_hit(i)
        elif w and w.kind == "bow":
            self.shoot(i)
        elif w and w.kind == "staff" and self.rules["staff_autocast"]:
            self.autocast(i)
        if self.winner is not None:
            return

        if self.turn > 0:                 # first player skips the turn-1 draw
            self.draw(i)
            if self.winner is not None:
                return

        actions, free_plays = self.rules["actions"] - p.lost_actions, 0
        p.lost_actions = 0
        self.resume_turn(actions, free_plays)

    def resume_turn(self, actions, free_plays):
        """The action phase and end of the active player's turn, from any point in it."""
        i = self.active
        p = self.players[i]
        while actions > 0 and self.winner is None:
            self.actions_left, self.free_plays_used = actions, free_plays   # for agents that plan
            a = self.agents[i].choose(self, i, self.legal_actions(i))
            actions, free_plays = self.spend(i, a, actions, free_plays)
            self.apply(i, a)
        if self.winner is not None:
            return

        while len(p.hand) > self.rules["hand_max"]:
            name = self.agents[i].discard_choice(self, i)
            p.hand.remove(name)
            p.discard.append(name)
            self.stats[i]["hand_discards"] += 1

        self._end_of_turn_checks(i)
        self.turn += 1
        self.active = 1 - i

    def is_free(self, i, a, free_plays):
        """Does this action leave the turn's action count alone? (Called before apply.)"""
        p = self.players[i]
        if a[0] == "quest" and p.free_steps < self.rules["free_quest_steps"]:
            return True
        if a[0] == "exchange":
            return True
        if free_plays >= 3:
            return False
        if a[0] == "free_swing":
            return True
        if a[0] == "spec":
            return p.weapon_card().spec in FREE_SPECS
        if a[0] == "play":
            c = CARDS[a[1]]
            return c.free or (c.kind == "buff" and self.rules.get("buff_free", False))
        return False

    def spend(self, i, a, actions, free_plays):
        """Account for action a: returns the new (actions left, free plays used)."""
        p = self.players[i]
        if a[0] == "quest" and p.free_steps < self.rules["free_quest_steps"]:
            p.free_steps += 1
            return actions, free_plays
        if a[0] == "exchange":
            return actions, free_plays        # Grand Exchange: free, once per turn, outside the cap
        if self.is_free(i, a, free_plays):
            return actions, free_plays + 1
        return actions - 1, free_plays

    # ---------- copying (for agents that search) ----------
    def clone(self, view=None, rng=None):
        """A copy of the game to try moves on. With view=i, hidden information is
        resampled from player i's point of view: i's deck order, and the opponent's
        hand and deck (pooled and redealt), so a search can't peek."""
        import copy
        g = Game.__new__(Game)
        g.__dict__.update(self.__dict__)
        g.__dict__.pop("apply", None)        # a replay's narrating wrapper stays on the real game
        g.rng = rng or random.Random(self.rng.random())
        g.players = [p.clone() for p in self.players]
        for p in g.players:
            p.rng = g.rng
        g.stats = [defaultdict(int), defaultdict(int)]
        g.first_damage_turn = list(self.first_damage_turn)
        g.bolt_ready_turn = list(self.bolt_ready_turn)
        g.ran_dry = list(self.ran_dry)
        g.cape_claimed = dict(self.cape_claimed)
        g.log = []
        if view is not None:
            me, op = g.players[view], g.players[1 - view]
            g.rng.shuffle(me.deck)
            pool = op.hand + op.deck
            g.rng.shuffle(pool)
            k = len(op.hand)
            op.hand, op.deck = pool[:k], pool[k:]
        return g

    def claimable(self, i):
        from capes import progress
        p = self.players[i]
        if p.cape is not None:
            return []
        return [c for c in self.cape_table if c not in self.cape_claimed
                and progress(p, c, self.rules["cape_need"])[0] >= progress(p, c, self.rules["cape_need"])[1]]

    def _end_of_turn_checks(self, i):
        p = self.players[i]
        if self.bolt_ready_turn[i] is None and p.meets(CARDS["Air Bolt"].req):
            self.bolt_ready_turn[i] = self.own_turn(i)
        if not self.ran_dry[i]:
            left = any(CARDS[n].kind in DAMAGE_KINDS for n in p.deck + p.hand)
            w = p.weapon_card()
            armed = w and ((w.kind == "bow" and p.ammo) or (w.kind != "bow" and p.weapon["dur"]))
            if not left and not armed:
                self.ran_dry[i] = True

    def run(self, max_turns=400):
        while self.winner is None and self.turn < max_turns:
            self.play_turn()
        if self.winner is None:
            self.end_reason = "timeout"
        return self.winner
