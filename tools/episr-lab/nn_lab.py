# Tiny residual CNN on top of EpiSR 2 (pure numpy, hand-written backprop). Usage: python3 nn_lab.py C iters [down]
import sys, os, time, numpy as np, cv2, lab
C = int(sys.argv[1]); ITERS = int(sys.argv[2]); DOWN = sys.argv[3] if len(sys.argv) > 3 else 'area'
SC = 1 / 1.5
EP = dict(ls=1.5, lclamp=0.02, loff=1.5, lfloor=0.02)
names = ['gtav_desert', 'gtav_night', 'gtav_villa', 'rdr_canyon', 'rdr_sea', 'rdr_sunset']
test = ['gtav_night', 'rdr_sea']
if len(sys.argv) > 4 and sys.argv[4] == 'xgame': test = ['rdr_canyon', 'rdr_sea', 'rdr_sunset']   # trained on GTA V only
TAG = sys.argv[4] if len(sys.argv) > 4 else ''
os.makedirs('nn_cache', exist_ok=True)
def get(n):
    f = 'nn_cache/%s_%s.npz' % (n, DOWN)
    if os.path.exists(f):
        z = np.load(f); return z['b'], z['g']
    g = lab.to_f(cv2.cvtColor(cv2.imread('shots/%s.png' % n), cv2.COLOR_BGR2RGB)); h, w = g.shape[:2]
    s = lab.render_small(g, SC, DOWN); b = np.asarray(lab.up_epi2(s, w, h, **EP), np.float32)
    np.savez(f, b=b, g=g.astype(np.float32)); return b, g.astype(np.float32)
data = {n: get(n) for n in names}
print('bases ready', flush=True)

def cols(x):  # x (N,H,W,Ci) -> (N*H*W, Ci*9), zero padding
    N, H, W, Ci = x.shape; p = np.pad(x, ((0, 0), (1, 1), (1, 1), (0, 0)))
    return np.concatenate([p[:, i:i + H, j:j + W, :] for i in range(3) for j in range(3)], axis=3).reshape(N * H * W, Ci * 9)
def uncols(dc, shape):
    N, H, W, Ci = shape; dc = dc.reshape(N, H, W, 9, Ci); p = np.zeros((N, H + 2, W + 2, Ci), np.float32); k = 0
    for i in range(3):
        for j in range(3): p[:, i:i + H, j:j + W, :] += dc[:, :, :, k, :]; k += 1
    return p[:, 1:-1, 1:-1, :]
rng = np.random.default_rng(0)
dims = [3, C, C, 3]
Wt = [(rng.standard_normal((dims[i] * 9, dims[i + 1])) * np.sqrt(2 / (dims[i] * 9))).astype(np.float32) for i in range(3)]
Bt = [np.zeros(dims[i + 1], np.float32) for i in range(3)]
Wt[2] *= 0.1
def fwd(x):
    shp = x.shape; c0 = cols(x - 0.5); z1 = c0 @ Wt[0] + Bt[0]; a1 = np.maximum(z1, 0).reshape(shp[0], shp[1], shp[2], C)
    c1 = cols(a1); z2 = c1 @ Wt[1] + Bt[1]; a2 = np.maximum(z2, 0).reshape(shp[0], shp[1], shp[2], C)
    c2 = cols(a2); out = (c2 @ Wt[2] + Bt[2]).reshape(shp)
    return out, (shp, c0, z1, a1, c1, z2, a2, c2)
def bwd(d, cache):
    shp, c0, z1, a1, c1, z2, a2, c2 = cache; d = d.reshape(-1, 3)
    gW2 = c2.T @ d; gB2 = d.sum(0); dc2 = d @ Wt[2].T; da2 = uncols(dc2, (shp[0], shp[1], shp[2], C)).reshape(-1, C) * (z2 > 0)
    gW1 = c1.T @ da2; gB1 = da2.sum(0); dc1 = da2 @ Wt[1].T; da1 = uncols(dc1, (shp[0], shp[1], shp[2], C)).reshape(-1, C) * (z1 > 0)
    gW0 = c0.T @ da1; gB0 = da1.sum(0)
    return [gW0, gW1, gW2], [gB0, gB1, gB2]
P = 40; BS = 16; M = 4
train = [n for n in names if n not in test]
mW = [np.zeros_like(w) for w in Wt]; vW = [np.zeros_like(w) for w in Wt]; mB = [np.zeros_like(b) for b in Bt]; vB = [np.zeros_like(b) for b in Bt]
t0 = time.time()
for it in range(1, ITERS + 1):
    xs, ys = [], []
    for _ in range(BS):
        b, g = data[train[rng.integers(len(train))]]; H, W = b.shape[:2]
        y0, x0 = rng.integers(0, H - P), rng.integers(0, W - P); xs.append(b[y0:y0 + P, x0:x0 + P]); ys.append(g[y0:y0 + P, x0:x0 + P])
    x = np.stack(xs); y = np.stack(ys)
    out, cache = fwd(x); pred = x + out; err = pred - y
    mask = np.zeros_like(err); mask[:, M:-M, M:-M, :] = 1
    loss = float((err ** 2 * mask).sum() / mask.sum()); d = 2 * err * mask / mask.sum()
    gW, gB = bwd(d, cache); lr = 2e-3 * (0.5 * (1 + np.cos(np.pi * it / ITERS)) * 0.95 + 0.05)
    for k in range(3):
        for p_, g_, m_, v_ in ((Wt[k], gW[k], mW[k], vW[k]), (Bt[k], gB[k], mB[k], vB[k])):
            m_ *= 0.9; m_ += 0.1 * g_; v_ *= 0.999; v_ += 0.001 * g_ * g_
            p_ -= lr * (m_ / (1 - 0.9 ** it)) / (np.sqrt(v_ / (1 - 0.999 ** it)) + 1e-8)
    if it % 250 == 0 or it == 1: print('it %d loss %.6f (%.1f dB patch) %.0fs' % (it, loss, -10 * np.log10(loss), time.time() - t0), flush=True)

def run(b):
    outs = []
    for y0 in range(0, b.shape[0], 120):   # strips with a 4 px overlap, to bound memory
        a, e = max(0, y0 - M), min(b.shape[0], y0 + 120 + M); o, _ = fwd(b[None, a:e]); outs.append((b[a:e] + o[0])[y0 - a:y0 - a + min(120, b.shape[0] - y0)])
    return np.clip(np.concatenate(outs), 0, 1)
macs = sum(dims[i] * 9 * dims[i + 1] for i in range(3))
print('C=%d  multiply-adds per output pixel: %d  weights: %d' % (C, macs, sum(w.size for w in Wt)))
print('%-12s %-7s %8s %8s %8s %8s' % ('image', 'set', 'psnr EP2', 'psnr NN', 'ssim EP2', 'ssim NN'))
for n in names:
    b, g = data[n]; o = run(b); m0 = lab.metrics(b, g); m1 = lab.metrics(o, g)
    print('%-12s %-7s %8.2f %8.2f %8.4f %8.4f' % (n, 'TEST' if n in test else 'train', m0['psnr'], m1['psnr'], m0['ssim'], m1['ssim']), flush=True)
np.savez('nn_cache/weights_C%d_%s%s_%d.npz' % (C, DOWN, TAG, ITERS), *Wt, *Bt)
