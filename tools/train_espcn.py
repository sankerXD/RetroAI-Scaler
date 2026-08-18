#!/usr/bin/env python3
"""
ESPCN (Y-channel) trainer + direct NCNN exporter for Retro-AI-Scaler.

Why this exists: generate_espcn_models.py only exported a RANDOMLY INITIALISED
network. A random ESPCN outputs noise, so the "AI upscale" was never going to
work no matter how the inference path was wired.

Training data is synthetic and generated to match the actual runtime input
distribution: LR is not a blurred downsample of HR, it is the SAME vector scene
rendered at 1x. A retro game frame is authored art at native resolution, not a
downscaled photo, so training on blur-inversion pairs would teach the net to
over-sharpen. Scenes are rendered at 12x and area-averaged down to 3x (target)
and 1x (input), plus a slice of hard-edged pixel-art pairs so the net learns to
keep sprite edges crisp instead of smoothing them.

Export writes ncnn .param/.bin directly - no onnx2ncnn round trip:
  .bin layout per Convolution = [uint32 tag=0][weights f32][bias f32]
  weights are [out_ch][in_ch][kh][kw], the same layout PyTorch uses.

Usage:
    python tools/train_espcn.py [--epochs 60] [--samples 6000]
"""

import argparse
import math
import os
import random
import struct

import numpy as np
import torch
import torch.nn as nn
from PIL import Image, ImageDraw

LR_PATCH = 32
SUPER = 12  # supersampling factor used to render ground truth scenes

# Scales to train. The app picks one at runtime ("AI 增强倍率"), independently
# of how big the picture is drawn on screen.
# 6 is here because it is what the handheld actually draws: 240x160 at the
# largest integer multiple that fits 1920x1080 is 6x. Reconstructing below that
# means the result gets upscaled again; above it, the detail is computed and
# then discarded on the way down.
SCALES = (2, 3, 4, 6)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "models")

# Channel counts MUST stay multiples of 8. ncnn's fp16 packing layout falls back
# to a scalar path otherwise: a measured 12/6 net ran at 22.9ms while the much
# larger 32/16 net ran at 13.8ms on the same device purely because of packing.
VARIANTS = {
    # name: (channels, number of hidden 3x3 layers)
    # fast/hq stay tiny enough for a Cortex-A76 pair on CPU.
    "fast": (16, 1),
    "hq": (32, 1),
    # ultra is for Snapdragon 8 Elite class hardware running ncnn on Vulkan:
    # ~30x the arithmetic of hq, which is what buys real shape reconstruction
    # instead of slightly smarter interpolation.
    "ultra": (48, 4),
}


class ESPCN(nn.Module):
    """
    5x5 head, N hidden 3x3 layers, sub-pixel tail.

    ReLU rather than the original TanH: TanH saturates, which caps how much
    contrast the deep variants can build up, and it is slower in ncnn where
    ReLU folds into the convolution itself.
    """

    def __init__(self, channels, hidden, scale=3):
        super().__init__()
        self.head = nn.Conv2d(1, channels, kernel_size=5, padding=2)
        self.hidden = nn.ModuleList(
            nn.Conv2d(channels, channels, kernel_size=3, padding=1) for _ in range(hidden)
        )
        self.tail = nn.Conv2d(channels, scale * scale, kernel_size=3, padding=1)
        self.shuffle = nn.PixelShuffle(scale)

    def forward(self, x):
        x = torch.relu(self.head(x))
        for layer in self.hidden:
            x = torch.relu(layer(x))
        return self.shuffle(self.tail(x))

    def conv_list(self):
        return [self.head, *self.hidden, self.tail]


# ---------------------------------------------------------------- data

def _rand_color():
    return random.randint(0, 255)


