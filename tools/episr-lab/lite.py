# "Lite": a 5-tap (bilinear fetches) Keys/Catmull-Rom style upscaler, one pass, no branches. Corner taps dropped.
import numpy as np, cv2, lab, itertools
def keys(d, a):
    d = np.abs(d); return np.where(d <= 1, (a + 2) * d ** 3 - (a + 3) * d ** 2 + 1, np.where(d < 2, a * d ** 3 - 5 * a * d ** 2 + 8 * a * d - 4 * a, 0.0))
def up_lite(img, ow, oh, a=-0.5, clampm=None, sharp=0.0, S=None, **k):
    h, w = img.shape[:2]; img = img.astype(np.float32)
    px = (np.arange(ow) + 0.5) * w / ow - 0.5; py = (np.arange(oh) + 0.5) * h / oh - 0.5
    def axis(p):
        i0 = np.floor(p); f = p - i0
        w0, w1, w2, w3 = keys(1 + f, a), keys(f, a), keys(1 - f, a), keys(2 - f, a)
        w12 = w1 + w2; return i0 - 1, i0 + w2 / w12, i0 + 2, w0, w12, w3
    xa, xb, xc, wxa, wxb, wxc = axis(px); ya, yb, yc, wya, wyb, wyc = axis(py)
    def tap(x, y): return cv2.remap(img, np.broadcast_to(x[None, :], (oh, ow)).astype(np.float32), np.broadcast_to(y[:, None], (oh, ow)).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
    W = lambda a_, b_: (a_[None, :] * b_[:, None])[..., None]
    c = tap(xb, yb); l = tap(xa, yb); r = tap(xc, yb); u = tap(xb, ya); d = tap(xb, yc)
    wc, wl, wr, wu, wd = W(wxb, wyb), W(wxa, wyb), W(wxc, wyb), W(wxb, wya), W(wxb, wyc)
    out = (c * wc + l * wl + r * wr + u * wu + d * wd) / (wc + wl + wr + wu + wd)
    if sharp:                                   # unsharp from the same five taps: centre minus the mean of the four outer taps, limited like CAS (less where contrast is already high)
        blur = 0.25 * (l + r + u + d); det = c - blur
        mx = np.maximum(np.maximum(l, r), np.maximum(np.maximum(u, d), c)); mn = np.minimum(np.minimum(l, r), np.minimum(np.minimum(u, d), c))
        cas = np.sqrt(np.clip(np.minimum(mn, 1 - mx) / np.maximum(mx, 1e-3), 0, 1))
        out = out + sharp * cas * det
    if clampm is not None:                     # anti-ringing: stay within the range of the five taps, plus a margin
        st = np.stack([c, l, r, u, d]); lo, hi = st.min(0), st.max(0); m = clampm * (hi - lo); out = np.clip(out, lo - m, hi + m)
    return np.clip(out, 0, 1)
if __name__ == '__main__':
    names = ['gtav_desert', 'rdr_canyon', 'gtav_villa', 'rdr_sunset']; data = []
    for n in names:
        z = np.load('nn_cache/%s_area.npz' % n); g = z['g']; h, w = g.shape[:2]; c = g[h // 4:h // 4 + 480, w // 4:w // 4 + 800].copy(); data.append((c, lab.render_small(c, 1 / 1.5, 'area')))
    def ev(f):
        r = []
        for c, s in data:
            h, w = c.shape[:2]; o = np.asarray(f(s, w, h), np.float32); m = lab.metrics(o, c); r.append([m['psnr'], m['sharp'], m['ring'], m['ssim']])
        return np.mean(r, 0)
    print('%-34s %7s %7s %7s %7s' % ('method', 'psnr', 'sharp', 'ring', 'ssim'))
    rows = [('bilinear', lambda s, w, h: lab.up_bilinear(s, w, h)), ('FSR 1 RCAS stops=0.2', lambda s, w, h: lab.up_fsr1(s, w, h, rcas_stops=0.2)), ('FSR 1 EASU only', lambda s, w, h: lab.up_fsr1(s, w, h, rcas_stops=None)),
            ('EpiSR 2 Crisp', lambda s, w, h: lab.up_epi2(s, w, h, ls=2.5, lclamp=0.035, loff=1.0, lfloor=0.02))]
    for a in (-0.5, -0.75, -1.0): rows.append(('Lite a=%.2f' % a, (lambda a: lambda s, w, h: up_lite(s, w, h, a=a))(a)))
    for a, cm in itertools.product((-0.75, -1.0), (0.0, 0.1)): rows.append(('Lite a=%.2f clamp %.1f' % (a, cm), (lambda a, cm: lambda s, w, h: up_lite(s, w, h, a=a, clampm=cm))(a, cm)))
    rows = rows[:4]
    for a, sh, cm in itertools.product((-1.0, -1.25), (0.5, 1.0, 1.5), (None, 0.15)): rows.append(('Lite a=%.2f sharp %.1f clamp %s' % (a, sh, cm), (lambda a, sh, cm: lambda s, w, h: up_lite(s, w, h, a=a, sharp=sh, clampm=cm))(a, sh, cm)))
    for n_, f in rows: print('%-34s %7.2f %7.3f %7.3f %7.4f' % (n_, *ev(f)), flush=True)
