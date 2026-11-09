"""Round 24 parity: exported decks with Protect prayers vs every other exported deck. python parity24.py 300"""
import sys
import parity19 as p19
p19.staves = lambda d: any(c.startswith("Protect from") for c in d)
if __name__ == "__main__":
    p19.main()
