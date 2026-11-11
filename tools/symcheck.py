#!/usr/bin/env python3
"""Report undefined dynamic symbols of prebuilt ELF files that no library on their
search path defines.  usage: symcheck.py <recovery root> <libdir1:libdir2...> <elf>..."""
import subprocess, sys, os
root, path, elfs = sys.argv[1], sys.argv[2].split(':'), sys.argv[3:]
def nm(f, undef):
    out = subprocess.run(['nm', '-D', '--defined-only' if not undef else '--undefined-only', f],
                         capture_output=True, text=True).stdout.split('\n')
    return {l.split()[-1].split('@')[0] for l in out if l.strip() and (not undef or l.split()[0] == 'U')}
def needed(f):
    out = subprocess.run(['readelf', '-d', f], capture_output=True, text=True).stdout
    return [l.split('[')[1].rstrip(']') for l in out.split('\n') if '(NEEDED)' in l]
def find(lib):
    for d in path:
        p = os.path.join(root, d.lstrip('/'), lib)
        if os.path.exists(p): return p
bad = 0
for e in elfs:
    seen, todo, defs = set(), needed(e), set()
    while todo:
        l = todo.pop()
        if l in seen: continue
        seen.add(l); p = find(l)
        if not p: print(f'{e}: MISSING lib {l}'); bad += 1; continue
        defs |= nm(p, False); todo += needed(p)
    miss = sorted(nm(e, True) - defs)
    print(f'{e}: {len(miss)} unresolved' + ''.join('\n   ' + m for m in miss[:20]))
    bad += len(miss)
sys.exit(1 if bad else 0)
