import numpy as np, lab, itertools
names = ['gtav_desert', 'rdr_canyon', 'gtav_villa', 'rdr_sunset']
data = []
for n in names:
    z = np.load('nn_cache/%s_area.npz' % n); g = z['g']; h, w = g.shape[:2]; c = g[h // 4:h // 4 + 480, w // 4:w // 4 + 800].copy()
    data.append((c, lab.render_small(c, 1 / 1.5, 'area')))
def ev(f):
    r = []
    for c, s in data:
        h, w = c.shape[:2]; o = np.asarray(f(s, w, h), np.float32); m = lab.metrics(o, c); r.append([m['psnr'], m['sharp'], m['ring'], m['ssim']])
    return np.mean(r, 0)
print('%-34s %7s %7s %7s %7s' % ('method', 'psnr', 'sharp', 'ring', 'ssim'))
for st in (0.0, 0.2, 0.5, None): print('%-34s %7.2f %7.3f %7.3f %7.4f' % ('FSR 1 RCAS stops=%s' % st, *ev(lambda s, w, h: lab.up_fsr1(s, w, h, rcas_stops=st))))
base = dict(lfloor=0.02, loff=1.0)
for boost, ls, lc, lim in itertools.product((2, 3), (1.5, 2.5), (0.02, 0.035, 0.05), (0.02, 0.05)):
    p = dict(base, boost=boost, ls=ls, lclamp=lc, lim=lim)
    print('%-34s %7.2f %7.3f %7.3f %7.4f' % ('EP2 b%g ls%g lc%g lim%g' % (boost, ls, lc, lim), *ev(lambda s, w, h: lab.up_epi2(s, w, h, **p))), flush=True)
