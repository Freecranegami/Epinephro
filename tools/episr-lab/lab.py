#!/usr/bin/env python3
"""EpiSR offline lab.
Takes full-resolution screenshots (ground truth), renders them small like a game running at a lower resolution, upscales them with
several upscalers (numpy ports of the shaders) and scores/compares the result against the original.

  python3 lab.py shots/*.png --scale 0.667 --out out
  python3 lab.py --synthetic --scale 0.667 --out out       (no screenshots needed, uses generated test images)
"""
import argparse, glob, os, sys
import numpy as np, cv2
from skimage.metrics import structural_similarity

# ---------- helpers ----------
def to_f(img): return img.astype(np.float32) / 255.0
def to_u8(a): return (np.clip(a, 0, 1) * 255.0 + 0.5).astype(np.uint8)

def render_small(gt, scale, mode):
    h, w = gt.shape[:2]
    sw, sh = int(round(w * scale)), int(round(h * scale))
    interp = {'area': cv2.INTER_AREA, 'point': cv2.INTER_NEAREST, 'linear': cv2.INTER_LINEAR}[mode]
    return cv2.resize(gt, (sw, sh), interpolation=interp)

def sample_bilinear(img, px, py):
    """GPU-style bilinear: px,py in input pixel units where texel centres are at +0.5, clamp to edge."""
    h, w = img.shape[:2]
    x = px - 0.5; y = py - 0.5
    x0 = np.floor(x).astype(np.int32); y0 = np.floor(y).astype(np.int32)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    def t(i, j): return img[np.clip(j, 0, h - 1), np.clip(i, 0, w - 1)]
    return (t(x0, y0) * (1 - fx) + t(x0 + 1, y0) * fx) * (1 - fy) + (t(x0, y0 + 1) * (1 - fx) + t(x0 + 1, y0 + 1) * fx) * fy

class Grid:
    """Per output pixel: footprint indices (i, j), fractional position pl (as in the SGSR shader) and a texel fetch."""
    def __init__(self, img, ow, oh):
        self.img = img; h, w = img.shape[:2]; self.h, self.w = h, w
        ox, oy = np.meshgrid(np.arange(ow), np.arange(oh))
        u = (ox + 0.5) / ow; v = (oy + 0.5) / oh
        self.px = u * w; self.py = v * h
        ic_x = self.px - 0.5; ic_y = self.py + 0.5
        self.i = np.floor(ic_x).astype(np.int32); self.j = np.floor(ic_y).astype(np.int32)
        self.plx = (ic_x - self.i).astype(np.float32); self.ply = (ic_y - self.j).astype(np.float32)
        self.bil = sample_bilinear(img, self.px, self.py)
    def T(self, di, dj):   # texel (i+di, j+dj), all channels
        return self.img[np.clip(self.j + dj, 0, self.h - 1), np.clip(self.i + di, 0, self.w - 1)]
    def G(self, di, dj):   # green channel only (SGSR works on green)
        return self.T(di, dj)[..., 1]

# the 12 gathered texels of SGSR, as (di, dj): the 2x2 centre (0..1, -1..0) plus the 8 around it
TAPS = {'ux': (0, -2), 'uy': (1, -2), 'uz': (1, 1), 'uw': (0, 1), 'lx': (-1, 0), 'ly': (0, 0), 'lz': (0, -1), 'lw': (-1, -1),
        'rx': (1, 0), 'ry': (2, 0), 'rz': (2, -1), 'rw': (1, -1)}

def lanczos2_fast(x):
    wa = x - 4.0
    wb = x * wa - wa
    return wb * wa * wa

# ---------- upscalers ----------
def up_bilinear(img, ow, oh, **k): return Grid(img, ow, oh).bil

def _cv(img, ow, oh, flag):
    return cv2.resize(img, (ow, oh), interpolation=flag)
def up_bicubic(img, ow, oh, **k): return np.clip(_cv(img, ow, oh, cv2.INTER_CUBIC), 0, 1)
def up_lanczos(img, ow, oh, **k): return np.clip(_cv(img, ow, oh, cv2.INTER_LANCZOS4), 0, 1)

def _sgsr(img, ow, oh, quality, sharp=2.0, overshoot=0.0, limit=23 / 255, soft=0.0, edge=8 / 255, noise=0.0, **k):
    g = Grid(img, ow, oh)
    col = g.bil.copy()
    gy = g.bil[..., 1]
    ly, lz = g.G(0, 0), g.G(0, -1)
    vote = np.abs(lz - ly) + np.abs(gy - ly) + np.abs(gy - lz)
    m = vote > edge
    v = {n: g.G(*o) for n, o in TAPS.items()}
    mean = (v['ly'] + v['lz'] + v['rx'] + v['rw']) * 0.25
    d = {n: a - mean for n, a in v.items()}
    cw = gy - mean
    s = sum(np.abs(a) for a in d.values())
    s = np.maximum(s, 1e-12)
    if quality:
        std = (10.14185 / s) ** 2
        rxlz = d['rx'] - d['lz']; rwly = d['rw'] - d['ly']
        dx_ = rxlz + rwly; dy_ = rxlz - rwly
        inv = 1.0 / np.sqrt(dx_ * dx_ + 3.07574e-05 + dy_ * dy_)
        ex, ey = dx_ * inv, dy_ * inv
    else:
        std = 2.181818 / s
    sw = np.zeros_like(s); sy = np.zeros_like(s)
    for n, (ti, tj) in TAPS.items():
        dx = g.plx - ti; dy = g.ply - (tj + 1)
        c = d[n]
        if quality:
            ed = dx * ey + dy * ex
            x = (dx * dx + dy * dy) + ed * ed * (np.clip(c * c * std, 0, 1) * 0.7 - 1.0)
        else:
            x = (dx * dx + dy * dy) * 0.55 + np.clip(np.abs(c) * std, 0, 1)
        w = lanczos2_fast(x)
        if soft > 0:
            sx = np.maximum(1 - x * 0.25, 0); w = w * (1 - soft) + 64 * sx * sx * soft
        sw += w; sy += w * c
    fy = sy / sw
    mx = np.maximum(np.maximum(d['ly'], d['lz']), np.maximum(d['rx'], d['rw']))
    mn = np.minimum(np.minimum(d['ly'], d['lz']), np.minimum(d['rx'], d['rw']))
    kk = np.clip(s / noise, 0, 1) if noise > 0 else 1.0
    if noise > 0: kk = kk * kk * (3 - 2 * kk)
    rng = (mx - mn) * overshoot
    fy = np.clip((1 + (sharp - 1) * kk) * fy, mn - rng, mx + rng)
    delta = np.clip(fy - cw, -limit, limit)
    out = col + delta[..., None]
    out = np.where(m[..., None], out, col)
    return np.clip(out, 0, 1)

