import numpy as np, lab
from lite import up_lite
names = ['gtav_desert', 'rdr_canyon', 'gtav_villa', 'rdr_sunset']
for S, mode in ((1.5, 'point'), (2.0, 'area'), (1.25, 'area')):
    data = []
    for n in names:
        z = np.load('nn_cache/%s_area.npz' % n); g = z['g']; h, w = g.shape[:2]; c = g[h // 4:h // 4 + 480, w // 4:w // 4 + 800].copy(); data.append((c, lab.render_small(c, 1 / S, mode)))
    def ev(f):
        r = []
        for c, s in data:
            h, w = c.shape[:2]; o = np.asarray(f(s, w, h), np.float32); m = lab.metrics(o, c); r.append([m['psnr'], m['sharp'], m['ring'], m['ssim']])
        return np.mean(r, 0)
    print('--- x%.2f %s' % (S, mode)); print('%-30s %7s %7s %7s %7s' % ('method', 'psnr', 'sharp', 'ring', 'ssim'))
    for n_, f in (('bilinear', lambda s, w, h: lab.up_bilinear(s, w, h)), ('FSR 1 RCAS 0.2', lambda s, w, h: lab.up_fsr1(s, w, h, rcas_stops=0.2)),
                  ('EpiSR 2 Crisp', lambda s, w, h: lab.up_epi2(s, w, h, ls=2.5, lclamp=0.035, loff=1.0, lfloor=0.02)),
                  ('Lite a=-1 sharp 0.5', lambda s, w, h: up_lite(s, w, h, a=-1.0, sharp=0.5)), ('Lite a=-1 sharp 0.5 clamp.15', lambda s, w, h: up_lite(s, w, h, a=-1.0, sharp=0.5, clampm=0.15))):
        print('%-30s %7.2f %7.3f %7.3f %7.4f' % (n_, *ev(f)), flush=True)
