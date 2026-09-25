"""按 Cheatwazi 的真实绘制规格离屏渲染三张预览图（等待 / 读条 / 结果揭晓）。

规格与 ChwaziView 保持一致：色表 colors.xml、触点半径 min(w,h)*0.085、
读条弧宽 3.5dp 间距 7dp、hint 字号 16sp、赢家 1.25 倍 + 3dp 白圈。
运行：python tools/render_preview.py  输出到 docs/preview_*.png
"""
import math
import os

from PIL import Image, ImageDraw

W, H = 1080, 2400
D = 2.75  # 演示按 440dpi 屏的密度换算 dp/sp

BG = (6, 6, 6)
POINTER_COLORS = [
    (255, 214, 10),   # p0 黄
    (255, 45, 149),   # p1 品红
    (0, 229, 255),    # p2 青
    (118, 255, 3),    # p3 绿
]
RING = (255, 255, 255)
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "docs")


def base_radius(n):
    r = min(W, H) * 0.085
    if n > 5:
        r *= (5.0 / n) ** 0.35
    return r


def brighten(c, amount=0.08):
    """径向渐变中心色：HSL 亮度 +8% 的近似（RGB 线性提亮）"""
    return tuple(min(255, int(v + 255 * amount * 0.35)) for v in c)


def draw_pointer(im, draw, x, y, r, color, alpha=1.0, ring=False):
    """径向渐变实心圆（中心亮 8%），与 ChwaziView 的 RadialGradient 一致"""
    steps = 24
    center = brighten(color)
    for i in range(steps, 0, -1):
        t = i / steps
        layer = tuple(int(center[j] * t + color[j] * (1 - t)) for j in range(3))
        a = int(255 * alpha)
        draw.ellipse(
            [x - r * t, y - r * t, x + r * t, y + r * t],
            fill=layer + (a,),
        )
    if ring:
        w = max(1, int(3 * D))
        draw.ellipse([x - r, y - r, x + r, y + r], outline=RING + (255,), width=w)


def draw_readout_arc(draw, x, y, r, color, progress):
    gap = 7 * D
    w = max(1, int(3.5 * D))
    bbox = [x - r - gap, y - r - gap, x + r + gap, y + r + gap]
    draw.arc(bbox, start=-90, end=-90 + 360 * progress, fill=color + (255,), width=w)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    r0 = base_radius(3)
    # —— 等待：三根手指已就位（呼吸中段） ——
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im, "RGBA")
    pts = [(300, 900), (700, 1100), (500, 1500)]
    for i, (x, y) in enumerate(pts):
        draw_pointer(im, d, x, y, r0, POINTER_COLORS[i])
    im.save(os.path.join(OUT_DIR, "preview_waiting.png"))

    # —— 读条：四指 + 外围 70% 进度弧 ——
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im, "RGBA")
    pts = [(280, 800), (800, 900), (350, 1500), (760, 1600)]
    r4 = base_radius(4)
    for i, (x, y) in enumerate(pts):
        draw_pointer(im, d, x, y, r4, POINTER_COLORS[i])
        draw_readout_arc(d, x, y, r4, POINTER_COLORS[i], 0.7)
    im.save(os.path.join(OUT_DIR, "preview_readout.png"))

    # —— 结果揭晓：内定赢家颜色扩散覆盖整屏（满屏色 + 白圈，无文字） ——
    im = Image.new("RGB", (W, H), POINTER_COLORS[0])
    d = ImageDraw.Draw(im, "RGBA")
    wx, wy = 300, 1150
    wr = r0 * 1.25
    draw_pointer(im, d, wx, wy, wr, POINTER_COLORS[0], ring=True)
    im.save(os.path.join(OUT_DIR, "preview_result.png"))

    print("done:", os.listdir(OUT_DIR))


if __name__ == "__main__":
    main()