def up_sgsr1(img, ow, oh, **k): return _sgsr(img, ow, oh, False)
def up_sgsrq(img, ow, oh, **k): return _sgsr(img, ow, oh, True)
def up_episr1(img, ow, oh, **k): return _sgsr(img, ow, oh, True, **k)

def up_episr2(img, ow, oh, sig_n=0.42, sig_t=0.95, sig_iso=0.62, boost=0.9, lim=0.12, noise=0.04, edge=6 / 255, kern=0, gsig=0.0, poly=0, gpoly=0.0, tilt=1.0, cohmode=0, **k):
    """EpiSR v2 prototype: structure tensor -> anisotropic positive (gaussian) kernel, soft-limited detail boost. Uses only the
    SGSR 12-texel footprint (so it can be one gather-based shader)."""
    g = Grid(img, ow, oh)
    v = {n: g.G(*o) for n, o in TAPS.items()}
    A, B, C, D = v['lz'], v['rw'], v['ly'], v['rx']
    px, py = g.plx, g.ply
    # gradients at the four centre pixels (central differences inside the footprint)
    gxA, gyA = B - v['lw'], C - v['uw'] * 0 - v['ux']
    gxB, gyB = v['rz'] - A, D - v['uy']
    gxC, gyC = D - v['lx'], v['uw'] - A
    gxD, gyD = v['ry'] - C, v['uz'] - B
    wA = (1 - px) * (1 - py); wB = px * (1 - py); wC = (1 - px) * py; wD = px * py
    jxx = wA * gxA * gxA + wB * gxB * gxB + wC * gxC * gxC + wD * gxD * gxD
    jyy = wA * gyA * gyA + wB * gyB * gyB + wC * gyC * gyC + wD * gyD * gyD
    jxy = wA * gxA * gyA + wB * gxB * gyB + wC * gxC * gyC + wD * gxD * gyD
    tr = jxx + jyy
    dis = np.sqrt((jxx - jyy) ** 2 + 4 * jxy * jxy)
    coh = dis / (tr + 1e-6)                        # 0 flat/isotropic .. 1 clean edge
    energy = np.sqrt(tr)
    # normal direction (across the edge): major eigenvector of J
    th = 0.5 * np.arctan2(2 * jxy, jxx - jyy)
    nx, ny = np.cos(th), np.sin(th)
    kk0 = np.clip(energy / noise, 0, 1); kk0 = kk0 * kk0 * (3 - 2 * kk0)
    ec = np.clip((energy - edge * 0.5) / (edge * 2), 0, 1)       # edge confidence from gradient energy
    a = np.clip((np.maximum(np.maximum(v['ly'], v['lz']), g.bil[..., 1]) - np.minimum(np.minimum(v['ly'], v['lz']), g.bil[..., 1]) - edge) / (2 * edge), 0, 1) if cohmode == 3 else (coh * ec) if cohmode == 0 else ec if cohmode == 1 else np.clip((np.maximum(np.maximum(A, B), np.maximum(C, D)) - np.minimum(np.minimum(A, B), np.minimum(C, D)) - edge) / (2 * edge), 0, 1)
    sn = sig_iso + (sig_n - sig_iso) * a
    st = sig_iso + (sig_t - sig_iso) * a
    sw = np.zeros_like(px); sy = np.zeros_like(px)
    for n, (ti, tj) in TAPS.items():
        dx = ti - px; dy = (tj + 1) - py             # vector from the output sample to the texel
        dn = dx * nx + dy * ny; dt = -dx * ny + dy * nx
        if kern == 2:                                            # separable Lanczos-2 in a frame turned toward the edge by a*theta
            ca, sa = np.cos(tilt * a * th), np.sin(tilt * a * th)
            u = dx * ca + dy * sa; vv = -dx * sa + dy * ca
            if poly:
                def L(r):                                          # Catmull-Rom cubic, two multiply-adds per branch
                    r = np.abs(r); r2 = r * r
                    return np.where(r < 1, 1.5 * r2 * r - 2.5 * r2 + 1, np.where(r < 2, -0.5 * r2 * r + 2.5 * r2 - 4 * r + 2, 0.0))
            else: L = lambda r: np.where(np.abs(r) < 2, np.sinc(r) * np.sinc(r / 2), 0.0)
            w = L(u / sn) * L(vv / st)
        elif kern == 0: w = np.exp(-0.5 * (dn * dn / (sn * sn) + dt * dt / (st * st)))
        else:                                                    # Lanczos-2 of the anisotropic distance (negative lobes: sharper)
            r = np.sqrt(dn * dn / (sn * sn) + dt * dt / (st * st)) * 1.0
            w = np.where(r < 2, np.sinc(r) * np.sinc(r / 2), 0.0)
        sw += w; sy += w * v[n]
    rec = sy / sw
    if kern == 2 and gsig < 0:                                   # unsharp against the hardware bilinear read (free)
        det2 = rec - g.bil[..., 1]; rec = rec + boost * a * kk0 * det2 / (1 + np.abs(det2) / lim); boost = 0
    elif kern == 2 and gsig > 0:                                   # unsharp from the same 12 taps: Lanczos minus a Gaussian of them
        gw = 0; gs = 0
        for n, (ti, tj) in TAPS.items():
            dx = ti - px; dy = (tj + 1) - py; ww = (np.maximum(1 - (dx * dx + dy * dy) * gpoly, 0) ** 2) if gpoly else np.exp(-0.5 * (dx * dx + dy * dy) / (gsig * gsig)); gw += ww; gs += ww * v[n]
        det2 = rec - gs / gw
        rec = rec + boost * a * kk0 * det2 / (1 + np.abs(det2) / lim)
        boost = 0
    gb = g.bil[..., 1]
    meanc = (A + B + C + D) * 0.25
    detail = rec - meanc
    kk = np.clip(energy / noise, 0, 1); kk = kk * kk * (3 - 2 * kk)
    boosted = detail * (1 + boost * a * kk / (1 + np.abs(detail) / lim))
    target = meanc + boosted
    mx = np.maximum(np.maximum(A, B), np.maximum(C, D)); mn = np.minimum(np.minimum(A, B), np.minimum(C, D))
    soft = 0.04
    target = np.clip(target, mn - soft, mx + soft)
    delta = np.clip(target - gb, -0.12, 0.12)
    return np.clip(g.bil + delta[..., None], 0, 1)

