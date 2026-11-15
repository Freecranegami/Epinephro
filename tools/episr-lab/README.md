EpiSR lab: numpy ports of the upscalers (bilinear, bicubic, SGSR 1/Quality, MWSR, EpiSR v1/2) and a harness that shrinks a full-size screenshot,
upscales it with each and scores it (PSNR, SSIM, edge strength, ringing) with crop sheets. `python3 lab.py shots/*.png --scale 0.667 --out out`.
Needs numpy, opencv-python, scikit-image. `up_epi2` is the reference for shaders/episr2.frag.
