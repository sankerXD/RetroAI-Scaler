#!/usr/bin/env python3
"""
Compiles every GLSL string embedded in the C++ sources.

A shader that fails to compile does not crash: the renderer degrades to a fully
transparent overlay, exactly as the safety design requires. That makes a broken
shader look like "the enhanced picture disappeared" with nothing else wrong,
and the only evidence is one line in logcat. It has cost a device round trip
once already - a comment edit silently swallowed a function definition while
leaving its call site.

    python3 tools/check_shaders.py

Uses the glslc that ships with the NDK. Exits non-zero on any failure.
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(ROOT, "app", "src", "main", "cpp")

def find_glslc():
    for base in (os.environ.get("ANDROID_NDK_HOME"),
                 os.path.expanduser("~/Library/Android/sdk/ndk")):
        if not base:
            continue
        for dirpath, _, files in os.walk(base):
            if "glslc" in files:
                return os.path.join(dirpath, "glslc")
    return None

# R"glsl( ... )glsl" raw string literals, with the name of the variable holding them.
PATTERN = re.compile(r'(\w+)\s*=\s*R"glsl\((.*?)\)glsl"', re.S)

def main():
    glslc = find_glslc()
    if not glslc:
        print("glslc not found (set ANDROID_NDK_HOME); skipping shader check")
        return 0

    failures = 0
    checked = 0
    for dirpath, _, files in os.walk(CPP):
        if "libs" in dirpath.split(os.sep):
            continue
        for name in files:
            if not name.endswith((".cpp", ".h")):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            for var, src in PATTERN.findall(text):
                stage = "vert" if "VERTEX" in var.upper() else "frag"
                # The external-texture extension is an ES runtime feature glslc
                # does not know; stub the sampler so the rest still type-checks.
                # glslc only emits SPIR-V, which will not accept "300 es" and
                # wants explicit locations. Neither matters for what this check
                # is for - undeclared identifiers, type errors, bad syntax - so
                # retarget the source rather than the real shader.
                src = src.replace("#version 300 es", "#version 320 es")
                if "samplerExternalOES" in src:
                    src = src.replace(
                        "#extension GL_OES_EGL_image_external_essl3 : require\n", "")
                    src = src.replace("samplerExternalOES", "sampler2D")
                checked += 1
                with tempfile.NamedTemporaryFile("w", suffix="." + stage,
                                                 delete=False) as tf:
                    tf.write(src)
                    tmp = tf.name
                out = subprocess.run(
                    [glslc, "-fshader-stage=" + stage, "--target-env=opengl",
                     "-fauto-map-locations", "-fauto-bind-uniforms",
                     tmp, "-o", os.devnull],
                    capture_output=True, text=True)
                os.unlink(tmp)
                rel = os.path.relpath(path, ROOT)
                if out.returncode != 0:
                    failures += 1
                    print(f"FAIL  {rel}: {var}")
                    print(out.stderr.replace(tmp, var).rstrip())
                else:
                    print(f"ok    {rel}: {var}")
    print(f"\n{checked} shader(s) checked, {failures} failed")
    return 1 if failures else 0

if __name__ == "__main__":
    sys.exit(main())
