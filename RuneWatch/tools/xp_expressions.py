#!/usr/bin/env python3
"""Builds the WFF expressions that turn today's steps into an Agility level.

One step = 1 XP, on the real RuneScape experience curve, so 10,000 steps is level 27
and level 99 needs 13,034,431 steps in a day. Good luck.

WFF v1 has no variables or loops, so the lookup is a balanced tree of ternaries
(about 7 comparisons deep). Prints the two expressions; paste them into watchface.xml.
"""
import math

SOURCE = "[STEP_COUNT]"
MAX_LEVEL = 99


def xp_table():
    """xp[level] = XP needed to reach that level (xp[1] == 0, xp[2] == 83, xp[99] == 13,034,431)."""
    xp = {1: 0}
    points = 0
    for lvl in range(1, MAX_LEVEL):
        points += math.floor(lvl + 300 * 2 ** (lvl / 7))
        xp[lvl + 1] = points // 4
    return xp


XP = xp_table()
assert XP[2] == 83 and XP[92] == 6_517_253 and XP[99] == 13_034_431


def tree(lo, hi, leaf):
    """Expression for the value at the level L in [lo, hi] where XP[L] <= steps < XP[L+1]."""
    if lo == hi:
        return leaf(lo)
    mid = (lo + hi + 1) // 2
    return f"({SOURCE} >= {XP[mid]} ? {tree(mid, hi, leaf)} : {tree(lo, mid - 1, leaf)})"


def level_expr():
    return tree(1, MAX_LEVEL, str)


def progress_expr():
    def leaf(lvl):
        if lvl == MAX_LEVEL:
            return "1"
        return f"(({SOURCE} - {XP[lvl]}) / {XP[lvl + 1] - XP[lvl]})"
    return tree(1, MAX_LEVEL, leaf)


def level_of(steps):
    return max(l for l in XP if XP[l] <= steps)


if __name__ == "__main__":
    print("LEVEL:\n" + level_expr() + "\n")
    print("PROGRESS (0..1 towards the next level):\n" + progress_expr() + "\n")
    for s in (0, 82, 83, 5_000, 10_000, 20_000, 13_034_431):
        print(f"{s:>10,} steps -> level {level_of(s)}")
