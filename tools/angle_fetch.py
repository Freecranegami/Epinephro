#!/usr/bin/env python3
"""Downloads the prebuilt ANGLE (OpenGL ES on Vulkan) for Android arm64 and prepares it for this app.

Source: github.com/abnormalmaps/build-angle, release TAG below (ANGLE itself is BSD-3-Clause, Google).
Preparation, all by same-length (or shorter, NUL padded) byte replacements, because the app renames libraries in games the same way:
  libEGL_angle.so      -> libANG.so        (a game's "libEGL.so" is renamed to this when ANGLE is switched on for it)
  libGLESv2_angle.so   -> libANGLE2.so     ("libGLESv2.so" / "libGLESv3.so" in the game)
  libGLESv1_CM_angle.so-> libANGLEv1CM.so  ("libGLESv1_CM.so")
  libvulkan.so(.1)     -> libvkshim.so     (ANGLE's Vulkan then runs through the app's layer: custom driver, upscalers, effects)
The result goes to app/src/main/jniLibs/arm64-v8a/ and is packed into the APK by Gradle.
"""
import os, subprocess, sys, zipfile, glob, shutil, re

TAG = "angle-bb10246"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a")
WORK = os.path.join(ROOT, "build/angle-work")

def pad(new, oldlen):
    assert len(new) <= oldlen, (new, oldlen)
    return new + b"\0" * (oldlen - len(new))

# order matters: the longer names first
REPL = [
    (b"libGLESv1_CM_angle.so", pad(b"libANGLEv1CM.so", 21)),
    (b"libGLESv2_angle.so", pad(b"libANGLE2.so", 18)),
    (b"libGLESv2_angle", pad(b"libANGLE2", 15)),
    (b"libEGL_angle.so", pad(b"libANG.so", 15)),
    (b"libEGL_angle", pad(b"libANG", 12)),
    (b"libvulkan.so.1", pad(b"libvkshim.so", 14)),
    (b"libvulkan.so", b"libvkshim.so"),
]
FILES = {"libEGL_angle.so": "libANG.so", "libGLESv2_angle.so": "libANGLE2.so", "libGLESv1_CM_angle.so": "libANGLEv1CM.so"}

def main():
    shutil.rmtree(WORK, ignore_errors=True); os.makedirs(WORK)
    subprocess.check_call(["gh", "release", "download", TAG, "-R", "abnormalmaps/build-angle", "-p", "angle-android-arm64.zip", "-D", WORK])
    with zipfile.ZipFile(os.path.join(WORK, "angle-android-arm64.zip")) as z:
        z.extractall(WORK)
    os.makedirs(OUT, exist_ok=True)
    for src, dst in FILES.items():
        p = glob.glob(os.path.join(WORK, "**", src), recursive=True)
        if not p:
            print("missing", src); sys.exit(1)
        data = open(p[0], "rb").read()
        counts = []
        for old, new in REPL:
            counts.append("%s x%d" % (old.decode(), data.count(old)))
            data = data.replace(old, new)
        open(os.path.join(OUT, dst), "wb").write(data)
        print("%s -> %s (%d bytes): %s" % (src, dst, len(data), ", ".join(counts)))
    # licence next to the libraries' notices
    for lic in glob.glob(os.path.join(WORK, "**", "LICENSE*"), recursive=True):
        shutil.copy(lic, os.path.join(ROOT, "app/src/main/assets", "ANGLE-" + os.path.basename(lic)))
    print("ANGLE ready:", sorted(os.listdir(OUT)))

if __name__ == "__main__":
    main()