def _draw_scene(size):
    """Render a random vector scene at `size` pixels (square, grayscale)."""
    img = Image.new("L", (size, size), _rand_color())
    d = ImageDraw.Draw(img)
    for _ in range(random.randint(3, 12)):
        kind = random.random()
        x0, y0 = random.uniform(-0.2, 1.0), random.uniform(-0.2, 1.0)
        w = random.uniform(0.08, 0.7)
        h = random.uniform(0.08, 0.7)
        box = [x0 * size, y0 * size, (x0 + w) * size, (y0 + h) * size]
        col = _rand_color()
        if kind < 0.35:
            d.rectangle(box, fill=col)
        elif kind < 0.6:
            d.ellipse(box, fill=col)
        elif kind < 0.8:
            d.line(box, fill=col, width=max(1, int(random.uniform(0.01, 0.06) * size)))
        else:
            pts = [
                (random.uniform(0, 1) * size, random.uniform(0, 1) * size)
                for _ in range(random.randint(3, 6))
            ]
            d.polygon(pts, fill=col)
    return img


def _area_down(img, factor):
    target = (img.width // factor, img.height // factor)
    return img.resize(target, Image.BOX)


def _vector_pair(scale):
    """LR and HR are independent renderings of one scene - not a blur pair."""
    scene = _draw_scene(LR_PATCH * SUPER)
    hr = _area_down(scene, SUPER // scale)  # supersampled -> HR
    lr = _area_down(scene, SUPER)           # supersampled -> 1x
    return lr, hr


def _pixelart_pair(scale):
    """Hard-edged sprite art: HR is the same blocky art at 3x (nearest)."""
    lr = Image.new("L", (LR_PATCH, LR_PATCH), _rand_color())
    d = ImageDraw.Draw(lr)
    palette = [_rand_color() for _ in range(random.randint(2, 6))]
    block = random.choice([1, 1, 2, 2, 4])
    for by in range(0, LR_PATCH, block):
        for bx in range(0, LR_PATCH, block):
            if random.random() < 0.45:
                d.rectangle([bx, by, bx + block - 1, by + block - 1],
                            fill=random.choice(palette))
    # a few 1px details: text-like strokes that must survive upscaling
    for _ in range(random.randint(0, 6)):
        x = random.randint(0, LR_PATCH - 1)
        y = random.randint(0, LR_PATCH - 1)
        d.line([x, y, x, min(LR_PATCH - 1, y + random.randint(1, 6))],
               fill=random.choice(palette), width=1)
    hr_patch = LR_PATCH * scale
    hr = lr.resize((hr_patch, hr_patch), Image.NEAREST)
    # very light smoothing so the target is not a pure staircase
    hr = hr.resize((hr_patch * 2, hr_patch * 2), Image.BILINEAR).resize(
        (hr_patch, hr_patch), Image.BOX)
    return lr, hr


def build_dataset(n, scale):
    hr_patch = LR_PATCH * scale
    lrs = np.zeros((n, 1, LR_PATCH, LR_PATCH), dtype=np.float32)
    hrs = np.zeros((n, 1, hr_patch, hr_patch), dtype=np.float32)
    for i in range(n):
        lr, hr = _pixelart_pair(scale) if random.random() < 0.3 else _vector_pair(scale)
        lrs[i, 0] = np.asarray(lr, dtype=np.float32) / 255.0
        hrs[i, 0] = np.asarray(hr, dtype=np.float32) / 255.0
        if (i + 1) % 500 == 0:
            print(f"    generated {i + 1}/{n}")
    return torch.from_numpy(lrs), torch.from_numpy(hrs)


# ---------------------------------------------------------------- export

def write_ncnn(model, channels, hidden, scale, param_path, bin_path):
    """
    ReLU is folded into each Convolution (param 9=1) instead of being its own
    layer: fewer blobs, and ncnn fuses it into the same kernel.
    """
    convs = model.conv_list()
    layer_count = 2 + len(convs)          # Input + convs + PixelShuffle
    blob_count = layer_count

    lines = ["Input            input_y          0 1 input_y"]
    prev = "input_y"
    for idx, conv in enumerate(convs):
        out_blob = f"conv{idx}_blob"
        out_ch = conv.out_channels
        k = conv.kernel_size[0]
        pad = conv.padding[0]
        is_tail = idx == len(convs) - 1
        act = "" if is_tail else " 9=1"    # 9=1 -> ReLU
        lines.append(
            f"Convolution      conv{idx}            1 1 {prev} {out_blob} "
            f"0={out_ch} 1={k} 2=1 3=1 4={pad} 5=1 6={conv.weight.numel()}{act}"
        )
        prev = out_blob
    lines.append(f"PixelShuffle     ps               1 1 {prev} output_y 0={scale}")

    with open(param_path, "w") as f:
        f.write("7767517\n")
        f.write(f"{layer_count} {blob_count}\n")
        f.write("\n".join(lines) + "\n")

    with open(bin_path, "wb") as f:
        for conv in convs:
            f.write(struct.pack("<I", 0))  # fp32 tag
            conv.weight.detach().cpu().numpy().astype("<f4").tofile(f)
            conv.bias.detach().cpu().numpy().astype("<f4").tofile(f)


# ---------------------------------------------------------------- train

def psnr(a, b):
    mse = torch.mean((a - b) ** 2).item()
    return 99.0 if mse <= 1e-12 else 10 * math.log10(1.0 / mse)


def train_variant(name, channels, hidden, scale, lr_data, hr_data, epochs, device):
    print(f"\n=== training '{name}' {scale}x (ch={channels}, hidden={hidden}) ===")
    model = ESPCN(channels, hidden, scale).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
    loss_fn = nn.L1Loss()

    n = lr_data.shape[0]
    n_val = max(64, n // 20)
    lr_tr, hr_tr = lr_data[:-n_val].to(device), hr_data[:-n_val].to(device)
    lr_va, hr_va = lr_data[-n_val:].to(device), hr_data[-n_val:].to(device)
    batch = 64

    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(lr_tr.shape[0], device=device)
        total = 0.0
        for i in range(0, lr_tr.shape[0], batch):
            idx = perm[i:i + batch]
            opt.zero_grad()
            out = model(lr_tr[idx])
            loss = loss_fn(out, hr_tr[idx])
            loss.backward()
            opt.step()
            total += loss.item() * idx.numel()
        sched.step()
        if (epoch + 1) % 10 == 0 or epoch == 0:
            model.eval()
            with torch.no_grad():
                val = model(lr_va).clamp(0, 1)
            print(f"  epoch {epoch + 1:3d}  train L1={total / lr_tr.shape[0]:.5f}  "
                  f"val PSNR={psnr(val, hr_va):.2f} dB")

    model.eval()
    with torch.no_grad():
        val = model(lr_va).clamp(0, 1)
        bilinear = torch.nn.functional.interpolate(
            lr_va, scale_factor=scale, mode="bilinear", align_corners=False).clamp(0, 1)
    print(f"  FINAL  ESPCN={psnr(val, hr_va):.2f} dB   "
          f"bilinear baseline={psnr(bilinear, hr_va):.2f} dB")

    os.makedirs(ASSET_DIR, exist_ok=True)
    param_path = os.path.join(ASSET_DIR, f"espcn_y_{scale}x_{name}.param")
    bin_path = os.path.join(ASSET_DIR, f"espcn_y_{scale}x_{name}.bin")
    write_ncnn(model.cpu(), channels, hidden, scale, param_path, bin_path)
    macs_per_px = (25 * channels + hidden * 9 * channels * channels
                   + 9 * channels * scale * scale)
    print(f"  wrote {os.path.basename(param_path)} / {os.path.basename(bin_path)} "
          f"({os.path.getsize(bin_path) / 1024:.1f} KB, {macs_per_px} MAC/px "
          f"= {macs_per_px * 240 * 160 / 1e6:.0f} MMAC per GBA frame)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--samples", type=int, default=6000)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--only", type=str, default=None, help="train just one variant")
    ap.add_argument("--scales", type=int, nargs="+", default=list(SCALES),
                    metavar="N",
                    help="which factors to train; defaults to all of "
                         + " ".join(str(x) for x in SCALES)
                         + ". Adding one factor does not require redoing the "
                           "others - each is its own model.")
    args = ap.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    scales = [s for s in args.scales if s in SCALES] or list(SCALES)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"device: {device}")

    for scale in scales:
        print(f"\n########## scale {scale}x ##########")
        print(f"generating {args.samples} synthetic LR/HR pairs...")
        lr_data, hr_data = build_dataset(args.samples, scale)
        for name, (channels, hidden) in VARIANTS.items():
            if args.only and name != args.only:
                continue
            train_variant(name, channels, hidden, scale, lr_data, hr_data,
                          args.epochs, device)


if __name__ == "__main__":
    main()