def up_mwsr(img, ow, oh, ft=0.005, kwc=0.1, **k):
    """MWSR (MoonlightWeb Super Resolution, MIT, Bruno Martin): numpy port of mwsr-up.frag with the README's per-scale law.
    The GPU's dFdx/dFdy are the difference inside each 2x2 pixel quad."""
    h, w = img.shape[:2]
    sx, sy = ow / w, oh / h
    sc = min(sx, sy); sl = min(max(sc, 1.07), 4.0); x = np.log(sl)
    force = 1 + 0.35 * np.clip(np.log(sl / 1.3333) / np.log(2 / 1.3333), 0, 1)
    kc = 0.63536 + 0.02185 * x + 0.12781 * x * x
    kg = (-0.12884 + 2.58447 * x - 1.26009 * x * x) * force
    kr = 1.00715 - 1.02091 * x + 0.56996 * x * x
    kl = (0.33417 - 0.29620 * x + 0.08628 * x * x) * force
    fade = min(max((sc - 1) / 0.07, 0), 1); kg *= fade; kwc *= fade
    ox, oy = np.meshgrid(np.arange(ow), np.arange(oh))
    sp_x = (ox + 0.5) / ow * w; sp_y = (oy + 0.5) / oh * h
    f0x = np.floor(sp_x - 0.5); f0y = np.floor(sp_y - 0.5)
    tx = sp_x - 0.5 - f0x; ty = sp_y - 0.5 - f0y
    kux, kuy = kc * tx * (1 - tx), kc * ty * (1 - ty)
    w12x, w12y = 1 + kux, 1 + kuy
    p12x = f0x + 0.5 + (tx * tx * (3 - 2 * tx) + kux * (1 - tx)) / w12x
    p12y = f0y + 0.5 + (ty * ty * (3 - 2 * ty) + kuy * (1 - ty)) / w12y
    S = lambda px, py: sample_bilinear(img, px, py)
    rM = S(p12x, p12y)
    LUMA = np.array([0.25, 0.625, 0.125], np.float32)
    lM = rM @ LUMA
    ev = lM.copy(); dx = np.zeros_like(lM); dy = np.zeros_like(lM)
    H, W = lM.shape
    lx = lM[:, :W - (W % 2)].reshape(H, -1, 2); dx[:, :W - (W % 2)] = np.repeat(lx[:, :, 1] - lx[:, :, 0], 2, axis=1)
    ly = lM[:H - (H % 2)].reshape(-1, 2, W); dy[:H - (H % 2)] = np.repeat(ly[:, 1, :] - ly[:, 0, :], 2, axis=0)
    gx, gy = dx * sx, dy * sy
    flat = np.abs(gx) + np.abs(gy) < ft
    rT = S(p12x, f0y - 0.5 + 0.5); rB = S(p12x, f0y + 2.5 + 0.5)
    rL = S(f0x - 0.5 + 0.5, p12y); rR = S(f0x + 2.5 + 0.5, p12y)
    ln = np.sqrt(gx * gx + gy * gy) + 1e-20
    ax, ay = gx / ln * kr, gy / ln * kr                              # across tap offset in input texels
    ra = S(sp_x - ax, sp_y - ay); rb = S(sp_x + ax, sp_y + ay)
    e = lambda a: a[..., None]
    K = (e(w12x) * (e(w12y) * rM - e(kuy) * (e(1 - ty) * rT + e(ty) * rB)) - e(w12y) * e(kux) * (e(1 - tx) * rL + e(tx) * rR)) / e(1 - kux * kuy)
    D = K - 0.5 * (ra + rb)
    R = K + kg * D + kwc * (D - (D @ LUMA)[..., None])
    mn = np.minimum(rM, np.minimum(ra, rb)); mx = np.maximum(rM, np.maximum(ra, rb)); mg = (mx - mn) * kl
    out = np.clip(R, np.maximum(mn - mg, 0), mx + mg)
    return np.clip(np.where(e(flat), rM, out), 0, 1)

def _cr(r, a=-0.5):
    """Cubic convolution weight (a = -0.5 is Catmull-Rom)."""
    r = np.abs(r); r2 = r * r
    return np.where(r < 1, (a + 2) * r2 * r - (a + 3) * r2 + 1, np.where(r < 2, a * r2 * r - 5 * a * r2 + 8 * a * r - 4 * a, 0.0))

