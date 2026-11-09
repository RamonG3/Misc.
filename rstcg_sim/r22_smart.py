"""Round 22 batch 1: smart-bot check of the chosen tweaks only.   python r22_smart.py 4 --smart"""
import sys
import r22
CHOSEN = {"Anti-dragon Shield": "soak 7", "Grand Exchange": "free to play"} if "--two" in sys.argv else {"Smite": "+3 to all attacks", "Anti-dragon Shield": "soak 7", "Grand Exchange": "free to play",
          "Fire Blast": "Death + 3 Fire", "Fire Wave": "Blood + 4 Fire"}
if "--two" in sys.argv:
    CHOSEN = {"Anti-dragon Shield": "soak 7", "Grand Exchange": "free to play"}
r22.VARIANTS = {c: {"printed": {}, v: r22.VARIANTS[c][v]} for c, v in CHOSEN.items()}
if __name__ == "__main__":
    r22.cards_main(int([a for a in sys.argv[1:] if not a.startswith("--")][0]), None)
