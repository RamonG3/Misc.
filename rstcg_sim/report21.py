"""Round 21: one table of every Set 1 card from sweep_greedy.json (all cards, 100 games per opponent per
seat) and sweep_smart.json (the flagged cards, 4 smart-bot games per opponent per seat).

    python report21.py set1.json > report21.tsv
"""
import json
import sys
from collections import defaultdict

from cards import CARDS

G = json.load(open("sweep_greedy.json"))
S = json.load(open("sweep_smart.json"))
SET1 = json.load(open(sys.argv[1])) if len(sys.argv) > 1 else {}

KIND = {"material": "Material", "spell": "Spell", "staff": "Staff", "weapon": "Weapon", "thrown": "Thrown",
        "bow": "Bow", "arrows": "Arrows", "ability": "Ability", "rapid": "Ability", "longrange": "Ability",
        "reversal": "Reversal", "armor": "Armor", "shield": "Shield", "food": "Food", "prayer": "Protect prayer",
        "buff": "Buff prayer", "npc": "NPC", "location": "Location", "teleport": "Teleport", "destroy": "Destroy",
        "pouch": "Item", "hammer": "Item", "quest": "Quest", "cape": "Cape"}


def rows():
    out = []
    for card, ts in G["cards"].items():
        st = S["cards"].get(card, [])
        for i, t in enumerate(ts):
            s = st[i] if i < len(st) else None
            out.append(dict(card=card, kind=KIND.get(CARDS[card].kind, CARDS[card].kind),
                            num=SET1.get(card, {}).get("num", "—"), rarity=SET1.get(card, {}).get("rarity", "—"),
                            test=t["label"], copies=t["copies"], slots=t["n_slots"],
                            g_val=t["value"], g_slot=t["value"] / max(1, t["n_slots"]),
                            g_vsr=t.get("vs_replaced"), g_vsb=t.get("vs_basics"), g_use=t["uses_per_game"],
                            s_val=s and s["value"], s_vsr=s and s.get("vs_replaced"), s_use=s and s["uses_per_game"]))
    for cape, v in G["capes"].items():
        s = S["capes"].get(cape)
        out.append(dict(card=cape, kind="Cape", num=SET1.get(cape, {}).get("num", "—"),
                        rarity=SET1.get(cape, {}).get("rarity", "R"), test=f"{v['host']} brings it", copies=1,
                        slots=0, g_val=v["value"], g_slot=None, g_vsr=None, g_vsb=None,
                        g_use=v["cape_win_share"] / 100, s_val=s and s["value"], s_vsr=None,
                        s_use=s and s["cape_win_share"] / 100))
    return out


if __name__ == "__main__":
    f = lambda x: "" if x is None else f"{x:.1f}" if isinstance(x, float) else str(x)
    cols = ["num", "card", "kind", "rarity", "test", "copies", "g_val", "g_slot", "g_vsr", "g_vsb", "g_use",
            "s_val", "s_vsr", "s_use"]
    print("\t".join(cols))
    for r in sorted(rows(), key=lambda r: (r["num"], r["card"])):
        print("\t".join(f(r[c]) for c in cols))


