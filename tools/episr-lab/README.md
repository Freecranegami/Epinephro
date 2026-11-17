EpiSR lab: numpy ports of the upscalers (bilinear, bicubic, SGSR 1/Quality, MWSR, EpiSR v1/2) and a harness that shrinks a full-size screenshot,
upscales it with each and scores it (PSNR, SSIM, edge strength, ringing) with crop sheets. `python3 lab.py shots/*.png --scale 0.667 --out out`.
Needs numpy, opencv-python, scikit-image. `up_epi2` is the reference for shaders/episr2.frag.

## nn_lab.py: a tiny residual CNN on top of EpiSR 2

`python3 nn_lab.py C iters [area|point] [xgame]` trains a 3-layer 3x3 CNN (C channels, hand-written backprop in numpy, no torch) that adds a
residual to EpiSR 2's output at the output size, x1.5 upscale. Held-out images are reported next to EpiSR 2. `xgame` trains on GTA V only and
tests on RDR. 360 (C=4) / 1008 (C=8) multiply-adds per output pixel.
