"""Replay one game turn by turn, in plain English.

    python replay.py "Lean Melee" "Smoke Mage" 7          # deck going first, deck going second, seed
    python replay.py "Lean Melee" "Smoke Mage" 7 > game.txt
    python replay.py "Lean Melee" "Smoke Mage" 7 --reversals   # each deck carries its natural reversals
    python replay.py "Lean Melee" "Smoke Mage" 7 --smart       # both sides played by the playout bot
    python replay.py "Smithing deck" "Magic deck" 3 --capes="Smithing Cape,Magic Cape"   # cape zone

Any deck name from cards.py works (starter, lean, dual-style, counter decks), plus the
Round 7 test decks from new_cards.py ("Lean Ranged + specs", "R7 Smoke Mage", ...).
Change the seed to see a different shuffle.
"""
import sys

import cards
from bot import HeuristicBot
from cards import CARDS
from engine import Game

ALL_DECKS = {**cards.STARTER_DECKS, **cards.LEAN_DECKS, **cards.HYBRID_DECKS, **cards.GEAR_DECKS,
             **cards.OTHER_DECKS, **cards.COUNTER_DECKS}


def _round7():
    import new_cards
    return {**{k: d for k, (_, d) in new_cards.variants().items()},
            **{"R7 " + k: d for k, d in new_cards.round7_field().items()}}


def describe(g, i, a):
    p = g.players[i]
    if a[0] == "draw":
        return "draws a card"
    if a[0] == "punch":
        return "punches"
    if a[0] == "claim":
        return f"claims the {a[1]}"
    if a[0] == "spec":
        return f"uses {p.weapon['name']}'s special attack ({p.energy} energy)"
    if a[0] == "quest":
        q = p.quest["name"]
        step = p.quest["step"] + 1
        return f"does step {step} of {q}" + (f", giving up {a[1]}" if a[1] else "")
    if a[0] in ("swing", "free_swing"):
        extra = " (free, after a spell)" if a[0] == "free_swing" else ""
        return f"swings {p.weapon['name']} as {a[1]}{extra}"
    _, name, target = a
    c = CARDS[name]
    if c.kind == "material":
        return f"plays {name}"
    if c.kind == "destroy":
        return f"plays {name}, destroying the opponent's {target}"
    if c.kind == "pouch":
        return f"plays {name}, fetching {target}"
    if c.kind == "npc":
        return f"plays {name}, recovering {target}"
    if c.kind in ("weapon", "thrown", "bow", "staff"):
        return f"equips {name}"
    if c.kind == "spell":
        return f"casts {name}"
    if c.kind == "buff":
        return f"prays {name}"
    if c.kind == "quest":
        return f"starts {name}"
    if c.kind == "cape":
        return "puts on the Quest Point Cape"
    return f"plays {name}"


class Narrator(HeuristicBot):
    def choose(self, g, i, legal):
        a = super().choose(g, i, legal)
        o = g.opp(i)
        before = o.lp
        shield_before = o.shield["soak"] if o.shield else 0
        text = describe(g, i, a)
        self.pending = (text, before, shield_before)
        return a


def board(p):
    mats = ", ".join(f"{n} x{k}" for n, k in sorted(p.materials.items())) or "none"
    w = p.weapon["name"] + (f" ({p.weapon['dur']} left)" if p.weapon and p.weapon["dur"] else "") if p.weapon else "none"
    ammo = f", ammo {p.ammo['shots']}" if p.ammo else ""
    sh = f"{p.shield['name']} ({p.shield['soak']} soak)" if p.shield else "none"
    extra = (f" | prayer {p.buff}" if p.buff else "") + (f" | quest {p.quest['name']}" if p.quest else "") \
        + (f" | {p.qp} QP" if p.qp else "") + (f" | energy {p.energy}" if p.energy else "") \
        + (f" | WEARING {p.cape_name} ({p.cape} counter{'s' if p.cape != 1 else ''})" if p.cape_name else "")
    return f"LP {p.lp} | weapon {w}{ammo} | shield {sh} | armor {p.armor or 'none'}{extra} | materials: {mats}"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    first, second, seed = args[0], args[1], int(args[2]) if len(args) > 2 else 1
    if first not in ALL_DECKS or second not in ALL_DECKS:
        ALL_DECKS.update(_round7())
        import cape_zone
        ALL_DECKS.update({c.replace(" Cape", "") + " deck": d for c, (_, d) in cape_zone.built().items()})
        ALL_DECKS.update(cape_zone.FIELD)
    decks = [ALL_DECKS[first], ALL_DECKS[second]]
    if "--reversals" in sys.argv:
        decks = [cards.with_reactions(d, cards.NATURAL_REVERSALS.get(n, {})) for d, n in zip(decks, (first, second))]
    if "--smart" in sys.argv:
        from planner import RolloutBot
        bots = [RolloutBot(seed), RolloutBot(seed + 1)]
    else:
        bots = [Narrator(seed), Narrator(seed + 1)]
    capes = None
    for a in sys.argv:
        if a.startswith("--capes="):
            capes = a[len("--capes="):].split(",")
    g = Game(decks, bots, seed=seed, names=(first, second), rules={"capes": capes})
    orig_apply = g.apply

    def apply(i, a):
        o = g.opp(i)
        before, sb = o.lp, (o.shield["soak"] if o.shield else 0)
        text = describe(g, i, a)
        n_log = len(g.log)
        orig_apply(i, a)
        dealt = before - o.lp
        soaked = sb - (o.shield["soak"] if o.shield else 0) if sb else 0
        note = ""
        if dealt > 0 or soaked > 0:
            note = f"  -> {dealt} damage" + (f", {soaked} soaked by shield" if soaked > 0 else "")
        print(f"    {g.players[i].name}: {text}{note}")
        for event in g.log[n_log:]:
            print(f"      ! {event}")
    g.apply = apply

    while g.winner is None and g.turn < 400:
        i = g.active
        p = g.players[i]
        print(f"\nRound {g.turn // 2 + 1}, {p.name}'s turn. Hand: {', '.join(sorted(p.hand))}")
        g.play_turn()
        for j in (0, 1):
            print(f"  {g.players[j].name}: {board(g.players[j])}")
        if g.cape_table:
            from capes import progress
            race = "; ".join(f"{c}: " + ("claimed by " + g.players[g.cape_claimed[c]].name if c in g.cape_claimed
                             else " vs ".join(f"{progress(q, c)[0]}/{progress(q, c)[1]}" for q in g.players))
                             for c in g.cape_table)
            print(f"  Capes: {race}")
    w = g.players[g.winner].name if g.winner is not None else "nobody"
    print(f"\n{w} wins ({g.end_reason}) after {g.turn // 2 + 1} rounds.")


if __name__ == "__main__":
    main()
