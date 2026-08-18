#!/usr/bin/env python3
"""
Builds the engine comparison strip for the README.

Top row: each engine's whole frame. Bottom row: the SAME region of each, at
1:1 output pixels, because the difference between these engines is a per-pixel
one and a scaled-down full frame hides most of it.

The captures are 1920x1080 device screenshots. Two things are cropped away: the
black letterbox, and the system monitor bar across the top - it is the
handheld's own overlay, not the app's, and its numbers differ between shots.
"""
from PIL import Image, ImageDraw, ImageFont
import os

SRC = "/Users/kersan/Downloads/RA AI Render/pictures"
OUT = os.path.join(SRC, "comparison.png")

# Measured on all four, identical: the game area inside the letterbox.
CONTENT = (240, 60, 1680, 1020)          # 1440x960, exactly 6x of 240x160

# Panels in engine order, least to most processed. The Chinese half of each
# label says something the English one does not - repeating "HD-2D" or
# "ESPCN 6x" in both would waste the line.
ALL_PANELS = {
    "original":   ("原图.png",        "Original",   "未处理"),
    "pixel-edge": ("像素边缘重建.png", "Pixel-edge", "像素边缘重建"),
    "hd2d":       ("HD2D.png",        "HD-2D",      "深度光影"),
    "espcn":      ("ESPCN.png",       "ESPCN 6x",   "亮度超分"),
}

# THREE COLUMNS, NOT FOUR. GitHub renders a README image at about 890 px wide,
# so a four-column strip arrives at roughly 220 px per panel and nothing in it
# can be read without clicking through. Three is about 290.
#
# Which three is a real choice, and it was made the other way first. Three
# processed engines side by side look much alike to anyone who has not used
# them - built without "original", only HD-2D stood out, and nothing in the
# strip said what the app does. "pixel-edge" is the one that can go, because
# HD-2D IS pixel-edge with the lighting on top, so it is still on screen.
SHOW = ["original", "hd2d", "espcn"]
PANELS = [ALL_PANELS[k] for k in SHOW]

COL_W = 600                               # width of one column
COL_H = COL_W * 960 // 1440               # full frame, keeping 3:2
ZOOM_W, ZOOM_H = COL_W, 400               # 1:1 crop, so this is its real size
# Where the 1:1 crop is taken from, in content coordinates. The party on the
# ledge: sprites, hard edges, and terrain relief in one place, which is what
# separates these four.
ZOOM_AT = (400, 430)
GAP, MARGIN = 10, 14
LABEL_H, CAPTION_H = 46, 38

BG = (17, 17, 20)
FG = (238, 238, 242)
DIM = (150, 150, 158)

def font(path, size):
    for p in (path, "/System/Library/Fonts/Supplemental/Arial.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()

f_en = font("/System/Library/Fonts/Helvetica.ttc", 27)
f_zh = font("/System/Library/Fonts/Hiragino Sans GB.ttc", 22)
f_cap = font("/System/Library/Fonts/Hiragino Sans GB.ttc", 20)

W = MARGIN * 2 + COL_W * len(PANELS) + GAP * (len(PANELS) - 1)
H = MARGIN + LABEL_H + COL_H + GAP + ZOOM_H + CAPTION_H
sheet = Image.new("RGB", (W, H), BG)
draw = ImageDraw.Draw(sheet)

for i, (name, en, zh) in enumerate(PANELS):
    src = Image.open(os.path.join(SRC, name)).convert("RGB").crop(CONTENT)
    x = MARGIN + i * (COL_W + GAP)

    draw.text((x, MARGIN + 2), en, font=f_en, fill=FG)
    wen = draw.textlength(en, font=f_en)
    draw.text((x + wen + 12, MARGIN + 9), zh, font=f_zh, fill=DIM)

    y = MARGIN + LABEL_H
    # LANCZOS for the overview: these are being shrunk, and nearest would alias
    # the pixel art into moire that belongs to neither engine.
    sheet.paste(src.resize((COL_W, COL_H), Image.LANCZOS), (x, y))

    # The detail row is a straight copy - no resampling at all, so what is on
    # screen is what the engine produced.
    zx, zy = ZOOM_AT
    sheet.paste(src.crop((zx, zy, zx + ZOOM_W, zy + ZOOM_H)), (x, y + COL_H + GAP))

cap_en = "top: whole frame   ·   bottom: the same region at 1:1 output pixels"
cap_zh = "上排为整帧   ·   下排为同一区域的 1:1 输出像素"
cy = H - CAPTION_H + 8
draw.text((MARGIN, cy), cap_en, font=f_cap, fill=DIM)
sep_x = MARGIN + draw.textlength(cap_en, font=f_cap) + 26
draw.text((sep_x, cy), "|", font=f_cap, fill=(70, 70, 76))
draw.text((sep_x + 18, cy), cap_zh, font=f_cap, fill=DIM)

# 256 colours. This is a README's first image, so its weight matters, and the
# content is pixel art plus two smooth gradients - quantising it costs 1.4% mean
# error with no banding visible in the sky, for a third of the bytes.
sheet.quantize(colors=256, method=Image.MEDIANCUT,
               dither=Image.FLOYDSTEINBERG).save(OUT, optimize=True)
print(f"{OUT}  {sheet.width}x{sheet.height}  {os.path.getsize(OUT) / 1024:.0f} KB")
