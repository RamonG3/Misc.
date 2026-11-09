"""Round 27 parity: the rebuilt starters vs every other exported deck. python parity27.py 200"""
import parity19 as p19
S = {k: dict(v) for k, v in p19.D.items() if k in ("Melee", "Ranged", "Air Mage")}
p19.staves = lambda d: d in S.values()
if __name__ == "__main__":
    p19.main()
