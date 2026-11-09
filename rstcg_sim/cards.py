"""Set 1 card data and starter decks for the RuneScape TCG.

Every number here mirrors the rules doc (Round 4 tuned numbers, armor weaknesses +3). Change a value here and the
simulator (and any future game built on the engine) picks it up.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Card:
    name: str
    kind: str                 # material, spell, weapon, thrown, staff, bow, arrows, ability, reversal,
                              # rapid, longrange, destroy, pouch, armor, shield, food,
                              # prayer, npc, location
    style: str = "neutral"    # melee, ranged, magic, neutral
    req: tuple = ()           # ((material name, count), ...) that must be in play
    mat: str = ""             # material family: rune, bar, log, cloth, leather, bones
    hit: int = 0              # weapons and bows: damage per hit or shot
    durability: int = 0       # melee weapons
    wclass: str = ""          # dagger, sword, scimitar, mace, staff
    allowed: tuple = ()       # abilities: weapon classes they work with
    arrow_tier: int = 0       # bows: best arrow tier; arrows: their tier
    arrow_bonus: int = 0      # arrows: damage added per shot
    damage: int = 0           # spells: damage; abilities: bonus on top of the hit
    ignores_armor: bool = False
    armor: tuple = ()         # ((style, modifier to damage taken), ...)
    soak: int = 0             # shields
    free: bool = False        # playing it doesn't use an action
    counts_as: tuple = ()     # staves: ((material name, count), ...)
    prayer: str = ""          # style the prayer protects from
    heal: int = 0
    stacks_with_food: bool = False
    recovers: tuple = ()      # NPCs: material families, card kinds, or "item"
    destroys: str = ""        # material family it destroys
    needs_melee_weapon: bool = False
    item: bool = False        # recoverable by the Banker
    legendary: bool = False
    combo: tuple = ()         # specialty runes: the two runes it counts as, at once
    styles: tuple = ()        # hybrid weapons: styles a swing may use
    bash_hit: int = 0         # Shieldbow: melee swing damage (uses durability)
    two_handed: bool = False  # fills the Shield slot too
    free_swing_after_spell: bool = False   # Magical Longsword
    wears: int = 0            # spells: opponent's weapon loses this much durability (or ammo shots)
    draws: int = 0            # spells: draw this many cards after casting
    reacts_to: str = ""       # reversals: the attack style they answer
    converts_to: str = ""     # reversals: the style the attack comes back as
    bonus: int = 0            # buff prayers: added to each attack of their style
    effect: str = ""          # buff prayers (smite, redemption), quest rewards
    spec: str = ""            # special attack effect
    spec_cost: int = 0        # energy the special attack needs
    spec_val: int = 0         # special attack number (extra damage, etc.)
    qp: int = 0               # quests: quest points
    steps: tuple = ()         # quests: ((kind, arg), ...)
    reward_val: int = 0       # quests: reward number
    duration: int = 0         # buff prayers: your turns it lasts, counting the one you play it (0: until replaced)
    families: int = 0         # needs materials of at least this many different families in play
    hits: int = 1             # weapons: hits per swing (Dragon Dagger, Dark Bow)
    equip_swing: bool = False # weapons: equipping also swings (Granite Maul)
    expend: tuple = ()        # Legendary: ((Legendary material, count), ...) removed from the game to equip
    fam_req: tuple = ()       # ((family, count), ...) of any tier in play ("3 bars in play")
    burns: str = ""           # Round 20 experiment, spells: "shatter" (a shield that blocks it is destroyed) or "pierce" (shields soak none of it)
    destroy_n: int = 1        # Round 25 experiment, destroy cards: materials destroyed per play
    heals: int = 0            # spells: you heal this much on casting (Water)
    fetch: str = ""           # teleports: the location played from your deck
    unshatter: bool = False   # shields: Shatter doesn't break it (Anti-dragon Shield)


def _mat(name, family):
    return Card(name, "material", mat=family)


_ALL = [
    # Materials
    _mat("Air Rune", "rune"), _mat("Chaos Rune", "rune"), _mat("Death Rune", "rune"),
    _mat("Cloth", "cloth"), _mat("Leather", "leather"),
    _mat("Bronze Bar", "bar"), _mat("Mithril Bar", "bar"),
    _mat("Normal Logs", "log"), _mat("Willow Logs", "log"),

    Card("Fire Rune", "material", mat="rune"),
    Card("Earth Rune", "material", mat="rune"),
    Card("Dust Rune", "material", mat="rune", combo=("Air Rune", "Earth Rune")),
    Card("Earth Strike", "spell", "magic", req=(("Earth Rune", 1),), damage=5, wears=1),
    Card("Earth Bolt", "spell", "magic", req=(("Chaos Rune", 1), ("Earth Rune", 2)), damage=9, wears=1),
    Card("Crumble", "spell", "magic", req=(("Chaos Rune", 1), ("Earth Rune", 2)), damage=0, wears=2),
    Card("Smoke Rune", "material", mat="rune", combo=("Air Rune", "Fire Rune")),

    # Magic
    Card("Fire Strike", "spell", "magic", req=(("Fire Rune", 2),), damage=8),
    Card("Fire Bolt", "spell", "magic", req=(("Chaos Rune", 1), ("Fire Rune", 3)), damage=12),
    Card("Smoke Flurry", "spell", "magic", req=(("Air Rune", 1), ("Fire Rune", 2)), damage=9),  # Round 12: was 1 Air + 1 Fire
    Card("Air Strike", "spell", "magic", req=(("Air Rune", 1),), damage=6),
    Card("Air Bolt", "spell", "magic", req=(("Chaos Rune", 1), ("Air Rune", 2)), damage=10),
    Card("Air Blast", "spell", "magic", req=(("Death Rune", 1), ("Air Rune", 3)), damage=13),
    Card("Staff of Air", "staff", "magic", req=(("Air Rune", 1),),
         counts_as=(("Air Rune", 2),), wclass="staff", hit=2, durability=3),
    Card("Rune Pouch", "pouch", item=True),

    # Melee
    Card("Bronze Dagger", "weapon", "melee", req=(("Bronze Bar", 1),), hit=4, durability=3, wclass="dagger"),
    Card("Bronze Scimitar", "weapon", "melee", req=(("Bronze Bar", 2),), hit=5, durability=3, wclass="scimitar"),
    Card("Mithril Sword", "weapon", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=8, durability=3, wclass="sword"),
    Card("Mithril Scimitar", "weapon", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=9, durability=3, wclass="scimitar"),
    Card("Slash", "ability", "melee", allowed=("sword", "scimitar"), damage=4),
    Card("Stab", "ability", "melee", allowed=("dagger", "sword"), damage=2, ignores_armor=True),
    Card("Gunnar's Crusade", "destroy", "melee", destroys="rune", needs_melee_weapon=True, damage=6),   # Round 25: +6 damage, playable with nothing to destroy

    # Ranged
    Card("Shortbow", "bow", "ranged", req=(("Normal Logs", 1),), hit=5, arrow_tier=1),
    Card("Willow Shortbow", "bow", "ranged", req=(("Normal Logs", 1), ("Willow Logs", 1)),
         hit=6, arrow_tier=2),
    Card("Bronze Arrows", "arrows", "ranged", arrow_tier=1, arrow_bonus=0),
    Card("Mithril Arrows", "arrows", "ranged", arrow_tier=2, arrow_bonus=3),
    Card("Rapid", "rapid", "ranged"),
    Card("Longrange", "longrange", "ranged"),

    # Thrown weapons: Weapon slot, made from bars, one throw per swing; discarded when empty
    Card("Bronze Darts", "thrown", "ranged", req=(("Bronze Bar", 1),), hit=4, durability=5, wclass="dart"),
    Card("Bronze Knives", "thrown", "ranged", req=(("Bronze Bar", 2),), hit=5, durability=4, wclass="knife"),
    Card("Bronze Throwing Axe", "thrown", "ranged", req=(("Bronze Bar", 2),), hit=8, durability=2, wclass="axe"),
    Card("Mithril Darts", "thrown", "ranged", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=6, durability=5, wclass="dart"),
    Card("Mithril Knives", "thrown", "ranged", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=7, durability=4, wclass="knife"),
    Card("Mithril Throwing Axe", "thrown", "ranged", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=12, durability=2, wclass="axe"),

    # Hybrid weapons: need a material from each style
    Card("Magical Longsword", "weapon", "melee", req=(("Mithril Bar", 1), ("Chaos Rune", 1)),
         hit=7, durability=3, wclass="longsword", styles=("melee", "magic"), free_swing_after_spell=True),
    Card("Shieldbow", "bow", "ranged", req=(("Willow Logs", 1), ("Mithril Bar", 1)),
         hit=5, arrow_tier=2, bash_hit=6, durability=3, soak=6, two_handed=True),
    Card("Air Battlestaff", "bow", "magic", req=(("Willow Logs", 1), ("Air Rune", 1)),
         hit=5, arrow_tier=2, counts_as=(("Air Rune", 2),)),

    # Armor (modifier = change to damage you take)
    Card("Wizard Robe", "armor", "magic", fam_req=(("rune", 1),), armor=(("melee", -2), ("ranged", -1), ("magic", -1))),   # Round 22 armor
    Card("Bronze Platebody", "armor", "melee", req=(("Bronze Bar", 2),), armor=(("melee", -1), ("ranged", -2), ("magic", -1)), bonus=2),   # Round 22 armor (+2 to your attacks)
    Card("Leather Body", "armor", "ranged", fam_req=(("log", 1),), armor=(("melee", -1), ("ranged", -1), ("magic", -2))),   # Round 22 armor
    Card("Green D'hide Body", "armor", "ranged", fam_req=(("log", 2),), armor=(("melee", -1), ("ranged", -1), ("magic", -3))),   # Round 22 armor

    # Shields
    Card("Wooden Shield", "shield", soak=5, free=True),
    Card("Kiteshield", "shield", soak=8),

    # Food
    Card("Shrimp", "food", heal=3),
    Card("Lobster", "food", heal=6),
    Card("Swordfish", "food", heal=8),
    Card("Karambwan", "food", heal=4, stacks_with_food=True),

    # Reactions: played on the opponent's turn in answer to an attack, one per player per round.
    # Prayers block one attack of their style (can't block a Punch).
    Card("Protect from Magic", "prayer", prayer="magic"),
    Card("Protect from Missiles", "prayer", prayer="ranged"),
    Card("Protect from Melee", "prayer", prayer="melee"),
    # Reversals send the attack back as the style that beats it. No requirement.
    Card("Mirror Shield", "reversal", "magic", reacts_to="melee", converts_to="magic"),
    Card("Ricochet", "reversal", "ranged", reacts_to="magic", converts_to="ranged"),
    Card("Parry", "reversal", "melee", reacts_to="ranged", converts_to="melee"),

    # Buff prayers: free to play, one in your Prayer slot at a time. Basic prayers last until
    # replaced; elite prayers last 3 of your turns (the turn you play one counts).
    # Smite and Ancient Mace can end the opponent's prayer early.
    Card("Burst of Strength", "buff", "melee", prayer="melee", bonus=1),
    Card("Sharp Eye", "buff", "ranged", prayer="ranged", bonus=1),
    Card("Mystic Will", "buff", "magic", prayer="magic", bonus=1),
    Card("Piety", "buff", "melee", prayer="melee", bonus=4, duration=3),
    Card("Rigour", "buff", "ranged", prayer="ranged", bonus=3, duration=3),
    Card("Augury", "buff", "magic", prayer="magic", bonus=4, duration=3),
    Card("Smite", "buff", effect="smite", duration=3, prayer="any", bonus=3),   # Round 22: also +3 to all attacks
    Card("Redemption", "buff", effect="redemption", reward_val=10, duration=3),

    # Special attack weapons: you gain 1 special energy at the start of each of your turns (max 4).
    # A special attack is an action (marked free ones aren't) and uses durability like a swing.
    Card("Ancient Mace", "weapon", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=7, durability=4, wclass="mace", spec="war_god", spec_cost=2),
    Card("Excalibur", "weapon", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 2)),
         hit=8, durability=3, wclass="sword", spec="sanctuary", spec_cost=2, spec_val=3),
    Card("Barrelchest Anchor", "weapon", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 3)),
         hit=10, durability=3, wclass="anchor", two_handed=True, spec="sunder", spec_cost=3, spec_val=5),
    Card("Magic Shortbow", "bow", "ranged", req=(("Normal Logs", 1), ("Willow Logs", 2)),
         hit=5, arrow_tier=2, spec="snapshot", spec_cost=3),
    Card("Seercull", "bow", "ranged", req=(("Normal Logs", 1), ("Willow Logs", 2)),
         hit=5, arrow_tier=2, spec="soulshot", spec_cost=3, spec_val=2),
    # Round 19 (Ramon): Iban's and Light need any Air/Water/Earth/Fire rune, not Air; Light counts as
    # 2 of the Air/Water/Earth/Fire rune you have the most of in play ("Elemental Rune" in the engine)
    Card("Staff of Light", "staff", "magic", req=(("Chaos Rune", 1), ("Elemental Rune", 1)),
         counts_as=(("Elemental Rune", 2),), wclass="staff", hit=2, durability=3,
         spec="light", spec_cost=2),
    Card("Iban's Staff", "staff", "magic", req=(("Chaos Rune", 1), ("Elemental Rune", 1)),
         wclass="staff", hit=3, durability=3, spec="iban", spec_cost=3, spec_val=12),
    Card("Saradomin Sword", "weapon", "melee", req=(("Mithril Bar", 2), ("Chaos Rune", 1)),
         hit=8, durability=3, wclass="godsword", two_handed=True, styles=("melee",),
         spec="lightning", spec_cost=3, spec_val=6),

    # Quests: one in progress at a time; each step is an action. Each quest can be completed
    # once per game. Completed quests leave the game.
    Card("Cook's Assistant", "quest", qp=1, steps=(("discard", "food"), ("discard", "food")),
         effect="heal", reward_val=10),
    Card("Sheep Shearer", "quest", qp=1, steps=(("have", ("any", 2)),), effect="draw", reward_val=2),   # Round 22: was 1 Cloth
    Card("Rune Mysteries", "quest", qp=1, steps=(("have", ("rune", 2)), ("have", ("rune", 3))),
         effect="fetch_rune", reward_val=1),
    Card("Doric's Quest", "quest", qp=1, steps=(("discard", "material"), ("discard", "material")),
         effect="fetch_bar", reward_val=1),
    Card("The Restless Ghost", "quest", qp=1, steps=(("have", ("any", 3)), ("discard", None)),
         effect="fetch_prayer", reward_val=1),
    Card("Romeo & Juliet", "quest", qp=5, steps=(("discard", None), ("discard", None), ("discard", None)),
         effect="none"),
    Card("Goblin Diplomacy", "quest", qp=5, steps=(("spend", None), ("spend", None), ("spend", None)),
         effect="none"),
    Card("Prince Ali Rescue", "quest", qp=3, steps=(("armor", None), ("discard", "material"), ("shield", None)),
         effect="lose_action", reward_val=1),
    Card("Vampyre Slayer", "quest", qp=3, steps=(("weapon", None), ("discard", "food"), ("weapon", None)),
         effect="damage", reward_val=10),
    Card("Ernest the Chicken", "quest", qp=4, steps=(("discard", None), ("have", ("any", 4)), ("discard", None)),
         effect="chicken"),
    Card("Demon Slayer", "quest", qp=3, steps=(("have", ("any", 3)), ("discard", "material"), ("weapon", None)),
         effect="silverlight"),
    Card("Dragon Slayer", "quest", qp=2, steps=(("shield", None), ("armor", None), ("spend", None), ("weapon", None)),
         effect="dragon_slayer"),
    Card("Quest Point Cape", "cape"),     # needs quest points (rules: cape_qp); play it and you win

    # Tier 3 (Round 13): Rune bars, Yew logs, Blood runes and what they make.
    # Rune gear costs 1 Mithril + 2 Rune: one of the tier below, two of its own (adopted).
    _mat("Rune Bar", "bar"), _mat("Yew Logs", "log"), _mat("Blood Rune", "rune"),
    Card("Bronze Mace", "weapon", "melee", req=(("Bronze Bar", 2),), hit=7, durability=2, wclass="mace"),
    Card("Mithril Mace", "weapon", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 2)), hit=10, durability=2, wclass="mace"),  # Round 17: was 12 (Bronze 8)
    Card("Rune Dagger", "weapon", "melee", req=(("Mithril Bar", 1), ("Rune Bar", 2)), hit=10, durability=4, wclass="dagger"),
    Card("Rune Scimitar", "weapon", "melee", req=(("Mithril Bar", 1), ("Rune Bar", 2)), hit=13, durability=3, wclass="scimitar"),
    Card("Rune Mace", "weapon", "melee", req=(("Mithril Bar", 1), ("Rune Bar", 2)), hit=20, durability=2, wclass="mace"),
    Card("Crush", "ability", "melee", allowed=("mace",), damage=6, durability=2),   # uses 2 durability
    Card("Yew Shortbow", "bow", "ranged", req=(("Willow Logs", 1), ("Yew Logs", 2)), hit=8, arrow_tier=3),
    Card("Rune Arrows", "arrows", "ranged", arrow_tier=3, arrow_bonus=5),
    Card("Rune Darts", "thrown", "ranged", req=(("Mithril Bar", 1), ("Rune Bar", 2)), hit=8, durability=5, wclass="dart"),
    Card("Rune Knives", "thrown", "ranged", req=(("Mithril Bar", 1), ("Rune Bar", 2)), hit=10, durability=4, wclass="knife"),
    Card("Rune Throwing Axe", "thrown", "ranged", req=(("Mithril Bar", 1), ("Rune Bar", 2)), hit=17, durability=2, wclass="axe"),
    Card("Mithril Platebody", "armor", "melee", req=(("Bronze Bar", 1), ("Mithril Bar", 2)), armor=(("melee", -1), ("ranged", -3), ("magic", -1)), bonus=2),   # Round 22 armor (+2 to your attacks)
    Card("Rune Platebody", "armor", "melee", req=(("Mithril Bar", 1), ("Rune Bar", 2)), armor=(("melee", -1), ("ranged", -4), ("magic", -1)), bonus=2),   # Round 22 armor (+2 to your attacks)
    Card("Mystic Robe Top", "armor", "magic", fam_req=(("rune", 2),), armor=(("melee", -3), ("ranged", -1), ("magic", -1))),   # Round 22 armor
    Card("Infinity Top", "armor", "magic", fam_req=(("rune", 3),), armor=(("melee", -4), ("ranged", -1), ("magic", -1))),   # Round 22 armor
    Card("Black D'hide Body", "armor", "ranged", fam_req=(("log", 3),), armor=(("melee", -1), ("ranged", -1), ("magic", -4))),   # Round 22 armor
    Card("Fire Blast", "spell", "magic", req=(("Death Rune", 1), ("Fire Rune", 3)), damage=15),   # Round 22: was 4 Fire
    Card("Air Wave", "spell", "magic", req=(("Blood Rune", 1), ("Air Rune", 4)), damage=17),
    Card("Fire Wave", "spell", "magic", req=(("Blood Rune", 1), ("Fire Rune", 4)), damage=19),   # Round 22: was 5 Fire

    # Legendary (Round 13, adopted): one Legendary card per deck. Needs materials of any tier in play.
    # Telegraph: this weapon cannot attack on the turn it is played. Breaks shields it
    # hits (Shatter) and is removed from the game when it breaks. (Legendary materials were cut.)
    # Granite Maul lost its equip-and-swing: it contradicts Telegraph.
    # Round 14 tuning: each is worth about +10% win rate when you have it (Dagger was +31, Dark Bow +2).
    Card("Dragon Dagger", "weapon", "melee", hit=9, durability=1, wclass="dagger", hits=2, legendary=True,
         fam_req=(("bar", 2),)),
    Card("Granite Maul", "weapon", "melee", hit=22, durability=1, wclass="maul", two_handed=True,
         legendary=True, fam_req=(("bar", 3),)),
    Card("Dark Bow", "weapon", "ranged", hit=15, durability=1, wclass="bow", styles=("ranged",), hits=2,
         two_handed=True, legendary=True, fam_req=(("log", 3),)),
    Card("Staff of the Dead", "weapon", "magic", hit=20, durability=1, wclass="staff", styles=("magic",),
         legendary=True, fam_req=(("rune", 3),)),

    # Hybrid cards (Round 11)
    # Dual materials: each counts as one of each of two materials (4-copy limit, like specialty runes).
    Card("Enchanted Bar", "material", mat="bar", combo=("Mithril Bar", "Chaos Rune")),
    Card("Mithril Grapple", "material", mat="bar", combo=("Mithril Bar", "Willow Logs")),
    Card("Enchanted Logs", "material", mat="log", combo=("Willow Logs", "Chaos Rune")),
    # Void Knight Top: needs materials from two different families in play. All damage you take -1,
    # your attacks +1, no weakness. (A "Switch" card that equipped an off-style weapon for free was
    # tried and cut: hybrid decks were almost never able to play it.)
    Card("Void Knight Top", "armor", families=2, armor=(("melee", -1), ("ranged", -1), ("magic", -1)), bonus=1),

    # Resource destruction items
    Card("Tinderbox", "destroy", destroys="log", item=True, damage=6),   # Round 22: no Cloth requirement; Round 25: +6 damage
    Card("Superheat", "destroy", "magic", req=(("Fire Rune", 2),), destroys="bar", damage=6),   # Round 25: +6 damage, playable with nothing to destroy

    # NPCs: materials go straight back into play, anything else to your hand
    Card("Archmage Sedridor", "npc", recovers=("rune",)),
    Card("Doric", "npc", recovers=("bar",)),
    Card("Lowe", "npc", recovers=("log", "arrows")),
    Card("Vanaka", "npc", recovers=("weapon", "thrown", "bow", "staff")),
    Card("Banker", "npc", recovers=("item",)),

    # Locations
    Card("Wizards' Tower", "location"),   # Magic damage +2; prayers need 1 extra Bone
    Card("Wilderness", "location"),       # All damage +2

    # ---------- Group 2 (Round 18): the cards the simulator hadn't seen ----------
    _mat("Water Rune", "rune"), _mat("Law Rune", "rune"),
    Card("Lava Rune", "material", mat="rune", combo=("Fire Rune", "Earth Rune")),
    Card("Steam Rune", "material", mat="rune", combo=("Fire Rune", "Water Rune")),
    Card("Mud Rune", "material", mat="rune", combo=("Water Rune", "Earth Rune")),
    Card("Mist Rune", "material", mat="rune", combo=("Water Rune", "Air Rune")),
    # Water spells heal 2 as part of the spell, whether or not the opponent reacts (ruling default)
    Card("Water Strike", "spell", "magic", req=(("Water Rune", 1),), damage=5, heals=2),
    Card("Water Bolt", "spell", "magic", req=(("Chaos Rune", 1), ("Water Rune", 2)), damage=9, heals=2),
    Card("Water Blast", "spell", "magic", req=(("Death Rune", 1), ("Water Rune", 3)), damage=12, heals=2),
    Card("Water Wave", "spell", "magic", req=(("Blood Rune", 1), ("Water Rune", 4)), damage=16, heals=2),
    Card("Earth Blast", "spell", "magic", req=(("Death Rune", 1), ("Earth Rune", 3)), damage=12, wears=1),
    Card("Earth Wave", "spell", "magic", req=(("Blood Rune", 1), ("Earth Rune", 4)), damage=16, wears=1),
    Card("Mist Flurry", "spell", "magic", req=(("Air Rune", 1), ("Water Rune", 1)), damage=8, heals=2),
    Card("Dust Flurry", "spell", "magic", req=(("Air Rune", 1), ("Earth Rune", 1)), damage=8, wears=1),
    Card("Steam Flurry", "spell", "magic", req=(("Water Rune", 1), ("Fire Rune", 1)), damage=8, heals=2),
    Card("Mud Flurry", "spell", "magic", req=(("Water Rune", 1), ("Earth Rune", 1)), damage=7, heals=2, wears=1),
    Card("Lava Flurry", "spell", "magic", req=(("Earth Rune", 1), ("Fire Rune", 1)), damage=8, wears=1),
    # Teleports: play Varrock from your deck. If it isn't there (or is already in play), the
    # spell still draws its card; the tablet does nothing.
    Card("Varrock Teleport", "spell", "magic", req=(("Law Rune", 1), ("Air Rune", 1), ("Fire Rune", 1)),
         draws=1, fetch="Varrock"),
    Card("Varrock Teleport Tablet", "teleport", item=True, fetch="Varrock"),
    Card("Staff of Water", "staff", "magic", req=(("Water Rune", 1),),
         counts_as=(("Water Rune", 2),), wclass="staff", hit=2, durability=3),
    Card("Staff of Earth", "staff", "magic", req=(("Earth Rune", 1),),
         counts_as=(("Earth Rune", 2),), wclass="staff", hit=2, durability=3),
    Card("Staff of Fire", "staff", "magic", req=(("Fire Rune", 1),),
         counts_as=(("Fire Rune", 2),), wclass="staff", hit=2, durability=3),
    # Smith's Hammer: +3 durability to your weapon (not a Legendary, not a bow with no
    # durability) or +3 soak to your shield
    Card("Smith's Hammer", "hammer", item=True, bonus=3),
    Card("Anti-dragon Shield", "shield", soak=7, unshatter=True),   # Round 22: soak 6 -> 7;   # Shatter (and Sunder) don't break it
    Card("Shark", "food", heal=10),
    Card("Horvik", "npc", recovers=("armor", "shield")),
    Card("Brother Jered", "npc", recovers=("buff", "prayer")),     # buff and Protect prayers
    Card("Thessalia", "npc", recovers=("cloth", "leather")),
    Card("Wise Old Man", "npc", recovers=("any",), legendary=True),  # takes the deck's Legendary slot
    # Locations (rulings, Round 18)
    Card("Lumbridge", "location"),       # each player's first time damage would empty their deck, they keep 1 card
    Card("Varrock", "location"),         # action: discard a card from hand to heal 3 (either player)
    Card("Mage Arena", "location"),      # Magic damage +2, Ranged damage -2
    Card("Duel Arena", "location"),      # no food, no prayers of any kind; active buff prayers end
    Card("Grand Exchange", "location", free=True),  # Round 22: free to play. Once per turn, free: discard a card to draw one
]

CARDS = {c.name: c for c in _ALL}


AIR_MAGE = {
    "Air Rune": 12, "Chaos Rune": 5, "Death Rune": 3, "Cloth": 2,
    "Air Strike": 4, "Air Bolt": 4, "Air Blast": 4,
    "Staff of Air": 3, "Rune Pouch": 4, "Wizard Robe": 2,
    "Kiteshield": 3, "Wooden Shield": 2, "Lobster": 3, "Augury": 2, "Mystic Will": 2,
    "Archmage Sedridor": 2, "Tinderbox": 2, "Wizards' Tower": 1,
}

MELEE = {
    "Mithril Bar": 12, "Bronze Bar": 10,
    "Bronze Dagger": 2, "Bronze Scimitar": 3, "Mithril Sword": 2, "Mithril Scimitar": 2,
    "Slash": 4, "Stab": 2, "Bronze Platebody": 3,
    "Kiteshield": 3, "Wooden Shield": 2, "Swordfish": 2, "Lobster": 2, "Karambwan": 2,
    "Protect from Magic": 2, "Protect from Missiles": 1,
    "Gunnar's Crusade": 2, "Doric": 2, "Vanaka": 1, "Wilderness": 1,
}

RANGED = {
    "Willow Logs": 10, "Normal Logs": 7, "Leather": 4,
    "Shortbow": 3, "Willow Shortbow": 4, "Bronze Arrows": 4, "Mithril Arrows": 4,
    "Rapid": 4, "Longrange": 2, "Leather Body": 2, "Green D'hide Body": 2,
    "Wooden Shield": 3, "Kiteshield": 3, "Lobster": 4, "Shrimp": 2, "Lowe": 2,
}

# Dual-style test decks: tiers 1-2, built to the same 22-material budget.
MELEE_RANGED = {
    "Bronze Bar": 6, "Mithril Bar": 5, "Normal Logs": 6, "Willow Logs": 5,
    "Bronze Scimitar": 3, "Mithril Scimitar": 2, "Mithril Sword": 2, "Slash": 3,
    "Shortbow": 2, "Willow Shortbow": 3, "Bronze Arrows": 2, "Mithril Arrows": 4, "Rapid": 3,
    "Bronze Platebody": 2, "Kiteshield": 3, "Wooden Shield": 2,
    "Lobster": 3, "Swordfish": 2, "Doric": 1, "Lowe": 1,
}

MAGE_MELEE = {
    "Air Rune": 9, "Chaos Rune": 3, "Bronze Bar": 5, "Mithril Bar": 5,
    "Air Strike": 4, "Air Bolt": 4, "Rune Pouch": 2,
    "Bronze Scimitar": 3, "Mithril Scimitar": 2, "Mithril Sword": 2, "Slash": 3, "Gunnar's Crusade": 2,
    "Bronze Platebody": 2, "Kiteshield": 3, "Wooden Shield": 2,
    "Lobster": 3, "Swordfish": 2, "Shrimp": 1, "Archmage Sedridor": 1, "Doric": 1, "Wizards' Tower": 1,
}

MAGE_RANGED = {
    "Air Rune": 9, "Chaos Rune": 3, "Normal Logs": 5, "Willow Logs": 5,
    "Air Strike": 4, "Air Bolt": 4, "Rune Pouch": 2,
    "Shortbow": 2, "Willow Shortbow": 3, "Bronze Arrows": 2, "Mithril Arrows": 4, "Rapid": 3,
    "Kiteshield": 3, "Wooden Shield": 3, "Lobster": 3, "Shrimp": 2,
    "Archmage Sedridor": 1, "Lowe": 1, "Wizards' Tower": 1,
}

# Single-style decks built to the same budget as the dual-style decks:
# 22 materials, damage cards first, 2 body armor each, few utility cards.
LEAN_MAGE = {
    "Air Rune": 12, "Chaos Rune": 5, "Death Rune": 3, "Cloth": 2, "Air Strike": 4, "Air Bolt": 4,
    "Air Blast": 4, "Staff of Air": 3, "Rune Pouch": 4, "Wizard Robe": 2, "Kiteshield": 3,
    "Wooden Shield": 3, "Lobster": 4, "Shrimp": 2, "Archmage Sedridor": 2, "Wizards' Tower": 2, "Banker": 1,
}
LEAN_MELEE = {
    "Bronze Bar": 9, "Mithril Bar": 13, "Bronze Dagger": 2, "Bronze Scimitar": 4, "Mithril Sword": 4,
    "Mithril Scimitar": 4, "Slash": 4, "Stab": 4, "Bronze Platebody": 2, "Kiteshield": 3, "Wooden Shield": 2,
    "Lobster": 3, "Swordfish": 2, "Karambwan": 2, "Doric": 1, "Vanaka": 1,
}
LEAN_RANGED = {
    "Normal Logs": 9, "Willow Logs": 11, "Leather": 2, "Shortbow": 2, "Willow Shortbow": 3,
    "Bronze Arrows": 4, "Mithril Arrows": 4, "Rapid": 4, "Longrange": 4, "Leather Body": 2,
    "Kiteshield": 4, "Wooden Shield": 3, "Lobster": 4, "Shrimp": 2, "Lowe": 2,
}

SMOKE_MAGE = {   # Air/Fire mage using Smoke Runes: a deeper spell pool than Air alone
    "Air Rune": 6, "Fire Rune": 6, "Smoke Rune": 4, "Chaos Rune": 4, "Cloth": 2,
    "Air Strike": 4, "Fire Strike": 4, "Smoke Flurry": 4, "Air Bolt": 4, "Fire Bolt": 4,
    "Rune Pouch": 2, "Wizard Robe": 2, "Kiteshield": 3, "Wooden Shield": 3, "Lobster": 4,
    "Archmage Sedridor": 2, "Wizards' Tower": 2,
}

THROWN_RANGED = {   # Ranged on bars: darts, knives and axes instead of bows
    "Bronze Bar": 8, "Mithril Bar": 12, "Leather": 2,
    "Bronze Darts": 2, "Bronze Knives": 3, "Bronze Throwing Axe": 2,
    "Mithril Darts": 4, "Mithril Knives": 4, "Mithril Throwing Axe": 2,
    "Rapid": 4, "Longrange": 2, "Leather Body": 2, "Kiteshield": 3, "Wooden Shield": 3,
    "Lobster": 4, "Shrimp": 1, "Doric": 2,
}

MELEE_THROWN = {   # Melee/Ranged sharing one material family: bars
    "Bronze Bar": 9, "Mithril Bar": 13,
    "Bronze Scimitar": 3, "Mithril Scimitar": 3, "Mithril Sword": 2, "Slash": 3, "Stab": 1,
    "Bronze Knives": 2, "Mithril Darts": 3, "Mithril Knives": 3, "Mithril Throwing Axe": 2, "Rapid": 2,
    "Bronze Platebody": 2, "Kiteshield": 3, "Wooden Shield": 2, "Lobster": 3, "Swordfish": 2, "Doric": 2,
}

# The three dual-style decks with their hybrid weapon added
MELEE_RANGED_GEAR = {**MELEE_RANGED, "Shortbow": 0, "Rapid": 2, "Shieldbow": 3}
MAGE_MELEE_GEAR = {**MAGE_MELEE, "Bronze Scimitar": 0, "Gunnar's Crusade": 1, "Magical Longsword": 4}
MAGE_RANGED_GEAR = {**MAGE_RANGED, "Shortbow": 0, "Rapid": 2, "Air Battlestaff": 3}
for _d in (MELEE_RANGED_GEAR, MAGE_MELEE_GEAR, MAGE_RANGED_GEAR):
    for _k in [k for k, v in _d.items() if v == 0]:
        del _d[_k]

ANTI_MELEE = {   # Smoke Mage rebuilt to beat Melee: melt its bars, robes, Protect from Melee
    "Air Rune": 5, "Fire Rune": 7, "Smoke Rune": 4, "Chaos Rune": 4, "Cloth": 2,
    "Air Strike": 4, "Fire Strike": 4, "Smoke Flurry": 4, "Air Bolt": 3, "Fire Bolt": 3,
    "Superheat": 3, "Wizard Robe": 3, "Protect from Melee": 2, "Kiteshield": 3, "Wooden Shield": 2,
    "Lobster": 3, "Archmage Sedridor": 1, "Rune Pouch": 3,
}

EARTH_MAGE = {   # attacks the opponent's weapon: Earth spells and Crumble wear durability
    "Earth Rune": 10, "Air Rune": 4, "Dust Rune": 4, "Chaos Rune": 4,
    "Earth Strike": 4, "Earth Bolt": 4, "Crumble": 3, "Air Strike": 4, "Air Bolt": 4,
    "Kiteshield": 4, "Wooden Shield": 3, "Lobster": 4, "Shrimp": 3, "Archmage Sedridor": 2, "Rune Pouch": 3,
}

STARTER_DECKS = {"Air Mage": AIR_MAGE, "Melee": MELEE, "Ranged": RANGED}
LEAN_DECKS = {"Lean Mage": LEAN_MAGE, "Lean Melee": LEAN_MELEE, "Lean Ranged": LEAN_RANGED}
HYBRID_DECKS = {"Melee/Ranged": MELEE_RANGED, "Mage/Melee": MAGE_MELEE, "Mage/Ranged": MAGE_RANGED}
GEAR_DECKS = {"Melee/Ranged+": MELEE_RANGED_GEAR, "Mage/Melee+": MAGE_MELEE_GEAR,
              "Mage/Ranged+": MAGE_RANGED_GEAR, "Melee/Thrown": MELEE_THROWN}
OTHER_DECKS = {"Smoke Mage": SMOKE_MAGE, "Thrown Ranged": THROWN_RANGED}
COUNTER_DECKS = {"Anti-Melee": ANTI_MELEE, "Earth Mage": EARTH_MAGE}


def check_decks(decks=None):
    for name, deck in (decks or {**STARTER_DECKS, **HYBRID_DECKS, **LEAN_DECKS, **GEAR_DECKS, **OTHER_DECKS, **COUNTER_DECKS}).items():
        total = sum(deck.values())
        assert total == 60, f"{name} has {total} cards"
        for card, n in deck.items():
            assert card in CARDS, f"{name}: unknown card {card}"
            if CARDS[card].kind != "material" or CARDS[card].legendary or CARDS[card].combo:
                assert n <= 4, f"{name}: {n} copies of {card}"
        assert sum(n for c, n in deck.items() if CARDS[c].legendary) <= 1, f"{name}: more than one Legendary"


def with_reactions(deck, reactions):
    """A copy of deck with reaction cards added in place of food, shields and NPCs."""
    d = dict(deck)
    d.update(reactions)
    owed = sum(reactions.values())
    for name in ("Shrimp", "Swordfish", "Karambwan", "Lobster", "Wooden Shield", "Kiteshield",
                 "Banker", "Lowe", "Doric", "Vanaka", "Archmage Sedridor"):
        while owed and d.get(name, 0) > 0 and name not in reactions:
            d[name] -= 1
            owed -= 1
    assert owed == 0
    return {k: v for k, v in d.items() if v}


NATURAL_REVERSALS = {   # each style's reversal answers the style it beats
    "Lean Mage": {"Mirror Shield": 3}, "Smoke Mage": {"Mirror Shield": 3},
    "Lean Melee": {"Parry": 3},
    "Lean Ranged": {"Ricochet": 3}, "Thrown Ranged": {"Ricochet": 3},
    "Melee/Ranged+": {"Parry": 2, "Ricochet": 2}, "Melee/Thrown": {"Parry": 2, "Ricochet": 2},
    "Mage/Melee+": {"Mirror Shield": 2, "Parry": 2}, "Mage/Ranged+": {"Mirror Shield": 2, "Ricochet": 2},
}


check_decks()


# ---- Round 22: Cloth, Leather and Thessalia are cut (armor now costs your style's own material) ----
RETIRED = {"Cloth", "Leather", "Thessalia",
           "Varrock Teleport", "Law Rune", "Crumble"}   # Round 26: the Tablet is the only teleport; Crumble was sim-only
BASIC_RUNES = ("Air Rune", "Water Rune", "Earth Rune", "Fire Rune")


def modernize(deck):
    """A copy of a decklist with the cut cards replaced:
    Cloth -> the deck's most-run basic elemental rune; Leather -> its most-run log (no logs: most-run bar);
    Thessalia -> Lowe (logs) or Doric; ranged armor in a deck with no logs -> Bronze Platebody (bars) ."""
    d = {k: v for k, v in deck.items() if v}

    def top(names):
        have = [(d.get(n, 0), n) for n in names if d.get(n, 0)]
        return max(have)[1] if have else None
    logs = top(("Normal Logs", "Willow Logs", "Yew Logs"))
    bars = top(("Bronze Bar", "Mithril Bar", "Rune Bar"))
    rune = top(BASIC_RUNES) or top([n for n, c in CARDS.items() if c.kind == "material" and c.mat == "rune"])
    for cut, to in (("Cloth", rune or logs or bars), ("Leather", logs or bars or rune),
                    ("Thessalia", "Lowe" if logs else "Doric"), ("Law Rune", rune or logs or bars),
                    ("Crumble", rune or logs or bars), ("Varrock Teleport", "Varrock Teleport Tablet")):
        n = d.pop(cut, 0)
        if n:
            assert to, f"nothing to replace {cut} with"
            d[to] = d.get(to, 0) + n
    if not logs:
        for arm in ("Leather Body", "Green D'hide Body", "Black D'hide Body"):
            n = d.pop(arm, 0)
            if n:
                to = "Bronze Platebody" if d.get("Bronze Bar", 0) >= 2 else "Kiteshield"
                d[to] = d.get(to, 0) + n
    assert sum(d.values()) == sum(deck.values()), "modernize changed the deck size"
    return d


STARTER_DECKS = {k: modernize(v) for k, v in STARTER_DECKS.items()}
STARTER_DECKS_R26 = STARTER_DECKS      # the starters before Round 27, kept so old experiments can be rerun
# Round 27: starters rebuilt from the fully tested pool. Each carries a little of everything its style needs (see
# starters27.py); head to head they sit within 4 points of each other, with or without their suggested capes.
from starter_lists import NEW as _R27, CAPE as STARTER_CAPES      # noqa: E402
STARTER_DECKS = {k: dict(v) for k, v in _R27.items()}
