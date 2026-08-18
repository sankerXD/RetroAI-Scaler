#!/usr/bin/env python3
"""
Compiles common/scroll_estimator.cpp for the host and checks that it recovers
scrolls it is given, and refuses the ones it cannot explain.

    python3 tools/check_scroll_estimator.py

WHY THIS IS CHECKED RATHER THAN TRUSTED

The vector this produces shifts the depth the lighting is built from. A wrong
one does not fail loudly - it smears the shading away from the picture, which
looks like the artefact it was added to remove. And section 13.3 records the
last attempt at motion compensation in this project going in with the SIGN
REVERSED and being measured, twice, before anyone noticed.

So the sign is asserted here in the direction the caller has to use it, on
synthetic frames where the true answer is known by construction.

THE REFUSALS MATTER AS MUCH AS THE HITS

Confidence is what decides whether the shift is applied. A frame whose change
is a fade, or two layers moving differently, has no single vector - the right
answer there is a low confidence and no compensation, not a best guess.
"""
from __future__ import annotations

import ctypes
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "app", "src", "main", "cpp", "common", "scroll_estimator.cpp")

SHIM = r"""
#include <cstdint>
#include "scroll_estimator.h"
extern "C" void est(const uint8_t* prev, const uint8_t* cur, int w, int h,
                    int ch, int radius, int seedx, int seedy, float* out) {
    retroai::ScrollEstimate e =
        retroai::estimateScroll(prev, cur, w, h, ch, radius, seedx, seedy);
    out[0] = e.dx; out[1] = e.dy; out[2] = e.confidence;
}
"""

def _ensure_numpy():
    """
    Re-exec under an interpreter that has numpy, if this one does not.

    Gradle invokes these with the system python3, which on a developer Mac
    usually has no numpy - so without this the check prints "skip", exits 0, and
    the build gate silently stops gating. A check that cannot fail is worse than
    no check, because it is also a claim that something was verified.
    """
    try:
        import numpy  # noqa: F401
        return
    except ImportError:
        pass
    import glob
    for cand in glob.glob(os.path.join(os.path.dirname(ROOT), "*", ".venv", "bin", "python")):
        if subprocess.run([cand, "-c", "import numpy"],
                          capture_output=True).returncode == 0:
            os.execv(cand, [cand] + sys.argv)
    print("skip: no interpreter with numpy found")
    raise SystemExit(0)

W, H, CH, RADIUS = 240, 160, 3, 8


def main() -> int:
    _ensure_numpy()
    import numpy as np

    with tempfile.TemporaryDirectory() as tmp:
        shim = os.path.join(tmp, "shim.cpp")
        with open(shim, "w") as f:
            f.write(SHIM)
        lib = os.path.join(tmp, "libscroll.so")
        r = subprocess.run(
            ["c++", "-std=c++17", "-O2", "-shared", "-fPIC",
             f"-I{os.path.dirname(SRC)}", SRC, shim, "-o", lib],
            capture_output=True, text=True)
        if r.returncode != 0:
            print("FAIL could not compile scroll_estimator.cpp for the host")
            print(r.stderr.strip())
            return 1

        dll = ctypes.CDLL(lib)
        u8 = ctypes.POINTER(ctypes.c_uint8)
        dll.est.argtypes = [u8, u8, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                            ctypes.c_int, ctypes.c_int, ctypes.c_int,
                            ctypes.POINTER(ctypes.c_float)]

        def run(prev, cur, seed=(0, 0)):
            out = np.zeros(3, np.float32)
            dll.est(prev.ctypes.data_as(u8), cur.ctypes.data_as(u8),
                    W, H, CH, RADIUS, seed[0], seed[1],
                    out.ctypes.data_as(ctypes.POINTER(ctypes.c_float)))
            return float(out[0]), float(out[1]), float(out[2])

        rng = np.random.default_rng(7)
        # Blocky, high-contrast, and repeated at several scales: pixel art, not
        # smooth noise. A smooth field matches everywhere and would flatter the
        # search.
        base = rng.integers(0, 256, (H // 8 + 4, W // 8 + 4, CH), dtype=np.uint8)
        base = np.repeat(np.repeat(base, 8, 0), 8, 1)
        base = (base // 48 * 48).astype(np.uint8)

        def scrolled(dx, dy):
            """Shift by (dx, dy), edge-clamped, the way a scrolling scene moves."""
            ys = np.clip(np.arange(H) - dy, 0, base.shape[0] - 1)
            xs = np.clip(np.arange(W) - dx, 0, base.shape[1] - 1)
            return np.ascontiguousarray(base[ys][:, xs])

        prev = scrolled(0, 0)
        failures = 0

        print("recovering known scrolls (sign included):")
        for dx, dy in [(0, 0), (0, 1), (0, -1), (1, 0), (-1, 0),
                       (0, 3), (2, -2), (-4, 5), (6, 6), (0, 8)]:
            cur = scrolled(dx, dy)
            gx, gy, conf = run(prev, cur)
            ok = (gx, gy) == (dx, dy) and conf > 0.9
            failures += not ok
            print(f"  {'ok  ' if ok else 'FAIL'}  true ({dx:+d},{dy:+d})  "
                  f"got ({gx:+.0f},{gy:+.0f})  confidence {conf:.3f}")

        print("\nthe seed is a shortcut, never an answer:")
        cur = scrolled(0, 2)
        gx, gy, conf = run(prev, cur, seed=(0, 5))     # deliberately wrong seed
        ok = (gx, gy) == (0, 2)
        failures += not ok
        print(f"  {'ok  ' if ok else 'FAIL'}  wrong seed (0,+5), true (0,+2), "
              f"got ({gx:+.0f},{gy:+.0f})")

        print("\nrefusing what no single vector explains:")
        # A cross-fade: every pixel changes, nothing translates.
        faded = np.clip(base.astype(np.int16) * 0.5 + 60, 0, 255).astype(np.uint8)
        cases = [
            ("cross-fade", np.ascontiguousarray(faded[:H, :W])),
            ("random recompose", rng.integers(0, 256, (H, W, CH), dtype=np.uint8)),
        ]
        for label, cur in cases:
            gx, gy, conf = run(prev, np.ascontiguousarray(cur))
            ok = conf < 0.5
            failures += not ok
            print(f"  {'ok  ' if ok else 'FAIL'}  {label:18} confidence {conf:.3f}"
                  f"  (must be low)")

        # Two layers moving oppositely: a real parallax frame. There is no single
        # right vector, so the requirement is a confidence that does not claim one.
        cur = scrolled(0, 2).copy()
        cur[H // 2:] = scrolled(0, -3)[H // 2:]
        gx, gy, conf = run(prev, np.ascontiguousarray(cur))
        ok = conf < 0.75
        failures += not ok
        print(f"  {'ok  ' if ok else 'FAIL'}  {'two layers':18} confidence {conf:.3f}"
              f"  got ({gx:+.0f},{gy:+.0f})  (must not be certain)")

    if failures:
        print(f"\nFAILED: {failures} case(s)")
        return 1
    print("\nscroll estimator recovers its scrolls and refuses the rest")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