# ---- verdicts (Round 21 judgment, from both sweeps; see README Round 21) ----
# Strong: clearly above its peers. Fine: does its job. Low: in the field but barely matters (starter tier or
# filler). Weak: loses to what it replaces, or about zero, with both bots. Dead: almost never played or no effect.
# Bot-limited: the greedy bot can't play it; the smart bot shows it working.
VERDICT = {
    "Iban's Staff": ("Strong", "Best staff by far (5.6/copy vs ~1.4); every mage list runs it. Magic leans on it, so watch rather than nerf."),
    "Shark": ("Strong", "+5 to +7 over Lobster. Fine at Rare."),
    "Willow Shortbow": ("Strong", "Beats Magic Shortbow in its own deck (greedy +5, smart +2)."),
    "Magic Shortbow": ("Weak", "Costs more than Willow Shortbow and hits for less (5 vs 6); only Snapshot makes up the gap. Candidate: hit 6."),
    "Enchanted Logs": ("Fine", "+8 over the two basics it stands for; hybrid decks are weak overall, so OK."),
    "Enchanted Bar": ("Fine", "+8 over the two basics; hybrid decks are weak overall, so OK."),
    "Mithril Grapple": ("Fine", "+6 over the two basics; hybrid decks are weak overall, so OK."),
    "Wizard Robe": ("Weak", "About zero with both bots (−1.4 greedy, 0 smart). Armor as a class barely gets played."),
    "Leather Body": ("Weak", "About zero with both bots. Armor as a class barely gets played."),
    "Bronze Platebody": ("Weak", "About zero. Armor as a class barely gets played."),
    "Infinity Top": ("Weak", "Below a blank card (−2.4)."),
    "Mystic Robe Top": ("Weak", "Below a blank card; no better than Wizard Robe."),
    "Green D'hide Body": ("Weak", "Below a blank card; no better than Leather Body."),
    "Black D'hide Body": ("Weak", "Worse than the Leather Body it replaces with both bots (−4)."),
    "Mithril Platebody": ("Low", "Slightly positive; low impact."),
    "Rune Platebody": ("Low", "Slightly positive; low impact."),
    "Cloth": ("Low", "Only feeds robes, which are weak."),
    "Leather": ("Low", "Only feeds d'hide/leather armor, which is weak."),
    "Protect from Missiles": ("Dead", "Played 0.05 times a game, value 0 with both bots."),
    "Protect from Melee": ("Weak", "Rarely played (0.07/game); small value."),
    "Protect from Magic": ("Low", "Small positive (+2.6 both bots)."),
    "Smite": ("Weak", "Worse than the Burst of Strength it replaces with both bots (−2.5 / −3.9)."),
    "Mystic Will": ("Weak", "Worse than Augury (−3.3)."),
    "Redemption": ("Low", "About even with Burst of Strength."),
    "Grand Exchange": ("Weak", "Worse than the Stab it replaces with both bots (−2.5 / −4.6)."),
    "Varrock Teleport": ("Weak", "Costs Smoke Mage 5–8 points with both bots (Law Runes + slots)."),
    "Varrock": ("Bot-limited", "Greedy −2.9 vs Stab; smart +6.6 with Tablets. Works with a real pilot."),
    "Varrock Teleport Tablet": ("Bot-limited", "Greedy −2.9; smart +6.6 (with Varrock)."),
    "Law Rune": ("Weak", "Only useful for Varrock Teleport, which is weak."),
    "Tinderbox": ("Dead", "Played 0.03 times a game with both bots: only logs to burn, only Ranged runs logs."),
    "Gunnar's Crusade": ("Weak", "About zero with both bots."),
    "Superheat": ("Weak", "Situational (bars only); greedy −4.8 vs Lobster, smart noisy."),
    "Crumble": ("Dead", "Not in Set 1; −0.3 greedy, −4.6 smart. Cut it from the sim's Earth Mage."),
    "Anti-dragon Shield": ("Weak", "Worse than Kiteshield (−2.8 greedy, 0 smart). Already on the watch list."),
    "Thessalia": ("Weak", "Below what it replaces with both bots (−2.2 / −3.3)."),
    "Horvik": ("Weak", "Below what it replaces with both bots (−1.7 / −2.0)."),
    "Wise Old Man": ("Low", "About even with Vanaka (both bots)."),
    "Brother Jered": ("Low", "Greedy −2.2, smart +2.5: about even."),
    "Banker": ("Low", "About zero."), "Archmage Sedridor": ("Low", "About zero."), "Vanaka": ("Low", "Small."),
    "Wizards' Tower": ("Low", "About zero (greedy +0.5, smart −4.6, noisy)."),
    "Mage Arena": ("Fine", "+2 to +3 over Wizards' Tower with both bots."),
    "Wilderness": ("Low", "About zero; shared effect."), "Lumbridge": ("Low", "About zero; a once-per-player save."),
    "Duel Arena": ("Low", "About zero; shared effect."),
    "Fire Blast": ("Weak", "Greedy −4.8 vs what it replaces, smart +1.3. On the watch list."),
    "Fire Wave": ("Weak", "−7.4 vs what it replaces. On the watch list."),
    "Earth Blast": ("Weak", "Greedy −6.6 vs Air Strike, smart 0. On the watch list."),
    "Earth Wave": ("Weak", "−6.8 vs Air Strike. On the watch list."),
    "Water Wave": ("Fine", "Even with Water Blast."),
    "Staff of Earth": ("Low", "Fine as a starter staff; far below Iban's (−18 if it replaces it)."),
    "Staff of Air": ("Low", "Even with Staff of Light; starter grade."),
    "Shrimp": ("Low", "Much worse than Lobster (−6.7); starter food."),
    "Karambwan": ("Low", "Slightly worse than Lobster."),
    "Swordfish": ("Fine", "+2 over Lobster."),
    "Smith's Hammer": ("Fine", "Greedy even with Stab, smart +5.9."),
    "Longrange": ("Low", "The greedy bot rarely uses it (0.04/game); smart 0.22/game, +3.3."),
    "Crush": ("Low", "0.3/copy vs Slash 1.5."), "Stab": ("Fine", ""),
    "Bronze Darts": ("Low", "Zero value; starter tier."), "Bronze Throwing Axe": ("Low", "Starter tier."),
    "Bronze Knives": ("Low", "Starter tier."), "Bronze Mace": ("Low", "Starter tier."),
    "Bronze Scimitar": ("Low", "Starter tier."), "Bronze Dagger": ("Low", "Starter tier."),
    "Ancient Mace": ("Low", "Special-attack weapons are low impact (0.5–0.9/copy)."),
    "Excalibur": ("Low", "Special-attack weapons are low impact."),
    "Barrelchest Anchor": ("Low", "Special-attack weapons are low impact."),
    "Saradomin Sword": ("Low", "1.3/copy vs Magical Longsword 2.9 in the same deck."),
    "Rune Darts": ("Fine", "A bit below Rune Knives (−2.6)."),
    "Rune Mace": ("Fine", "Even with Rune Scimitar."),
    "Granite Maul": ("Fine", "Legendary: +1 to +2 over Vanaka."), "Dragon Dagger": ("Fine", "Legendary: +2."),
    "Dark Bow": ("Fine", "Legendary: +3."), "Staff of the Dead": ("Fine", "Legendary: +6, the best one."),
    "Void Knight Top": ("Fine", "+4; the only armor that pulls its weight."),
    "Rune Mysteries": ("Low", "Standalone reward about zero (both bots); counts toward the Quest Point Cape."),
    "Sheep Shearer": ("Low", "Standalone about zero; counts toward the Quest Point Cape."),
    "Cook's Assistant": ("Low", "Standalone about zero; counts toward the Quest Point Cape."),
    "Doric's Quest": ("Fine", "Standalone about zero; +3 to +8 in the Quest Point Cape deck."),
    "The Restless Ghost": ("Bot-limited", "Greedy never plays it; smart +5.6 vs Stab (noisy)."),
    "Romeo & Juliet": ("Fine", "Only for the Quest Point Cape: +4 to +8 in that deck."),
    "Goblin Diplomacy": ("Fine", "Only for the Quest Point Cape: +3 to +7 in that deck."),
    "Prince Ali Rescue": ("Low", "Standalone about zero; counts toward the Quest Point Cape."),
    "Vampyre Slayer": ("Low", "Standalone about zero (smart +3.9, noisy)."),
    "Ernest the Chicken": ("Low", "Standalone about zero."), "Demon Slayer": ("Low", "Standalone about zero."),
    "Dragon Slayer": ("Low", "Standalone about zero (smart +3.3, noisy)."),
}
CAPE_VERDICT = {
    "Smithing Cape": ("Strong", "+10.6 on Lean Melee, +11.6 on Rune Thrown (the best deck). Candidate: 6 bars (+2 to +3)."),
    "Woodcutting Cape": ("Strong", "+4.5 on Lean Ranged but +10.3 on Yew Ranged. Candidate: 7 logs (+1 to +4)."),
    "Runecrafting Cape": ("Strong", "+8 on Lean Mage and Wave Mage. Candidate: 7 runes (+2.5 to +3.4)."),
    "Prayer Cape": ("Strong", "+10.9 (smart +8.8). Candidate: 3 elite prayers (+1.2)."),
    "Cooking Cape": ("Strong", "+8.8 in a food deck (smart +11.9), +1.4 on Fire Mage. Candidate: 4 food (+3.2)."),
    "Ranged Cape": ("Weak", "Makes Lean Ranged worse (−1.6 greedy, −9.2 smart): 4 hits arrive when the game is already decided. 3 hits: −0.2; 2 hits: +2.4."),
    "Attack Cape": ("Fine", "+6.6 on Lean Melee, +8.7 on Rune Melee."),
    "Magic Cape": ("Fine", "+6.2 on Lean Mage, +2.3 on Mist Mage."),
    "Defence Cape": ("Fine", "+4.4."),
}
VERDICT.update({
    "Rune Pouch": ("Low", "Small (0.6/copy) but played often; keeps rune decks smooth."),
    "Lowe": ("Low", "Small."), "Doric": ("Low", "Small."), "Mithril Darts": ("Low", "Small (0.8/copy)."),
})
RESOURCE = {"Air Rune", "Water Rune", "Earth Rune", "Fire Rune", "Chaos Rune", "Death Rune", "Blood Rune",
            "Bronze Bar", "Mithril Bar", "Rune Bar", "Normal Logs", "Willow Logs", "Yew Logs"}


def verdict(r):
    if r["kind"] == "Cape":
        return CAPE_VERDICT.get(r["card"], ("Fine", ""))
    if r["card"] in RESOURCE:
        return ("Resource", "Tested through the cards it unlocks; 2 copies are worth "
                f"{r['g_val']:+.1f} in {r['test'].split(':')[0]}.")
    return VERDICT.get(r["card"], ("Fine", ""))