def up_epi2(img, ow, oh, boost=2.0, lim=0.02, edge=0.004, soft=0.0, dlim=0.12, ca=-0.5, rlim=0.5, rsoft=0.15, ls=0.0, lclamp=0.035, loff=1.0, lmode=0, lcas=0.0, lfloor=0.0, steep=0.0, steepk=1.0, steepg=0.0, dk=1.0, aa=0.0, aat=0.5, aas=0.0, **k):
    """EpiSR 2: no edge direction. (1) gate: the range of three reads (two gathered texels and the bilinear read) says whether a pixel is on an
    edge; flat pixels keep the one bilinear read. (2) on an edge, a separable Catmull-Rom over the 12 gathered texels (per-axis weights, computed
    once, products per texel). (3) the cubic's difference from the bilinear read is boosted with a soft limit d/(1+|d|/lim), scaled by the gate,
    and kept within the four centre texels' range +- soft. The delta is added equally to R, G, B like SGSR does."""
    g = Grid(img, ow, oh); h_in, w_in = img.shape[:2]
    v = {n: g.G(*o) for n, o in TAPS.items()}
    gb = g.bil[..., 1]
    hi = np.maximum(np.maximum(v['ly'], v['lz']), gb); lo = np.minimum(np.minimum(v['ly'], v['lz']), gb)
    if dk < 1: edge = edge * (dk + (1 - dk) * np.clip((hi + lo) * 1.0, 0, 1))   # brightness-adaptive gate: dark areas need less contrast to count as edges
    a = np.clip((hi - lo - edge) / (2 * edge), 0, 1)
    px, py = g.plx, g.ply
    wx = {-1: _cr(px + 1, ca), 0: _cr(px, ca), 1: _cr(1 - px, ca), 2: _cr(2 - px, ca)}
    wy = {-2: _cr(py + 1, ca), -1: _cr(py, ca), 0: _cr(1 - py, ca), 1: _cr(2 - py, ca)}
    sw = 0; sy = 0
    for n, (ti, tj) in TAPS.items():
        w = wx[ti] * wy[tj]; sw = sw + w; sy = sy + w * v[n]
    rec = sy / sw
    det = rec - gb
    mx = np.maximum(np.maximum(v['ly'], v['lz']), np.maximum(v['rx'], v['rw'])); mn = np.minimum(np.minimum(v['ly'], v['lz']), np.minimum(v['rx'], v['rw']))
    L = lim + rlim * (mx - mn)
    if aa > 0:                                                   # alias detector: on an aliased edge the four centre texels sit at the two extremes (no in-between values); on an anti-aliased edge some do
        rg_ = np.maximum(mx - mn, 1e-4); cen = [(v[n] - mn) / rg_ for n in ('ly', 'lz', 'rx', 'rw')]
        mid = np.minimum.reduce([np.abs(2 * t - 1) for t in cen])                      # 1 = every centre texel is at an extreme
        bim = np.clip((mid - aat) / (1 - aat), 0, 1) * np.clip((mx - mn) / 0.08, 0, 1)
        boost_eff = boost * (1 - aa * bim); soften = aas * bim
    else: boost_eff = boost; soften = 0.0
    tgt = rec + boost_eff * a * det / (1 + np.abs(det) / L)
    if aas > 0 and aa > 0: tgt = tgt + soften * (0.5 * (rec + gb) - tgt) * 0   # (placeholder: softening handled below)
    soft = soft + rsoft * (mx - mn)
    if steep > 0:                                                # edge steepening: S-curve of the value inside the local range pushes mid-edge values to the ends
        rg = np.maximum(mx - mn, 1e-4); t_ = np.clip((tgt - mn) / rg, 0, 1)
        t2 = np.clip(0.5 + (t_ - 0.5) * steepg, 0, 1) if steepg > 0 else t_ * t_ * (3 - 2 * t_)
        wgt = steep * a * np.clip(rg / steepk, 0, 1)
        tgt = np.where(rg > 1e-4, mn + rg * (t_ + wgt * (t2 - t_)) + (tgt - np.clip(tgt, mn, mx)), tgt)
    tgt = np.clip(tgt, mn - soft, mx + soft)
    delta = np.clip(tgt - gb, -dlim, dlim)
    res = np.where((a > 0)[..., None], g.bil + delta[..., None], g.bil)
    if ls > 0:                                                   # LumaSharpen-style: bilinear minus the mean of 4 bilinear reads half an output pixel away on the diagonals
        sx, sy = w_in / ow, h_in / oh
        o = 0.5 * loff
        blur = 0.25 * sum(sample_bilinear(img, g.px + dx * o * sx, g.py + dy * o * sy) for dx, dy in ((1, -1), (-1, 1), (-1, -1), (1, 1)))
        sl = ((g.bil - blur) @ np.array([0.2126, 0.7152, 0.0722], np.float32)) * ls
        if lcas > 0 or lfloor > 0:                               # CAS-style limiter from the same taps: less near black/white and where contrast is already high; noise floor
            lum = lambda c: c @ np.array([0.2126, 0.7152, 0.0722], np.float32)
            taps = [sample_bilinear(img, g.px + dx * o * sx, g.py + dy * o * sy) for dx, dy in ((1, -1), (-1, 1), (-1, -1), (1, 1))]
            ls_ = np.stack([lum(t) for t in taps] + [lum(g.bil)])
            mn_, mx_ = ls_.min(0), ls_.max(0)
            amp = np.sqrt(np.clip(np.minimum(mn_, 1 - mx_) / np.maximum(mx_, 1e-4), 0, 1)) if lcas > 0 else 1.0
            if lfloor > 0: t_ = np.clip((mx_ - mn_) / lfloor, 0, 1); amp = amp * t_ * t_ * (3 - 2 * t_)
            sl = sl * amp * (1.0 if lcas <= 0 else 1.0 / max(lcas, 1e-3) * 1.0)
        sl = np.clip(sl, -lclamp, lclamp)
        if lmode == 1: sl = sl * (1 - a)
        res = res + sl[..., None]
    return np.clip(res, 0, 1)

