"""Round 15: Capes. Each player brings one cape, chosen blind, and both are revealed at the start.
Either player can claim either cape once they meet its condition: claiming is an action, each cape
can be claimed once, and you can wear only one. A worn cape can't be removed; at the start of your
turn it gains a counter, and at `cape_turns` counters (3, adopted Round 15) its wearer wins.

need: the count to reach. Event capes count things you do over the game; state capes check what you
have in play right now. Needs tuned in Round 15 (cape_zone.py --v3); the Quest Point Cape is unsolved."""

CAPES = {
    # name:               (kind,    what,           need)
    "Quest Point Cape":   ("state", "qp",           6),    # quest points
    "Attack Cape":        ("event", "melee_hit",    6),    # melee hits that damage the deck (Punch counts)
    "Ranged Cape":        ("event", "ranged_hit",   3),    # Round 23: was 4
    "Magic Cape":         ("event", "magic_hit",    3),
    "Defence Cape":       ("event", "soak",         12),   # damage your shields block
    "Prayer Cape":        ("event", "elite_prayer", 2),    # elite prayers activated (basic ones don't count)
    "Cooking Cape":       ("event", "food",         3),    # food eaten
    "Smithing Cape":      ("state", "bar",          6),    # Round 23: was 5   # bars in play
    "Woodcutting Cape":   ("state", "log",          7),    # Round 23: was 6   # logs in play
    "Runecrafting Cape":  ("state", "rune",         7),    # Round 23: was 6   # runes in play
}


def progress(p, cape, tune=None):
    """(have, need) for player p toward a cape."""
    kind, what, need = CAPES[cape]
    if tune and cape in tune:
        need = tune[cape]
    if kind == "event":
        return p.cape_prog[what], need
    if what == "qp":
        return p.qp, need
    return p.family_count(what), need
