# -*- coding: utf-8 -*-
"""生成「优学」App 图标：自适应图标前景 + 各密度传统图标 PNG。"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

RES = r"F:\dcloud\youxue\youxue-app\app\src\main\res"

# 品牌色
C_BG_TOP = (79, 124, 255)
C_BG_BOTTOM = (58, 92, 214)
C_ACCENT = (255, 255, 255)

FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_BOLD_ALT = r"C:\Windows\Fonts\msyh.ttc"


def load_font(size):
    for path in (FONT_BOLD, FONT_BOLD_ALT):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def vertical_gradient(size, top, bottom):
    """线性竖直渐变。"""
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = (
            round(top[0] + (bottom[0] - top[0]) * t),
            round(top[1] + (bottom[1] - top[1]) * t),
            round(top[2] + (bottom[2] - top[2]) * t),
        )
    return img.resize((w, h), Image.BILINEAR)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def draw_content(canvas_size, content_scale=1.0):
    """在透明画布上绘制图标内容（书本 + 声波纹 + 文字）。"""
    S = canvas_size
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    cx, cy = S / 2, S / 2
    unit = S * content_scale

    # ---- 打开的书本 ----
    bw = unit * 0.52          # 书宽
    bh = unit * 0.30          # 书高
    left, top = cx - bw / 2, cy - bh / 2 + unit * 0.055
    right, bottom = cx + bw / 2, top + bh
    r = unit * 0.035

    # 左页 / 右页（中间留书脊缝隙）
    gap = unit * 0.016
    d.rounded_rectangle(
        [left, top, cx - gap / 2, bottom], radius=r, fill=C_ACCENT
    )
    d.rounded_rectangle(
        [cx + gap / 2, top, right, bottom], radius=r, fill=C_ACCENT
    )
    # 书脊
    d.line([cx, top + unit * 0.012, cx, bottom - unit * 0.012], fill=C_BG_BOTTOM, width=max(2, int(unit * 0.014)))

    # 书页横线（用背景色描出来，形成"文字行"的感觉）
    line_w = max(2, int(unit * 0.016))
    for i in range(3):
        y = top + bh * (0.28 + i * 0.22)
        d.line([left + unit * 0.045, y, cx - gap / 2 - unit * 0.035, y], fill=C_BG_BOTTOM, width=line_w)
        d.line([cx + gap / 2 + unit * 0.035, y, right - unit * 0.045, y], fill=C_BG_BOTTOM, width=line_w)

    # ---- 声波弧线（书本上方）----
    arcs_cx, arcs_cy = cx, top - unit * 0.035
    for i, (rr, alpha) in enumerate(((0.075, 235), (0.115, 175), (0.155, 115))):
        rad = unit * rr
        box = [arcs_cx - rad, arcs_cy - rad, arcs_cx + rad, arcs_cy + rad]
        d.arc(box, start=200, end=340, fill=C_ACCENT + (alpha,), width=max(2, int(unit * 0.018)))

    # ---- 底部「学」字 ----
    fs = int(unit * 0.155)
    font = load_font(fs)
    txt = "学"
    bbox = d.textbbox((0, 0), txt, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text((cx - tw / 2 - bbox[0], bottom + unit * 0.045 - bbox[1]), txt, font=font, fill=C_ACCENT)

    return layer


def make_full_icon(size, rounded=False):
    """完整图标：渐变底 + 内容。rounded=True 时圆角。"""
    base = vertical_gradient((size, size), C_BG_TOP, C_BG_BOTTOM).convert("RGBA")

    # 柔和的深色装饰光斑
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse(
        [-size * 0.18, -size * 0.32, size * 0.62, size * 0.48],
        fill=(255, 255, 255, 26),
    )
    glow = glow.filter(__import__("PIL.ImageFilter", fromlist=["ImageFilter"]).GaussianBlur(size * 0.06))
    base = Image.alpha_composite(base, glow)

    content = draw_content(size)
    out = Image.alpha_composite(base, content)

    if rounded:
        out.putalpha(rounded_mask((size, size), int(size * 0.22)))
    return out


def make_foreground(size):
    """自适应图标前景：内容限制在中心 66% 安全区内。"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = draw_content(size, content_scale=0.66)
    layer = Image.alpha_composite(layer, inner)
    return layer


def main():
    # 传统图标各密度尺寸
    legacy = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px in legacy.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        icon = make_full_icon(px, rounded=True)
        icon.save(os.path.join(d, "ic_launcher.png"))
        # 圆形图标
        circ = make_full_icon(px)
        m = Image.new("L", (px, px), 0)
        ImageDraw.Draw(m).ellipse([0, 0, px - 1, px - 1], fill=255)
        circ.putalpha(m)
        circ.save(os.path.join(d, "ic_launcher_round.png"))
        print("legacy", folder, px)

    # 自适应图标前景（108dp 基准，各密度）
    adaptive = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }
    for folder, px in adaptive.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        make_foreground(px).save(os.path.join(d, "ic_launcher_foreground.png"))
        print("foreground", folder, px)

    # 自适应图标背景：纯渐变（无内容）
    for folder, px in adaptive.items():
        d = os.path.join(RES, folder)
        vertical_gradient((px, px), C_BG_TOP, C_BG_BOTTOM).convert("RGBA").save(
            os.path.join(d, "ic_launcher_background.png")
        )

    print("done")


if __name__ == "__main__":
    main()