def up_fsr1(img, ow, oh, rcas_stops=0.2, **k):
    """AMD FSR 1: EASU (12-tap edge-adaptive Lanczos-like) then RCAS (5-tap sharpen) at output size, ported from ffx_fsr1.h with exact reciprocals.
    rcas_stops = the app's setting (0.2 = RCAS 90 %), None skips RCAS."""
    h, w = img.shape[:2]
    ox, oy = np.meshgrid(np.arange(ow), np.arange(oh))
    ppx = (ox + 0.5) * w / ow - 0.5; ppy = (oy + 0.5) * h / oh - 0.5
    fx = np.floor(ppx).astype(np.int32); fy = np.floor(ppy).astype(np.int32)
    px = (ppx - fx).astype(np.float32); py = (ppy - fy).astype(np.float32)
    T = lambda di, dj: img[np.clip(fy + dj, 0, h - 1), np.clip(fx + di, 0, w - 1)]
    pos = {'b': (0, -1), 'c': (1, -1), 'e': (-1, 0), 'f': (0, 0), 'g': (1, 0), 'h': (2, 0), 'i': (-1, 1), 'j': (0, 1), 'k': (1, 1), 'l': (2, 1), 'n': (0, 2), 'o': (1, 2)}
    C = {n: T(*p) for n, p in pos.items()}
    L = {n: C[n][..., 2] * 0.5 + C[n][..., 0] * 0.5 + C[n][..., 1] for n in C}
    rcp = lambda x: 1.0 / np.maximum(x, 1e-12)
    dirx = np.zeros_like(px); diry = np.zeros_like(px); ln = np.zeros_like(px)
    def setacc(w_, lA, lB, lC, lD, lE):
        nonlocal dirx, diry, ln
        lenx = rcp(np.maximum(np.abs(lD - lC), np.abs(lC - lB))); dX = lD - lB; dirx = dirx + dX * w_
        lenx = np.clip(np.abs(dX) * lenx, 0, 1) ** 2; ln = ln + lenx * w_
        leny = rcp(np.maximum(np.abs(lE - lC), np.abs(lC - lA))); dY = lE - lA; diry = diry + dY * w_
        leny = np.clip(np.abs(dY) * leny, 0, 1) ** 2; ln = ln + leny * w_
    setacc((1 - px) * (1 - py), L['b'], L['e'], L['f'], L['g'], L['j'])
    setacc(px * (1 - py), L['c'], L['f'], L['g'], L['h'], L['k'])
    setacc((1 - px) * py, L['f'], L['i'], L['j'], L['k'], L['n'])
    setacc(px * py, L['g'], L['j'], L['k'], L['l'], L['o'])
    d2 = dirx * dirx + diry * diry
    zro = d2 < 1.0 / 32768.0
    dr = np.where(zro, 1.0, 1.0 / np.sqrt(np.maximum(d2, 1e-12)))
    dirx = np.where(zro, 1.0, dirx) * dr; diry = diry * dr
    ln = (ln * 0.5) ** 2
    stretch = (dirx * dirx + diry * diry) * rcp(np.maximum(np.abs(dirx), np.abs(diry)))
    len2x = 1 + (stretch - 1) * ln; len2y = 1 - 0.5 * ln
    lob = 0.5 + ((0.25 - 0.04) - 0.5) * ln
    clp = rcp(lob)
    ac = np.zeros_like(C['f']); aw = np.zeros_like(px)
    for n, (ti, tj) in pos.items():
        offx = ti - px; offy = tj - py
        vx = offx * dirx + offy * diry; vy = offx * (-diry) + offy * dirx
        vx = vx * len2x; vy = vy * len2y
        dd = np.minimum(vx * vx + vy * vy, clp)
        wb = (2.0 / 5.0 * dd - 1) ** 2; wa = (lob * dd - 1) ** 2
        wb = 25.0 / 16.0 * wb - (25.0 / 16.0 - 1)
        wt = wb * wa
        ac = ac + C[n] * wt[..., None]; aw = aw + wt
    mn = np.minimum(np.minimum(C['f'], C['g']), np.minimum(C['j'], C['k'])); mx = np.maximum(np.maximum(C['f'], C['g']), np.maximum(C['j'], C['k']))
    easu = np.minimum(mx, np.maximum(mn, ac / aw[..., None]))
    if rcas_stops is None: return np.clip(easu, 0, 1)
    P = np.pad(easu, ((1, 1), (1, 1), (0, 0)), mode='edge')
    b_, d_, e_, f_, h_ = P[:-2, 1:-1], P[1:-1, :-2], P[1:-1, 1:-1], P[1:-1, 2:], P[2:, 1:-1]
    mn4 = np.minimum(np.minimum(np.minimum(b_, d_), f_), h_); mx4 = np.maximum(np.maximum(np.maximum(b_, d_), f_), h_)
    hitmin = np.minimum(mn4, e_) / (4 * np.maximum(mx4, 1e-6)); hitmax = (1 - np.maximum(mx4, e_)) / (4 * mn4 - 4.0 + 1e-9)
    lobe_c = np.maximum(-hitmin, hitmax)
    lobe = np.maximum(-(0.25 - 1 / 16.0), np.minimum(lobe_c.max(axis=2), 0.0)) * (2.0 ** -rcas_stops)
    out = (lobe[..., None] * (b_ + d_ + h_ + f_) + e_) / (4 * lobe[..., None] + 1)
    return np.clip(out, 0, 1)

