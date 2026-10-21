# -*- coding: utf-8 -*-
"""生成「优学」App 图标：一顶学士帽（博士帽），自适应前景 + 各密度传统图标 PNG。

画面只有三块：帽板（俯视菱形）、帽身（压在帽板下沿）、侧边垂下的流苏。
**不放任何文字**——48dp 的图标里一个字只剩 7px 高，除了糊成一团什么也传达不了。
"""
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

# 按脚本位置定位资源目录，clone 到任何路径都能直接跑
RES = str(Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res")

# 品牌色
C_BG_TOP = (79, 124, 255)
C_BG_BOTTOM = (58, 92, 214)
C_ACCENT = (255, 255, 255)      # 帽板与流苏
C_BAND = (176, 202, 255)        # 帽身：比帽板暗一档，否则两个面糊成一整块

# 超采样倍数：先在 8 倍画布上画再缩回目标尺寸。
# PIL 的 polygon / line 在目标尺寸上直接画是硬锯齿，48px 的 mipmap-mdpi 尤其明显
SS = 8

# 传统图标铺满整块方形，可以用满一点；自适应图标必须待在中心 66% 安全区里。
# 帽子本身横宽竖扁，不放大一点在 48px 上会显得很小
LEGACY_SCALE = 1.12
ADAPTIVE_SCALE = 0.70


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
    """在透明画布上绘制学士帽。

    `canvas_size` 是超采样后的尺寸；所有尺寸都按 `unit` 的比例算，
    所以同一份代码既画 48px 的传统图标，也画 432px 的自适应前景。
    """
    S = canvas_size
    # 透明底取「白 + alpha=0」而不是「黑 + alpha=0」：
    # 降采样会把 RGB 和 alpha 一起平均，黑底会在边缘拖出一圈灰边
    layer = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    d = ImageDraw.Draw(layer)

    cx = S / 2
    cy = S / 2 - S * content_scale * 0.022   # 帽身压得比帽板深，整体上移一点才视觉居中
    unit = S * content_scale
    stroke = max(1, int(unit * 0.022))

    # ---- 帽身：先画，上沿会被帽板压住 ----
    band_w = unit * 0.295
    d.rounded_rectangle(
        [cx - band_w / 2, cy - unit * 0.012, cx + band_w / 2, cy + unit * 0.150],
        radius=unit * 0.050,
        fill=C_BAND,
    )

    # ---- 帽板：俯视的菱形，横长竖短（竖太深就成了帐篷）----
    d.polygon(
        [
            (cx - unit * 0.310, cy - unit * 0.005),
            (cx, cy - unit * 0.105),
            (cx + unit * 0.310, cy - unit * 0.005),
            (cx, cy + unit * 0.062),
        ],
        fill=C_ACCENT,
    )

    # ---- 流苏：从帽板右角垂下，一点点向外的弧度 ----
    p0 = (cx + unit * 0.305, cy - unit * 0.005)
    p1 = (cx + unit * 0.345, cy + unit * 0.055)
    p2 = (cx + unit * 0.322, cy + unit * 0.135)
    pts = []
    for i in range(25):
        t = i / 24
        pts.append(
            (
                (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t ** 2 * p2[0],
                (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t ** 2 * p2[1],
            )
        )
    d.line(pts, fill=C_ACCENT, width=stroke, joint="curve")

    # 流苏末端的小坠子
    kr = unit * 0.030
    d.ellipse([p2[0] - kr, p2[1] - kr, p2[0] + kr, p2[1] + kr], fill=C_ACCENT)

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
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.06))
    base = Image.alpha_composite(base, glow)

    content = draw_content(size * SS, content_scale=LEGACY_SCALE).resize((size, size), Image.LANCZOS)
    out = Image.alpha_composite(base, content)

    if rounded:
        out.putalpha(rounded_mask((size, size), int(size * 0.22)))
    return out


def make_foreground(size):
    """自适应图标前景：内容限制在中心 66% 安全区内。"""
    inner = draw_content(size * SS, content_scale=ADAPTIVE_SCALE)
    return inner.resize((size, size), Image.LANCZOS)


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
