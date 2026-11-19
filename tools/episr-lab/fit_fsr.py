# How close can EpiSR 2's own knobs get to the FSR 1 + RCAS look? Fit them to FSR's output (not to the original).
import numpy as np, cv2, lab, time, json
names = ['gtav_desert', 'rdr_canyon', 'gtav_villa', 'rdr_sunset']
crops = []
for n in names:
    z = np.load('nn_cache/%s_area.npz' % n); g = z['g']; h, w = g.shape[:2]
    c = g[h // 4:h // 4 + 480, w // 4:w // 4 + 800].copy(); crops.append((n, c))
data = []
for n, c in crops:
    s = lab.render_small(c, 1 / 1.5, 'area'); h, w = c.shape[:2]
    data.append((n, c, s, np.asarray(lab.up_fsr1(s, w, h), np.float32)))
def score(p, which=range(len(data))):
    r = []
    for i in which:
        n, c, s, f = data[i]; h, w = c.shape[:2]
        o = np.asarray(lab.up_epi2(s, w, h, **p), np.float32); r.append(lab.psnr(o[8:-8, 8:-8], f[8:-8, 8:-8]))
    return float(np.mean(r))
base = dict(ls=1.5, lclamp=0.02, loff=1.5, lfloor=0.02)
grid = dict(boost=[2, 3, 4, 6], lim=[0.02, 0.05, 0.1, 0.2], rsoft=[0.15, 0.3, 0.5], dlim=[0.12, 0.2, 0.35], ls=[1.5, 2.5, 4], lclamp=[0.02, 0.05, 0.1], loff=[1.0, 1.5, 2.0])
p = dict(base); best = score(p); print('start (app defaults) vs FSR: %.2f dB' % best, flush=True)
for rnd in range(2):
    for k, vals in grid.items():
        for v in vals:
            q = dict(p); q[k] = v
            if q == p: continue
            sc = score(q)
            if sc > best + 0.01: best, p = sc, q; print('  %s=%s -> %.2f dB' % (k, v, best), flush=True)
print('best', json.dumps(p), '%.2f dB vs FSR' % best)
print('%-12s %-22s %8s %8s %8s' % ('image', 'EpiSR 2 setting', 'vs FSR', 'vs orig', 'ring'))
for n, c, s, f in data:
    h, w = c.shape[:2]
    for lbl, q in (('app defaults', base), ('fitted to FSR', p)):
        o = np.asarray(lab.up_epi2(s, w, h, **q), np.float32); m = lab.metrics(o, c)
        print('%-12s %-22s %8.2f %8.2f %8.3f' % (n, lbl, lab.psnr(o[8:-8, 8:-8], f[8:-8, 8:-8]), m['psnr'], m.get('ring', float('nan'))))
    m = lab.metrics(f, c); print('%-12s %-22s %8s %8.2f %8.3f' % (n, 'FSR 1 + RCAS', '-', m['psnr'], m.get('ring', float('nan'))))
json.dump(p, open('fit_fsr.json', 'w'))
