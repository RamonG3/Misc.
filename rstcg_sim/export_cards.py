"""Write the card pool, decks and capes the browser game uses to web/cards.json.

    python export_cards.py
"""
import dataclasses
import json

import cape_zone
import cards
import hybrid_cards
from capes import CAPES
from cards import CARDS, Card

DEFAULTS = {f.name: f.default for f in dataclasses.fields(Card)}


def card_json(c):
    out = {}
    for k, v in dataclasses.asdict(c).items():
        if k != "name" and v == DEFAULTS.get(k):
            continue
        out[k] = v
    return out


def legend_variant(deck, legend, cut):
    d = dict(deck)
    d[cut] -= 1
    if not d[cut]:
        del d[cut]
    d[legend] = 1
    return d


def main():
    field = hybrid_cards.round11_field()
    built = cape_zone.built()
    decks = []
    for name, d in cards.STARTER_DECKS.items():
        decks.append({"name": name, "group": "Starter decks", "cards": d})
    for name, d in field.items():
        decks.append({"name": name, "group": "Tournament decks", "cards": d})
    extra = {"Quest Point Cape": "Quest Melee", "Defence Cape": "Defence Melee",
             "Prayer Cape": "Prayer Melee", "Cooking Cape": "Cooking Melee"}
    for cape, label in extra.items():
        decks.append({"name": label, "group": "Cape decks", "cards": built[cape][1], "cape": cape})
    legend = {"Lean Melee": ("Dragon Dagger", "Vanaka"), "Lean Ranged": ("Dark Bow", "Wooden Shield"),
              "Lean Mage": ("Staff of the Dead", "Banker"), "Thrown Ranged": ("Granite Maul", "Wooden Shield"),
              "Smoke Mage": ("Staff of the Dead", "Wooden Shield")}
    import group1
    pk = group1.packages()
    for name, src in (("Mace Melee", "Mace Melee (Bronze/Mithril Mace, Crush)"), ("Rune Melee", "Rune Melee (built for tier 3)"),
                      ("Rune Thrown", "Rune Thrown (built for tier 3)"), ("Earth Mage", "Earth Mage, modern build")):
        decks.append({"name": name, "group": "Round 17 decks", "cards": pk[src][1]})
    import group2
    g2 = group2.packages()
    for name, src in (("Water Mage", "Water Mage (Lean Mage on Water)"), ("Mist Mage", "Mist Mage (Air/Water flurry)"),
                      ("Dust Mage", "Dust Mage (Air/Earth flurry)"), ("Varrock Melee", "Lean Melee + Varrock, Tablets"),
                      ("Hammer Mace", "Mace Melee + Smith's Hammer")):
        decks.append({"name": name, "group": "Round 18 decks", "cards": g2[src][1]})
    for name, (leg, cut) in legend.items():
        decks.append({"name": f"{name} + {leg}", "group": "Legendary decks",
                      "cards": legend_variant(field[name], leg, cut)})
    fits = {   # capes each deck is built to reach; the bot picks one of these, blind
        **{k: [v] for k, v in cards.STARTER_CAPES.items()},     # Round 27: each starter suggests one cape
        "Lean Melee": ["Attack Cape", "Smithing Cape"],
        "Lean Mage": ["Magic Cape", "Runecrafting Cape"], "Lean Ranged": ["Ranged Cape", "Woodcutting Cape"],
        "Smoke Mage": ["Magic Cape", "Runecrafting Cape"], "Thrown Ranged": ["Smithing Cape", "Ranged Cape"],
        "Mage/Ranged+": ["Magic Cape", "Ranged Cape"], "Mage/Melee+": ["Magic Cape", "Attack Cape"],
        "Melee/Ranged+": ["Attack Cape", "Ranged Cape"], "Melee/Thrown": ["Smithing Cape", "Attack Cape"],
        "Mace Melee": ["Attack Cape", "Smithing Cape"], "Rune Melee": ["Smithing Cape", "Attack Cape"],
        "Rune Thrown": ["Smithing Cape", "Ranged Cape"], "Earth Mage": ["Magic Cape", "Runecrafting Cape"],
        "Water Mage": ["Magic Cape", "Runecrafting Cape"], "Mist Mage": ["Magic Cape", "Runecrafting Cape"],
        "Dust Mage": ["Magic Cape", "Runecrafting Cape"], "Varrock Melee": ["Attack Cape", "Smithing Cape"],
        "Hammer Mace": ["Attack Cape", "Smithing Cape"],
    }
    for d in decks:
        d["cards"] = cards.modernize(d["cards"])        # Round 22: Cloth, Leather, Thessalia cut
        assert not set(d["cards"]) & cards.RETIRED, d["name"]
        cards.check_decks({d["name"]: d["cards"]})
        d["capes"] = [d.pop("cape")] if "cape" in d else fits[d["name"].split(" + ")[0]]
    used = sorted(n for n in CARDS if n not in cards.RETIRED and n != "Blank")   # the whole pool, minus cut cards
    # Round 26: printed names that differ from the simulator's (the sim keeps its name so old experiments run)
    PRINTED = {"Varrock Teleport Tablet": "Varrock Teleport"}
    pn = lambda n: PRINTED.get(n, n)
    for d in decks:
        d["cards"] = {pn(k): v for k, v in d["cards"].items()}
    out = {
        "cards": {pn(n): {**card_json(CARDS[n]), "name": pn(n)} for n in used},
        "decks": decks,
        "capes": {k: {"kind": v[0], "what": v[1], "need": v[2]} for k, v in CAPES.items()},
    }
    with open("web/cards.json", "w") as f:
        json.dump(out, f, indent=1)
    print(len(used), "cards,", len(decks), "decks,", len(CAPES), "capes")


if __name__ == "__main__":
    main()
