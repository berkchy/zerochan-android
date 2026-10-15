#!/usr/bin/env python3
"""Generates every Android icon variant from a single square source image.

    python3 design/generate_icons.py /sdcard/zerochan.jpg

Produces
  mipmap-<density>/ic_launcher.png               legacy square launcher icon
  mipmap-<density>/ic_launcher_round.png         legacy round launcher icon
  mipmap-<density>/ic_launcher_foreground.png    adaptive foreground (72/108 safe zone)
  mipmap-<density>/ic_launcher_background.png    adaptive background
  mipmap-<density>/ic_launcher_monochrome.png    themed icon (API 33+)
  drawable-nodpi/ic_splash_logo.png              splash screen icon
  design/play-icon-512.png                       Play Store listing icon
  design/play-feature-graphic.png                Play Store feature graphic
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFilter

# launcher icon: 48dp, adaptive layers: 108dp, splash: 288dp
DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
BG_TOP = (32, 24, 36)
BG_BOTTOM = (12, 10, 16)

SS = 4  # supersampling factor for smooth masks


def load(path: str) -> Image.Image:
    image = Image.open(path).convert("RGB")
    return square_crop(image)


def square_crop(image: Image.Image) -> Image.Image:
    side = min(image.size)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    return image.crop((left, top, left + side, top + side))


def masked(image: Image.Image, size: int, shape: str, radius_ratio: float = 0.22) -> Image.Image:
    """Renders `image` at `size` px with a circular or rounded-rect alpha mask."""
    scaled = image.resize((size, size), Image.LANCZOS)
    big = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(big)

    if shape == "circle":
        draw.ellipse((0, 0, size * SS - 1, size * SS - 1), fill=(255, 255, 255, 255))
    else:
        radius = int(size * SS * radius_ratio)
        draw.rounded_rectangle((0, 0, size * SS - 1, size * SS - 1), radius=radius, fill=(255, 255, 255, 255))

    alpha = big.resize((size, size), Image.LANCZOS).getchannel("A")
    scaled = scaled.convert("RGBA")
    scaled.putalpha(alpha)
    return scaled


# 108dp adaptive canvas: 72dp is the guaranteed visible circle, but launchers also apply a
# parallax zoom, so filling 80dp reads as a proper full bleed icon while the circular mask
# still lands inside the artwork.
FOREGROUND_RATIO = 80 / 108

# luminance cut-out curve for the themed icon
CUTOUT = lambda v: max(0, min(255, int((150 - v) * 2.4)))  # noqa: E731


def adaptive_foreground(image: Image.Image, canvas: int) -> Image.Image:
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    inner = int(round(canvas * FOREGROUND_RATIO))
    out.paste(masked(image, inner, "circle"), ((canvas - inner) // 2, (canvas - inner) // 2))
    return out


def adaptive_background(canvas: int) -> Image.Image:
    """Subtle vertical gradient; the artwork is carried by the foreground layer."""
    out = Image.new("RGBA", (canvas, canvas))
    draw = ImageDraw.Draw(out)
    for y in range(canvas):
        t = y / max(canvas - 1, 1)
        draw.line(
            (
                (0, y),
                (
                    canvas,
                    y,
                ),
            ),
            fill=tuple(round(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)) + (255,),
        )
    return out


def monochrome(image: Image.Image, canvas: int) -> Image.Image:
    """
    Themed icon (API 33+). The launcher flattens this layer to a single colour, so only the
    alpha channel matters: a luminance cut-out keeps the face, hair and hat readable while
    the bright hair and skin drop out and let the theme colour through.
    """
    inner = int(round(canvas * FOREGROUND_RATIO))
    alpha = image.resize((inner * SS, inner * SS), Image.LANCZOS).convert("L").point(CUTOUT)
    alpha = alpha.filter(ImageFilter.GaussianBlur(inner * SS * 0.003))

    silhouette = Image.new("RGBA", (inner * SS, inner * SS), (255, 255, 255, 0))
    silhouette.putalpha(alpha)

    circle = Image.new("L", (inner * SS, inner * SS), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, inner * SS - 1, inner * SS - 1), fill=255)
    silhouette.putalpha(
        Image.composite(silhouette.getchannel("A"), Image.new("L", silhouette.size, 0), circle)
    )

    silhouette = silhouette.resize((inner, inner), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(silhouette, ((canvas - inner) // 2, (canvas - inner) // 2))
    return out


# WebP keeps photographic icons ~5x smaller than png while still carrying the alpha channel
# (supported since API 18; minSdk here is 24).
EXTENSION = ".webp"
QUALITY = 88


def write(image: Image.Image, *parts: str) -> str:
    *folders, name = parts
    path = os.path.join(RES, *folders, os.path.splitext(name)[0] + EXTENSION)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "WEBP", quality=QUALITY, method=6)
    print(
        f"  {os.path.relpath(path, os.path.join(RES, '..'))}  "
        f"{image.size[0]}x{image.size[1]}  {os.path.getsize(path) // 1024} kB"
    )
    return path


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else "/sdcard/zerochan.jpg"
    if not os.path.exists(source):
        print(f"source not found: {source}")
        return 1

    image = load(source)
    print(f"source {source} -> {image.size[0]}x{image.size[1]}")

    for bucket, scale in DENSITIES.items():
        folder = f"mipmap-{bucket}"
        print(f"{folder}")
        launcher = int(round(48 * scale))
        canvas = int(round(108 * scale))
        write(masked(image, launcher, "rect"), folder, "ic_launcher.png")
        write(masked(image, launcher, "circle"), folder, "ic_launcher_round.png")
        write(adaptive_foreground(image, canvas), folder, "ic_launcher_foreground.png")
        write(adaptive_background(canvas), folder, "ic_launcher_background.png")
        write(monochrome(image, canvas), folder, "ic_launcher_monochrome.png")

    print("splash")
    write(masked(image, 640, "circle"), "drawable-nodpi", "ic_splash_logo.png")

    print("play store")
    design = os.path.join(os.path.dirname(os.path.abspath(__file__)))
    os.makedirs(design, exist_ok=True)
    masked(image, 512, "rect", radius_ratio=0.0).save(
        os.path.join(design, "play-icon-512.png"), optimize=True
    )

    graphic = Image.new("RGB", (1024, 500), BG_BOTTOM)
    art = masked(image, 440, "circle")
    graphic.paste(art, (72, 30), art)
    draw = ImageDraw.Draw(graphic)
    draw.text((560, 210), "Zerochan", fill=(255, 255, 255))
    draw.text((562, 250), "for Android", fill=(230, 0, 126))
    graphic.save(os.path.join(design, "play-feature-graphic.png"))
    print(f"  design/play-feature-graphic.png  1024x500")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())