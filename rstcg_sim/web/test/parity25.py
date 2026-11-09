"""Round 25 parity: exported decks with destroy cards vs every other exported deck. python parity25.py 300"""
import parity19 as p19
p19.staves = lambda d: any(c in d for c in ("Tinderbox", "Superheat", "Gunnar's Crusade"))
if __name__ == "__main__":
    p19.main()
