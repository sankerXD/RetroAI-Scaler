#!/usr/bin/env python3
"""
Compiles common/depth_profile.cpp for the host and compares it, pixel by
pixel, against the numpy the model repository shades with.

    python3 tools/check_depth_profile.py

WHY THIS EXISTS

Section 13.5 records a fix that was validated in numpy and had no effect
whatsoever on the device: a wide average taken from mip level 6, checked
against an edge-clamped box mean written in numpy and shipped on the strength
of it. The two were never the same operation. The lesson written down at the
time was "validation has to hit the implementation, not a model of the
implementation", and this is that lesson wired up.

It matters twice over for boxDepthField(), because that function's entire
purpose is to BE the numpy filter - hd2d.py has always modelled the shading's
depth read as _box(depth, 4), and the reason the wobble was invisible offline
for so long is that the shader was doing something else. Replacing the mip with
this only helps if "this" really is a sliding box, so that is checked here
rather than assumed.

Needs a C++ compiler and numpy. Skips cleanly if the model repository is not
alongside, so it can sit in the build without pinning the two together.
"""
from __future__ import annotations

import ctypes
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "app", "src", "main", "cpp", "common", "depth_profile.cpp")

# The reference lives with the training code, which is where the shading maths
# is authored. Kept as a path rather than a copy: two copies of a reference is
# just two things to disagree.
MODEL_REPO = os.path.join(os.path.dirname(ROOT), "RetroAI Model")

SHIM = r"""
#include <cstdint>
#include <vector>
#include "depth_profile.h"
extern "C" void box_field(const uint8_t* src, int w, int h, int r, float* dst) {
    std::vector<float> out;
    retroai::boxDepthField(src, w, h, r, out);
    for (size_t i = 0; i < out.size(); ++i) dst[i] = out[i];
}
extern "C" void row_profile(const uint8_t* src, int w, int h, int r, float* dst) {
    std::vector<float> out;
    retroai::rowDepthProfile(src, w, h, r, out);
    for (size_t i = 0; i < out.size(); ++i) dst[i] = out[i];
}
"""

W, H, RADIUS = 240, 160, 4


def main() -> int:
    try:
        import numpy as np
    except ImportError:
        print("skip: numpy not available")
        return 0
    if not os.path.isdir(MODEL_REPO):
        print(f"skip: no model repository at {MODEL_REPO}")
        return 0
    sys.path.insert(0, MODEL_REPO)
    try:
        from retroai.hd2d import _box
    except ImportError as e:
        print(f"skip: cannot import the reference ({e})")
        return 0

    with tempfile.TemporaryDirectory() as tmp:
        shim = os.path.join(tmp, "shim.cpp")
        with open(shim, "w") as f:
            f.write(SHIM)
        lib = os.path.join(tmp, "libdepth.so")
        cmd = ["c++", "-std=c++17", "-O2", "-shared", "-fPIC",
               f"-I{os.path.dirname(SRC)}", SRC, shim, "-o", lib]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print("FAIL could not compile depth_profile.cpp for the host")
            print(r.stderr.strip())
            return 1

        dll = ctypes.CDLL(lib)
        dll.box_field.argtypes = [ctypes.POINTER(ctypes.c_uint8), ctypes.c_int,
                                  ctypes.c_int, ctypes.c_int,
                                  ctypes.POINTER(ctypes.c_float)]

        rng = np.random.default_rng(0)
        worst = 0.0
        # Noise finds disagreement anywhere; the ramp and the step are the two
        # shapes the edge handling can get wrong without noise noticing, since
        # a clamped and a zero-padded border differ most where the border is
        # not near the mean.
        cases = {
            "uniform noise": rng.integers(0, 256, (H, W), dtype=np.uint8),
            "vertical ramp": np.tile(np.linspace(0, 255, H, dtype=np.uint8)[:, None], (1, W)),
            "bright edges": np.pad(np.zeros((H - 20, W - 20), np.uint8), 10,
                                   constant_values=255),
            "flat mid": np.full((H, W), 128, np.uint8),
        }
        for name, src in cases.items():
            out = np.zeros(H * W, np.float32)
            dll.box_field(src.ctypes.data_as(ctypes.POINTER(ctypes.c_uint8)),
                          W, H, RADIUS,
                          out.ctypes.data_as(ctypes.POINTER(ctypes.c_float)))
            want = _box(src.astype("float32") / 255.0, RADIUS)
            err = float(np.abs(out.reshape(H, W) - want).max())
            worst = max(worst, err)
            status = "ok  " if err < 1e-5 else "FAIL"
            print(f"{status}  {name:<16} max |C++ - numpy| = {err:.2e}")

    if worst >= 1e-5:
        print(f"\nFAILED: worst disagreement {worst:.2e}, tolerance 1e-5")
        return 1
    print(f"\nboxDepthField matches hd2d._box to {worst:.1e}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