def _edge_field(img, ow, oh, sig=1.0):
    """Edge orientation field from the low-res image (structure tensor, Gaussian-smoothed), bilinearly upsampled to output size.
    Returns tangent unit vector (tx, ty) in input-pixel units and coherence 0..1."""
    y = img @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    gx = cv2.Sobel(y, cv2.CV_32F, 1, 0, ksize=3) / 8; gy = cv2.Sobel(y, cv2.CV_32F, 0, 1, ksize=3) / 8
    jxx = cv2.GaussianBlur(gx * gx, (0, 0), sig); jyy = cv2.GaussianBlur(gy * gy, (0, 0), sig); jxy = cv2.GaussianBlur(gx * gy, (0, 0), sig)
    up = lambda a: cv2.resize(a, (ow, oh), interpolation=cv2.INTER_LINEAR)
    jxx, jyy, jxy = up(jxx), up(jyy), up(jxy)
    tr = jxx + jyy; dis = np.sqrt((jxx - jyy) ** 2 + 4 * jxy * jxy)
    coh = dis / (tr + 1e-6); energy = np.sqrt(tr)
    th = 0.5 * np.arctan2(2 * jxy, jxx - jyy)            # normal direction
    return -np.sin(th), np.cos(th), coh, energy           # tangent = normal rotated by 90 degrees

