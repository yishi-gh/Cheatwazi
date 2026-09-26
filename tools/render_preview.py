"""按 Cheatwazi 的真实绘制规格离屏渲染三张预览图（等待 / 读条 / 结果揭晓）。

规格与 ChwaziView 保持一致：色表 colors.xml、触点圆半径 min(w,h)*0.125
（大色盘 0.66R + 粗外环带 0.20R，中线 0.90R）、读条=浅色弧带沿外环扫入、
结果=赢家色自四边合拢只留 2R 孔露出赢家圆。
运行：python tools/render_preview.py  输出到 docs/preview_*.png
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 2400

BG = (5, 5, 5)
# 每色三档（主体 / 描边 / 浅色读条弧），与 colors.xml 一致
POINTER_COLORS = [
    ((142, 196, 60), (99, 143, 32), (153, 255, 0)),    # 绿
    ((238, 58, 68), (208, 0, 62), (231, 104, 104)),    # 红
    ((4, 145, 179), (36, 115, 134), (100, 195, 217)),  # 蓝
    ((255, 226, 89), (237, 201, 0), (255, 236, 161)),  # 黄
]
DISC_RATIO = 0.66
RING_MID = 0.90
RING_W = 0.20
HOLE_TO_RATIO = 2.0
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "docs")


def base_radius(n):
    r = min(W, H) * 0.125
    if n > 5:
        r *= (5.0 / n) ** 0.35
    return r


_WHITE = (255, 255, 255)
_HINT = (255, 255, 255, 138)
_ACCENT = (4, 145, 179)
_LINK = (77, 141, 255)
_OK_GREEN = (76, 175, 80)


def draw_switch(draw, x_right, y_center, on=True):
    """SwitchCompat 示意：开=青轨道+右白拇指，关=灰轨道+左白拇指"""
    w, h = 94, 38
    x0, y0 = x_right - w, y_center - h / 2
    draw.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=h / 2,
                           fill=(_ACCENT + (120,)) if on else ((255, 255, 255, 50)))
    r = h / 2 + 3
    cx = x0 + w - r if on else x0 + r
    draw.ellipse([cx - r, y_center - r, cx + r, y_center + r], fill=_WHITE + (255,))


def draw_settings(d):
    """按 SettingsActivity 的布局规格合成设置页（节序：游戏/自检/作弊/序号/反馈/说明/GitHub）"""
    try:
        font = lambda sp: ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", int(sp * 2.75))
        font_b = lambda sp: ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc", int(sp * 2.75))
    except OSError:
        font = lambda sp: ImageFont.load_default()
        font_b = font
    pad = 55
    x_l, x_r = pad, W - pad
    y = 70.0

    def section(y, text):
        d.text((x_l, y), text, font=font_b(14), fill=_HINT)
        return y + 62

    def divider(y):
        d.rectangle([x_l, y, x_r, y + 2], fill=(255, 255, 255, 34))
        return y + 28

    def draw_row(x_left, x_right, y_top, title, sub=None, on=True):
        """开关行：标题 15sp 白 + 可选副标题 12sp 灰 + 右侧开关"""
        d.text((x_left, y_top), title, font=font(15), fill=_WHITE + (255,))
        yy = y_top + 40
        if sub:
            d.text((x_left, yy), sub, font=font(12), fill=_HINT)
            yy += 34
        draw_switch(d, x_right, y_top + (56 if sub else 28), on)
        return yy

    def draw_slider(x_left, x_right, yy, frac, label=None):
        """SeekBar 示意：轨道 + 青色已填充段 + 白色拇指圆"""
        if label:
            d.text((x_left, yy), label, font=font(15), fill=_WHITE + (255,))
            yy += 58
        h = 22
        d.rounded_rectangle([x_left, yy, x_right, yy + h], radius=h / 2,
                            fill=(255, 255, 255, 45))
        fill_w = (x_right - x_left) * frac
        d.rounded_rectangle([x_left, yy, x_left + fill_w, yy + h], radius=h / 2,
                            fill=_ACCENT + (255,))
        r = 30
        cx = x_left + fill_w
        d.ellipse([cx - r, yy + h / 2 - r, cx + r, yy + h / 2 + r], fill=_WHITE + (255,))
        return yy + h

    # 标题
    d.text((x_l, y), "Cheatwazi 设置", font=font_b(20), fill=_ACCENT + (255,))
    y += 100

    # 「游戏」节
    y = section(y, "游戏")
    r = 26
    for i, (txt, sel) in enumerate([("选出赢家", True), ("随机分队", False)]):
        cx = x_l + 60 + i * 470
        cy = y + 24
        ring_c = _ACCENT if sel else (150, 150, 150)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=ring_c + (255,), width=6)
        if sel:
            d.ellipse([cx - r * 0.45, cy - r * 0.45, cx + r * 0.45, cy + r * 0.45],
                      fill=_ACCENT + (255,))
        d.text((cx + r + 24, cy - 22), txt, font=font(15), fill=_WHITE + (255,))
    y += 92
    y = draw_slider(x_l, x_r, y, 0.25, label="赢家数量：2")
    y = divider(y + 40)

    # 「设备自检」节
    y = section(y, "设备自检")
    d.text((x_l, y), "按住下方区域查看压力 / 接触面积读数", font=font(13), fill=_HINT)
    y += 56
    box_h = 220
    d.rounded_rectangle([x_l, y, x_r, y + box_h], radius=33, fill=(255, 255, 255, 20))
    d.text((W / 2, y + box_h / 2 - 24), "压力 0.412　面积 0.351　倾斜 2.3°",
           font=font(14), fill=_WHITE + (255,), anchor="mm")
    y += box_h + 30
    d.text((x_l, y), "压力通道：可用", font=font(13), fill=_OK_GREEN + (255,))
    d.text((x_l + 460, y), "姿态通道：可用", font=font(13), fill=_OK_GREEN + (255,))
    y += 40
    y = divider(y)

    # 「作弊引擎」节
    y = section(y, "作弊引擎")
    y = draw_row(x_l, x_r, y, "启用作弊",
                 sub="开启后，下方三条通道与序号内定才可用", on=True) + 26
    y = draw_row(x_l, x_r, y, "用力按压 → 自己赢",
                 sub="读条期间，用力按压屏幕约半秒", on=True) + 20
    y = draw_row(x_l, x_r, y, "倾斜指向 → 指定的人赢",
                 sub="读条期间，把手机朝他那侧压低并保持半秒", on=True) + 20
    y = draw_row(x_l, x_r, y, "快速微抬 → 自己赢",
                 sub="读条期间，手指快速抬起并立即按回原处", on=True) + 12
    y = draw_slider(x_l, x_r, y, 0.33, label="灵敏度：标准")
    y = divider(y + 40)

    # 「序号内定（一次性）」节
    y = section(y, "序号内定（一次性）")
    d.text((x_l, y), "勾选后，下一局第 N 个放手指的人获胜，该局结束自动失效",
           font=font(12), fill=_HINT)
    y += 60
    chip_w, chip_h, gap = (x_r - x_l - 3 * 22) / 4, 112, 22
    for i in range(8):
        row_i, col_i = i // 4, i % 4
        cx0 = x_l + col_i * (chip_w + gap)
        cy0 = y + row_i * (chip_h + gap)
        sel = i == 1
        d.rounded_rectangle([cx0, cy0, cx0 + chip_w, cy0 + chip_h], radius=27,
                            fill=(_ACCENT + (255,)) if sel else ((31, 31, 31, 255)))
        d.text((cx0 + chip_w / 2, cy0 + chip_h / 2), str(i + 1), font=font(15),
               fill=_WHITE if sel else (154, 166, 166), anchor="mm")
    y += 2 * chip_h + gap + 34
    y = divider(y)

    # 「反馈」节
    y = section(y, "反馈")
    y = draw_row(x_l, x_r, y, "震动反馈", on=True) + 18
    y = draw_row(x_l, x_r, y, "音效", on=True) + 6
    y = divider(y + 4)

    # 「使用说明」节（摘要）
    y = section(y, "使用说明")
    for line in ("· 自己赢 —— 按住屏幕别放，或快速抬指再按回",
                 "· 指定别人 —— 手机朝他那侧压低半秒；序号内定用一次失效"):
        d.text((x_l, y), line, font=font(13), fill=_HINT)
        y += 46
    y += 8

    # GitHub 行 + 版权
    d.ellipse([x_l, y + 2, x_l + 40, y + 42], fill=_LINK + (255,))
    d.ellipse([x_l + 8, y + 14, x_l + 32, y + 38], fill=_WHITE + (255,))
    d.polygon([(x_l + 12, y + 16), (x_l + 16, y + 4), (x_l + 22, y + 14)], fill=_WHITE + (255,))
    d.polygon([(x_l + 28, y + 16), (x_l + 24, y + 4), (x_l + 18, y + 14)], fill=_WHITE + (255,))
    d.text((x_l + 56, y + 6), "github.com/yishi-gh/Cheatwazi", font=font(13),
           fill=_LINK + (255,))
    y += 66
    d.text((x_l, y), "本项目仅供学习交流使用 · 原版 Chwazi 为 Tenda Digital 的作品",
           font=font(11), fill=_HINT)


def draw_ring_arc(draw, x, y, r, color, sweep_deg, mid=RING_MID, width=RING_W):
    """环带弧：显式多边形近似。Canvas 的 stroke 以路径居中，内径 (mid-width/2)R、
    外径 (mid+width/2)R；PIL 的 arc 线宽向内画会吃掉盘环缝隙，故不使用。"""
    if sweep_deg <= 0:
        return
    r_in = r * (mid - width / 2)
    r_out = r * (mid + width / 2)
    start, end = -90.0, -90.0 + 360.0 * min(1.0, sweep_deg)
    steps = max(8, int((end - start) / 3) + 1)
    pts = []
    for i in range(steps + 1):
        a = math.radians(start + (end - start) * i / steps)
        pts.append((x + r_out * math.cos(a), y + r_out * math.sin(a)))
    for i in range(steps, -1, -1):
        a = math.radians(start + (end - start) * i / steps)
        pts.append((x + r_in * math.cos(a), y + r_in * math.sin(a)))
    draw.polygon(pts, fill=color + (255,))


def draw_pointer(draw, x, y, r, color, ring_sweep=360.0, loader=None):
    """原版触点：大色盘 + 细缝 + 粗外环带；loader 为读条弧扫入比例 0..1"""
    main, ring, clear = color
    disc_r = r * DISC_RATIO
    draw.ellipse([x - disc_r, y - disc_r, x + disc_r, y + disc_r], fill=main + (255,))
    if ring_sweep > 0:
        draw_ring_arc(draw, x, y, r, ring, ring_sweep)
    if loader is not None and loader > 0:
        draw_ring_arc(draw, x, y, r, clear, loader)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    r0 = base_radius(3)
    # —— 等待：三根手指已就位（色环扫入完成，呼吸中段示意） ——
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im, "RGBA")
    pts = [(300, 900), (700, 1100), (500, 1500)]
    for i, (x, y) in enumerate(pts):
        draw_pointer(d, x, y, r0, POINTER_COLORS[i])
    im.save(os.path.join(OUT_DIR, "preview_waiting.png"))

    # —— 读条：四指 + 浅色读条弧沿外环扫入 70% ——
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im, "RGBA")
    pts = [(280, 800), (800, 900), (350, 1500), (760, 1600)]
    r4 = base_radius(4)
    for i, (x, y) in enumerate(pts):
        draw_pointer(d, x, y, r4, POINTER_COLORS[i], loader=0.7)
    im.save(os.path.join(OUT_DIR, "preview_readout.png"))

    # —— 结果揭晓：赢家色覆盖全屏，只在赢家位置留孔露出其呼吸的圆 ——
    main0 = POINTER_COLORS[0][0]
    im = Image.new("RGB", (W, H), main0)
    d = ImageDraw.Draw(im, "RGBA")
    wx, wy = 300, 1150
    hole = r0 * HOLE_TO_RATIO
    d.ellipse([wx - hole, wy - hole, wx + hole, wy + hole], fill=BG + (255,))
    draw_pointer(d, wx, wy, r0, POINTER_COLORS[0])
    im.save(os.path.join(OUT_DIR, "preview_result.png"))

    print("done:", os.listdir(OUT_DIR))


if __name__ == "__main__":
    main()
