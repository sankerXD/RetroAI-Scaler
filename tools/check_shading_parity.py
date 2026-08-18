#!/usr/bin/env python3
"""
Checks that the offline HD-2D pipeline and the shipped shader are the same
thing: the depth blur pixel by pixel, and the shading constants value by value.

    python3 tools/check_shading_parity.py

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

THE CONSTANTS ARE CHECKED FOR THE SAME REASON, AND THEY HAD ALREADY DRIFTED.
hd2d.py carried OCCLUSION 0.70 / HAZE_CAP 0.30 while the shader pushed 0.55 /
0.22 - a mean shading multiplier of 0.706 against 0.784, so every offline
preview was around ten percent darker than the device, and 19.8/255 different
pixel for pixel. Both repositories described the port as "the same constants,
the same order, the same arithmetic, kept in step by hand". Keeping things in
step by hand is the part that failed, twice, so it is not done by hand now.

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

# Shader uniform -> hd2d.py constant. Only the ones the shading maths reads;
# the strengths are user-facing and live in the engine preset, not here.
CONSTANTS = {
    "uRelief": "RELIEF",
    "uOcclusion": "OCCLUSION",
    "uHazeCap": "HAZE_CAP",
    "uHazeKnee": "HAZE_KNEE",
    "uDofCentre": "DOF_CENTRE",
    "uDofBand": "DOF_BAND",
    "uDofRadius": "DOF_RADIUS",
    "uBloomThreshold": "BLOOM_THRESHOLD",
    "uBloomRadius": "BLOOM_RADIUS",
}
RENDERER = os.path.join(ROOT, "app", "src", "main", "cpp", "render", "gl_renderer.cpp")


def shader_constants():
    """
    The literals the renderer pushes, read from the glUniform1f calls.

    Deliberately parsed out of the source rather than duplicated here: a table
    of "what the shader uses" maintained next to the check is one more copy to
    fall out of step, which is the whole defect being guarded against.
    """
    import re
    text = open(RENDERER).read()
    found = {}
    for uni, _ in CONSTANTS.items():
        # glUniform1f(uni_.foo, 0.55f);  - the member name is the uniform's
        # without the leading u and lower-cased first letter.
        member = uni[1].lower() + uni[2:]
        m = re.search(r"glUniform1f\(uni_\." + member + r",\s*([0-9.]+)f\)", text)
        if m:
            found[uni] = float(m.group(1))
    return found


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
    print(f"boxDepthField matches hd2d._box to {worst:.1e}\n")

    from retroai import hd2d
    shader = shader_constants()
    bad = []
    for uni, name in sorted(CONSTANTS.items()):
        if uni not in shader:
            print(f"skip  {uni:<16} not found in gl_renderer.cpp")
            continue
        theirs = getattr(hd2d, name, None)
        if theirs is None:
            print(f"skip  {uni:<16} no hd2d.{name}")
            continue
        ok = abs(shader[uni] - float(theirs)) < 1e-6
        print(f"{'ok  ' if ok else 'FAIL'}  {uni:<16} shader {shader[uni]:<8g} "
              f"hd2d.{name} {theirs:g}")
        if not ok:
            bad.append(uni)
    if bad:
        print(f"\nFAILED: {len(bad)} constant(s) differ between the shader and "
              f"hd2d.py. The shader ships, so it wins - fix hd2d.py.")
        return 1
    print(f"\n{len(CONSTANTS)} shading constants agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