def up_epi3(img, ow, oh, smooth=1.0, reach=0.8, cthr=0.5, ethr=0.01, boost=2.0, lim=0.02, edge=0.004, rlim=0.5, rsoft=0.15, **k):
    """Experiment: EpiSR 2 with the cubic reconstruction smoothed ALONG the detected edge direction (potential, using full bicubic remaps)."""
    h, w = img.shape[:2]
    g = Grid(img, ow, oh); v = {n: g.G(*o) for n, o in TAPS.items()}; gb = g.bil[..., 1]
    tx, ty, coh, en = _edge_field(img, ow, oh)
    a_dir = np.clip((coh - cthr) / (1 - cthr), 0, 1) * np.clip(en / ethr, 0, 1) * smooth
    def cub(dx, dy):
        mx_ = (g.px + dx - 0.5).astype(np.float32); my_ = (g.py + dy - 0.5).astype(np.float32)
        return cv2.remap(img, mx_, my_, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    c0 = cub(0, 0); cp = cub(tx * reach, ty * reach); cm = cub(-tx * reach, -ty * reach)
    rec3 = (1 - a_dir[..., None]) * c0 + a_dir[..., None] * (0.5 * c0 + 0.25 * (cp + cm))
    rec = rec3[..., 1]
    hi = np.maximum(np.maximum(v['ly'], v['lz']), gb); lo = np.minimum(np.minimum(v['ly'], v['lz']), gb)
    a = np.clip((hi - lo - edge) / (2 * edge), 0, 1)
    det = rec - gb
    mx = np.maximum(np.maximum(v['ly'], v['lz']), np.maximum(v['rx'], v['rw'])); mn = np.minimum(np.minimum(v['ly'], v['lz']), np.minimum(v['rx'], v['rw']))
    L = lim + rlim * (mx - mn)
    tgt = rec + boost * a * det / (1 + np.abs(det) / L)
    sf = rsoft * (mx - mn)
    tgt = np.clip(tgt, mn - sf, mx + sf)
    delta = np.clip(tgt - gb, -0.12, 0.12)
    return np.clip(np.where((a > 0)[..., None], g.bil + delta[..., None], g.bil), 0, 1)

def fxaa(img, subpix=0.75, thr_min=0.0312, thr_max=0.125):
    """Numpy port of the app's fxaa.frag (FXAA 3.11 as in Rendu, MIT): runs on the game-size image before the upscale."""
    h, w = img.shape[:2]
    lum = lambda c: c @ np.array([0.299, 0.587, 0.114], np.float32)
    L = lum(img); P = np.pad(L, 1, mode='edge')
    sh = lambda dx, dy: P[1 + dy:1 + dy + h, 1 + dx:1 + dx + w]
    c, dn, up, lf, rt = sh(0, 0), sh(0, -1), sh(0, 1), sh(-1, 0), sh(1, 0)
    mn = np.minimum(c, np.minimum(np.minimum(dn, up), np.minimum(lf, rt))); mx = np.maximum(c, np.maximum(np.maximum(dn, up), np.maximum(lf, rt)))
    rng = mx - mn
    edge = rng >= np.maximum(thr_min, mx * thr_max)
    dl, ur, ul, dr = sh(-1, -1), sh(1, 1), sh(-1, 1), sh(1, -1)
    dnup = dn + up; lfrt = lf + rt; lc = dl + ul; dc = dl + dr; rc = dr + ur; uc = ur + ul
    eh = np.abs(-2 * lf + lc) + np.abs(-2 * c + dnup) * 2 + np.abs(-2 * rt + rc)
    ev = np.abs(-2 * up + uc) + np.abs(-2 * c + lfrt) * 2 + np.abs(-2 * dn + dc)
    horiz = eh >= ev
    luma1 = np.where(horiz, dn, lf); luma2 = np.where(horiz, up, rt)
    g1 = luma1 - c; g2 = luma2 - c
    steep1 = np.abs(g1) >= np.abs(g2)
    gscaled = 0.25 * np.maximum(np.abs(g1), np.abs(g2))
    step = np.where(steep1, -1.0, 1.0)
    lavg = np.where(steep1, 0.5 * (luma1 + c), 0.5 * (luma2 + c))
    ox, oy = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    cx = np.where(horiz, ox, ox + step * 0.5); cy = np.where(horiz, oy + step * 0.5, oy)
    dxo = np.where(horiz, 1.0, 0.0); dyo = np.where(horiz, 0.0, 1.0)
    q = lambda i: 1.0 if i < 5 else (1.5 if i == 5 else (2.0 if i < 10 else (4.0 if i < 11 else 8.0)))
    Ls = lambda x, y: lum(sample_bilinear(img, x, y))
    p1x, p1y = cx - dxo * q(0), cy - dyo * q(0); p2x, p2y = cx + dxo * q(0), cy + dyo * q(0)
    e1 = Ls(p1x, p1y) - lavg; e2 = Ls(p2x, p2y) - lavg
    r1 = np.abs(e1) >= gscaled; r2 = np.abs(e2) >= gscaled
    p1x = np.where(r1, p1x, p1x - dxo * q(1)); p1y = np.where(r1, p1y, p1y - dyo * q(1))
    p2x = np.where(r2, p2x, p2x + dxo * q(1)); p2y = np.where(r2, p2y, p2y + dyo * q(1))
    for i in range(2, 12):
        if (r1 & r2).all(): break
        n1 = Ls(p1x, p1y) - lavg; n2 = Ls(p2x, p2y) - lavg
        e1 = np.where(r1, e1, n1); e2 = np.where(r2, e2, n2)
        r1 = np.abs(e1) >= gscaled; r2 = np.abs(e2) >= gscaled
        p1x = np.where(r1, p1x, p1x - dxo * q(i)); p1y = np.where(r1, p1y, p1y - dyo * q(i))
        p2x = np.where(r2, p2x, p2x + dxo * q(i)); p2y = np.where(r2, p2y, p2y + dyo * q(i))
    d1 = np.where(horiz, ox - p1x, oy - p1y); d2 = np.where(horiz, p2x - ox, p2y - oy)
    dir1 = d1 < d2; dfin = np.minimum(d1, d2); thick = d1 + d2
    csmall = c < lavg
    cv1 = (e1 < 0) != csmall; cv2 = (e2 < 0) != csmall
    cv = np.where(dir1, cv1, cv2)
    po = -dfin / np.maximum(thick, 1e-6) + 0.5
    fo = np.where(cv, po, 0.0)
    lavg9 = (1.0 / 12.0) * (2 * (dnup + lfrt) + lc + rc)
    sp1 = np.clip(np.abs(lavg9 - c) / np.maximum(rng, 1e-6), 0, 1)
    sp2 = (-2 * sp1 + 3) * sp1 * sp1
    fo = np.maximum(fo, sp2 * sp2 * subpix)
    fx = np.where(horiz, ox, ox + fo * step); fy = np.where(horiz, oy + fo * step, oy)
    out = sample_bilinear(img, fx, fy)
    return np.where(edge[..., None], out, img)

def with_fxaa(up, **kw):
    return lambda img, ow, oh, **k: up(fxaa(img, **kw), ow, oh, **k)

def fxaa_gated(img, lo=0.25, hi=0.75, **kw):
    """FXAA applied only where the 3x3 neighbourhood is bimodal (every luma near the local min or max = a hard, aliased step); edges that already
    have in-between values (anti-aliased input) are left alone."""
    out = fxaa(img, **kw)
    h, w = img.shape[:2]
    L = img @ np.array([0.299, 0.587, 0.114], np.float32); P = np.pad(L, 1, mode='edge')
    nb = np.stack([P[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy in (-1, 0, 1) for dx in (-1, 0, 1)])
    mn, mx = nb.min(0), nb.max(0); rg = np.maximum(mx - mn, 1e-4)
    t = (nb - mn) / rg
    inbetween = ((t > lo) & (t < hi)).sum(0)                   # how many of the 9 are mid-values
    m = (inbetween <= 1) & (rg > 0.06)
    return np.where(m[..., None], out, img)

def with_gfxaa(up, **kw):
    return lambda img, ow, oh, **k: up(fxaa_gated(img, **kw), ow, oh, **k)

UPSCALERS = {
    'bilinear': up_bilinear, 'bicubic': up_bicubic, 'lanczos': up_lanczos,
    'sgsr1': up_sgsr1, 'sgsrq': up_sgsrq, 'episr1': up_episr1, 'episr2': up_episr2, 'mwsr': up_mwsr, 'epi2': up_epi2, 'fxaa_epi2': with_fxaa(up_epi2), 'gfxaa_epi2': with_gfxaa(up_epi2), 'lfxaa_epi2': with_fxaa(up_epi2, subpix=0.3, thr_min=0.06, thr_max=0.22), 'fxaa_fsr1': with_fxaa(up_fsr1), 'fxaa_bilinear': with_fxaa(up_bilinear), 'epi3': up_epi3, 'fsr1': up_fsr1, 'fsr1_easu': lambda i, w, h, **k: up_fsr1(i, w, h, rcas_stops=None),
}

# ---------- metrics ----------
def psnr(a, b):
    mse = np.mean((a - b) ** 2); return 99.0 if mse < 1e-12 else 10 * np.log10(1 / mse)
def ssim(a, b): return structural_similarity(a, b, channel_axis=2, data_range=1.0)
def grad_mag(a):
    y = a @ np.array([0.299, 0.587, 0.114], np.float32)
    gx = cv2.Sobel(y, cv2.CV_32F, 1, 0); gy = cv2.Sobel(y, cv2.CV_32F, 0, 1); return np.sqrt(gx * gx + gy * gy)
def metrics(out, gt):
    y = lambda a: a @ np.array([0.299, 0.587, 0.114], np.float32)
    go, gg = grad_mag(out), grad_mag(gt)
    sharp = float(go.sum() / (gg.sum() + 1e-9))                       # 1.0 = same edge strength as the original
    # ringing/halo: output pixels brighter/darker than the original's local 5x5 range
    k = np.ones((5, 5), np.uint8); yg, yo = y(gt), y(out)
    hi, lo = cv2.dilate(yg, k), cv2.erode(yg, k)
    ring = float(np.mean(np.maximum(yo - hi, 0) + np.maximum(lo - yo, 0)) * 255)
    return dict(psnr=psnr(out, gt), ssim=ssim(out, gt), sharp=sharp, ring=ring)

# ---------- synthetic images ----------
def synthetic(h=720, w=1280, seed=1):
    rng = np.random.default_rng(seed)
    img = np.zeros((h, w, 3), np.uint8) + np.array([89, 128, 166], np.uint8)
    img[h // 2:] = np.array([64, 89, 51], np.uint8)
    col = lambda: tuple(int(c) for c in rng.integers(0, 256, 3))
    for n in range(40):                                              # lines at many angles, hard edges
        cx, cy, ang = rng.uniform(0, w), rng.uniform(0, h), rng.uniform(0, np.pi); L = rng.uniform(60, 260)
        cv2.line(img, (int(cx), int(cy)), (int(cx + L * np.cos(ang)), int(cy + L * np.sin(ang))), col(), int(rng.integers(1, 5)), cv2.LINE_AA)
    for n in range(25):                                              # circles
        cv2.circle(img, (int(rng.uniform(0, w)), int(rng.uniform(0, h))), int(rng.uniform(8, 60)), col(), -1 if n % 2 else 2, cv2.LINE_AA)
    for n in range(18):                                              # text
        cv2.putText(img, "Epinephro 0123 fps", (int(rng.uniform(0, w - 250)), int(rng.uniform(30, h - 10))), cv2.FONT_HERSHEY_SIMPLEX, float(rng.uniform(0.4, 1.0)), (255, 255, 255) if n % 2 else (0, 0, 0), 1, cv2.LINE_AA)
    f = img.astype(np.float32) / 255.0
    tex = cv2.GaussianBlur(rng.normal(0, 0.04, (h // 2, w)).astype(np.float32), (0, 0), 1.2) * 3   # foliage-like noise, lower half
    f[h // 2:] += tex[..., None]
    return np.clip(f, 0, 1)

# ---------- report ----------
def pick_crops(gt, n=3, size=160):
    g = grad_mag(gt); h, w = g.shape
    ii = cv2.boxFilter(g, -1, (size, size))
    crops = []; ii = ii.copy(); m = size // 2
    ii[:m] = 0; ii[-m:] = 0; ii[:, :m] = 0; ii[:, -m:] = 0
    for _ in range(n):
        y, x = np.unravel_index(np.argmax(ii), ii.shape)
        crops.append((int(x - m), int(y - m)))
        ii[max(0, y - size):y + size, max(0, x - size):x + size] = 0
    return crops

def label(img_u8, text):
    img = img_u8.copy(); cv2.rectangle(img, (0, 0), (img.shape[1], 18), (0, 0, 0), -1)
    cv2.putText(img, text, (4, 13), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1, cv2.LINE_AA); return img

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='*'); ap.add_argument('--synthetic', action='store_true')
    ap.add_argument('--scale', type=float, default=0.667); ap.add_argument('--down', default='area', choices=['area', 'point', 'linear'])
    ap.add_argument('--methods', default='bilinear,bicubic,sgsr1,sgsrq,episr1,episr2,mwsr')
    ap.add_argument('--param', action='append', default=[], help='method:name=value, e.g. episr1:soft=0.4 (repeat)')
    ap.add_argument('--out', default='out'); ap.add_argument('--zoom', type=int, default=3)
    a = ap.parse_args(); os.makedirs(a.out, exist_ok=True)
    imgs = [(os.path.basename(f), to_f(cv2.cvtColor(cv2.imread(f), cv2.COLOR_BGR2RGB))) for f in sum([glob.glob(f) for f in a.files], [])]
    if a.synthetic or not imgs: imgs += [('synthetic', synthetic())]
    methods = a.methods.split(',')
    params = {}
    for p in a.param:
        m, kv = p.split(':'); k, v = kv.split('='); params.setdefault(m, {})[k] = float(v)
    totals = {m: [] for m in methods}
    for name, gt in imgs:
        h, w = gt.shape[:2]; small = render_small(gt, a.scale, a.down)
        outs = {m: np.ascontiguousarray(UPSCALERS[m](small, w, h, **params.get(m, {})), dtype=np.float32) for m in methods}
        rows = []
        for m in methods:
            r = metrics(outs[m], gt); totals[m].append(r)
        crops = pick_crops(gt)
        for ci, (x, y) in enumerate(crops):
            cells = [label(cv2.resize(to_u8(gt[y:y + 160, x:x + 160]), None, fx=a.zoom, fy=a.zoom, interpolation=cv2.INTER_NEAREST), 'original')]
            for m in methods:
                cells.append(label(cv2.resize(to_u8(outs[m][y:y + 160, x:x + 160]), None, fx=a.zoom, fy=a.zoom, interpolation=cv2.INTER_NEAREST), m))
            rows.append(np.hstack(cells[:4]));
            if len(cells) > 4: rows.append(np.hstack(cells[4:8] + [np.zeros_like(cells[0])] * (4 - len(cells[4:8]))))
        base = os.path.splitext(name)[0]
        cv2.imwrite(f'{a.out}/{base}_crops.png', cv2.cvtColor(np.vstack(rows), cv2.COLOR_RGB2BGR))
        print(f'== {name}  {h and w}x{h} from {small.shape[1]}x{small.shape[0]} ({a.scale:.3f}, {a.down})')
        for m in methods:
            r = totals[m][-1]; print(f'  {m:9s} PSNR {r["psnr"]:.2f}  SSIM {r["ssim"]:.4f}  edge strength {r["sharp"]:.3f}  ringing {r["ring"]:.3f}')
    if len(imgs) > 1:
        print('== average')
        for m in methods:
            print(f'  {m:9s} ' + '  '.join(f'{k} {np.mean([r[k] for r in totals[m]]):.3f}' for k in ('psnr', 'ssim', 'sharp', 'ring')))

if __name__ == '__main__': main()
