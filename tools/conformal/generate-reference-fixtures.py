"""Independent high-precision evaluation of the pinned CCAM octant formula.

SPDX-License-Identifier: GPL-3.0-or-later. See COPYING and README.md.
Requires mpmath; never downloads sources. Run with the original jimcc.f90 path.
"""
import hashlib
import re
import sys
from pathlib import Path
import mpmath as mp

source = Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(source).hexdigest() == '99cdf7d5caf75ad1712c31e6de9f99e89643ba523e03b56c5e191b7ffaf8862b'
text = source.decode('utf-8')
table = text.split('real, dimension(30) :: a =')[1].split('/)')[0]
mp.mp.dps = 70
coefficients = [mp.mpf(s) for s in re.findall(r'-?\d+\.\d+', table)]
assert len(coefficients) == 30
print('u,v,normal,first,second')
for sx, sy in [('0.3', '0.1'), ('0.125', '0.0625'), ('0.4', '0.2'), ('0.02', '0.01')]:
    x, y = mp.mpf(sx), mp.mpf(sy)
    z = mp.mpc(x, y) ** 4
    # Direct power sum, not the Java implementation's Horner recurrence.
    p = sum(a * z ** (i + 1) for i, a in enumerate(coefficients))
    w = mp.exp(mp.j * mp.pi / 6) / mp.sqrt(2) * (-mp.j * p) ** (mp.mpf(1) / 3)
    h = 2 / (1 + abs(w) ** 2)
    a, b, c = h * w.real, h * w.imag, h - 1
    n = a / mp.sqrt(6) + b / mp.sqrt(2) + c / mp.sqrt(3)
    u = 2 * a / mp.sqrt(6) - c / mp.sqrt(3)
    v = -a / mp.sqrt(6) + b / mp.sqrt(2) - c / mp.sqrt(3)
    print(','.join(mp.nstr(q, 35) for q in [1 - 2*x, 1 - 2*y, n, -u, -v]))
