import json, math, subprocess, sys
import parity19 as p19
pairs = [("Melee", "Lean Melee + Dragon Dagger"), ("Melee", "Lean Melee"), ("Lean Melee", "Lean Melee + Dragon Dagger"), ("Melee", "Hammer Mace"), ("Melee", "Mace Melee")]
n = int(sys.argv[1])
if __name__ == "__main__":
    json.dump([{"a": a, "b": b} for a, b in pairs], open('spec_chk.json', 'w'))
    js = subprocess.Popen(['node', 'spec.js', 'spec_chk.json', str(n), 'out_chk.json'])
    pyr = {k: p19.py((a, b, n, k + 500))[1] for k, (a, b) in enumerate(pairs)}
    js.wait()
    jr = json.load(open('out_chk.json'))
    for k, (a, b) in enumerate(pairs):
        p1, p2 = pyr[k], jr[k][0]
        pp = (p1 + p2) / 2
        print(f"z {abs(p1 - p2) / math.sqrt(pp * (1 - pp) * 2 / n):.1f}  {a} vs {b}: py {p1:.3f} js {p2:.3f}")
