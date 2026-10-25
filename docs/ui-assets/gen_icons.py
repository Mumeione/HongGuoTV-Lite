# -*- coding: utf-8 -*-
"""线稿白果图标生成器（B 方案，mockup #ic-line 几何）。

产出：
- app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png（48/72/96/144/192）
- app/src/main/res/drawable-nodpi/tv_banner.png（320×180 设计，按 2x=640×360 出图保清晰）

几何全部来自 docs/UI/mockups/2026-10-21-B方案v4.html 的 <symbol id="ic-line">：
96×96 视口、圆角方底 #0B0F15、1.6px 描边 #2A3546、白线果（圆 r20.5 stroke 4.4）、
果柄（三次贝塞尔 stroke 4.2 圆帽）、白叶（旋转 -22° 的实心椭圆）。
"""
from PIL import Image, ImageDraw, ImageFont
import math
import os

REPO = r"D:\Documents\Projects\HongGuoTV\HongGuoTV-Lite"
RES = os.path.join(REPO, "app", "src", "main", "res")

BG = (0x0B, 0x0F, 0x15, 255)
BORDER = (0x2A, 0x35, 0x46, 255)
WHITE = (255, 255, 255, 255)
CHROME = (0x0F, 0x15, 0x1D, 255)
CHROME_LINE = (0x1B, 0x24, 0x30, 255)
DIM = (0x5E, 0x6A, 0x7A, 255)

# ---- ic-line 几何（96 视口） ----
TILE_RADIUS = 22.0
BORDER_INSET = 1.2
BORDER_RADIUS = 21.0
BORDER_WIDTH = 1.6
CIRCLE_C = (48.0, 55.0)
CIRCLE_R = 20.5
CIRCLE_W = 4.4
STEM_START = (48.0, 35.0)
STEM_C1 = (48.0, 35.0 - 6.5)
STEM_C2 = (48.0 + 3.6, 35.0 - 10.8)
STEM_END = (48.0 + 9.4, 35.0 - 12.0)
STEM_W = 4.2
LEAF_C = (63.0, 24.5)
LEAF_RX = 12.0
LEAF_RY = 6.4
LEAF_ANGLE = -22.0


def bezier(p0, p1, p2, p3, steps=48):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def rotated_ellipse(cx, cy, rx, ry, angle_deg, steps=72):
    a = math.radians(angle_deg)
    cos, sin = math.cos(a), math.sin(a)
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        x = rx * math.cos(t)
        y = ry * math.sin(t)
        pts.append((cx + x * cos - y * sin, cy + x * sin + y * cos))
    return pts


def draw_fruit(draw: ImageDraw.ImageDraw, s: float, tile: bool = True) -> None:
    """在 96×s 的画布上画 ic-line（s = 像素/视口单位）。tile=True 画底板与描边。"""
    if tile:
        draw.rounded_rectangle(
            [0, 0, 96 * s - 1, 96 * s - 1], radius=TILE_RADIUS * s, fill=BG
        )
        inset = BORDER_INSET * s
        draw.rounded_rectangle(
            [inset, inset, 96 * s - 1 - inset, 96 * s - 1 - inset],
            radius=BORDER_RADIUS * s,
            outline=BORDER,
            width=max(1, round(BORDER_WIDTH * s)),
        )
    # 果身：白线圆
    cx, cy = CIRCLE_C[0] * s, CIRCLE_C[1] * s
    r = CIRCLE_R * s
    w = max(1, round(CIRCLE_W * s))
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=WHITE, width=w)
    # 果柄：贝塞尔 + 圆帽
    pts = [(x * s, y * s) for x, y in bezier(STEM_START, STEM_C1, STEM_C2, STEM_END)]
    sw = max(1, round(STEM_W * s))
    draw.line(pts, fill=WHITE, width=sw, joint="curve")
    cap = sw / 2
    for p in (pts[0], pts[-1]):
        draw.ellipse([p[0] - cap, p[1] - cap, p[0] + cap, p[1] + cap], fill=WHITE)
    # 白叶：旋转椭圆
    leaf = [(x * s, y * s) for x, y in rotated_ellipse(LEAF_C[0], LEAF_C[1], LEAF_RX, LEAF_RY, LEAF_ANGLE)]
    draw.polygon(leaf, fill=WHITE)


def render_icon(px: int) -> Image.Image:
    """96 视口 → px 像素。先 4x 超采样再缩小，边缘才不会糊。"""
    ss = 4
    big = px * ss
    s = big / 96.0
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw_fruit(ImageDraw.Draw(img), s, tile=True)
    return img.resize((px, px), Image.LANCZOS)


def render_banner(scale: float) -> Image.Image:
    """320×180 设计 → scale 倍出图。图标在左（52dp），右侧「红果TV / LITE」。"""
    w, h = int(320 * scale), int(180 * scale)
    s = scale
    img = Image.new("RGBA", (w, h), CHROME)
    draw = ImageDraw.Draw(img)
    # 1px 边框 + 圆角（mockup .banner border #1B2430 radius 10）
    inset = round(1 * s)
    draw.rounded_rectangle(
        [inset, inset, w - 1 - inset, h - 1 - inset],
        radius=round(10 * s),
        outline=CHROME_LINE,
        width=max(1, round(1 * s)),
    )
    # 图标：52dp，左缘留 18dp，垂直居中（mockup banner 内 padding 0 18px, gap 14px）
    tile = 52 * s
    ox, oy = 18 * s, (180 - 52) / 2 * s
    # 把 ic-line 画在 (ox, oy) 起、52dp 大小：临时画布复用 draw_fruit，再贴过去
    icon_ss = 4
    icon_px = int(round(52 * s))
    tmp = Image.new("RGBA", (icon_px * icon_ss, icon_px * icon_ss), (0, 0, 0, 0))
    draw_fruit(ImageDraw.Draw(tmp), icon_px * icon_ss / 96.0, tile=True)
    tmp = tmp.resize((icon_px, icon_px), Image.LANCZOS)
    img.paste(tmp, (int(ox), int(oy)), tmp)
    # 文案：红果TV 24px 白 600 字重；LITE 11px #5E6A7A 加字距
    tx = ox + tile + 14 * s
    bold = ImageFont.truetype(r"C:\Windows\Fonts\msyhbd.ttc", int(round(24 * s)))
    cy = 180 / 2
    name_y = cy - 13 * s  # 两行的上沿
    draw.text((tx, name_y), "红果TV", font=bold, fill=WHITE)
    lite = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", int(round(11 * s)))
    lite_y = cy + 3 * s
    lx = tx
    for ch in "LITE":
        draw.text((lx, lite_y), ch, font=lite, fill=DIM)
        lx += lite.getlength(ch) + 0.5 * s
    return img


def main():
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in densities.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        render_icon(px).save(os.path.join(folder, "ic_launcher.png"))
        print(f"mipmap-{name}/ic_launcher.png {px}px")
    banner = render_banner(2.0)
    banner.save(os.path.join(RES, "drawable-nodpi", "tv_banner.png"))
    print("drawable-nodpi/tv_banner.png 640x360 (320x180 @2x)")


if __name__ == "__main__":
    main()
