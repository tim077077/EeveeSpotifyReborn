"""Pillow slide renderer: background image(s) + overlay + TikTok-style text."""

from __future__ import annotations

import hashlib
import io
import re
from functools import lru_cache
from pathlib import Path

import httpx
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

from .config import FONTS_DIR, settings
from .schemas import Slide, SlideshowSpec, TextItem

CANVAS = {"4:5": (1080, 1350), "9:16": (1080, 1920), "1:1": (1080, 1080), "16:9": (1920, 1080)}

# (file, variable-font weight or None)
FONTS: dict[str, tuple[str, int | None]] = {
    "tiktok": ("Montserrat[wght].ttf", 800),
    "bebas": ("BebasNeue-Regular.ttf", None),
    "anton": ("Anton-Regular.ttf", None),
    "archivo": ("ArchivoBlack-Regular.ttf", None),
    "cormorant": ("CormorantGaramond[wght].ttf", 500),
    "cormorant_italic": ("CormorantGaramond-Italic[wght].ttf", 500),
    "playfair_italic": ("PlayfairDisplay-Italic[wght].ttf", 500),
}
CJK_FONT_CANDIDATES = [
    Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
    Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"),
    Path("/System/Library/Fonts/PingFang.ttc"),
]
# Pixel sizes at 1080px canvas width.
SIZES = {"extra_extra_small": 34, "extra_small": 42, "small": 52, "medium": 64, "large": 80, "extra_large": 100}
# Condensed display faces read small at the same pixel size.
FONT_SCALE = {"bebas": 1.3, "anton": 1.1, "cormorant": 1.15, "cormorant_italic": 1.15, "playfair_italic": 1.05}
COLORS = {
    "white": (255, 255, 255), "black": (0, 0, 0), "yellow": (255, 221, 0), "red": (238, 29, 82),
    "pink": (255, 105, 180), "orange": (255, 140, 0), "green": (37, 211, 102), "blue": (32, 120, 255),
    "purple": (140, 82, 255),
}
LIGHT_COLORS = {"white", "yellow", "green", "pink", "orange"}
CJK_RE = re.compile(r"[぀-ヿ㐀-鿿가-힯฀-๿]")


@lru_cache(maxsize=64)
def _font(name: str, size: int, cjk: bool) -> ImageFont.FreeTypeFont:
    if cjk:
        for candidate in CJK_FONT_CANDIDATES:
            if candidate.is_file():
                return ImageFont.truetype(str(candidate), size)
    file, weight = FONTS.get(name, FONTS["tiktok"])
    font = ImageFont.truetype(str(FONTS_DIR / file), size)
    if weight is not None:
        try:
            font.set_variation_by_axes([weight])
        except (OSError, ValueError):
            pass
    return font


def _px(item: TextItem, canvas_w: int) -> int:
    size = item.font_size
    if isinstance(size, str) and size.endswith("px") and size[:-2].isdigit():
        base = int(size[:-2])
    else:
        base = SIZES.get(str(size), SIZES["medium"])
    return max(12, round(base * FONT_SCALE.get(item.font, 1.0) * canvas_w / 1080))


def load_image(ref: str) -> Image.Image:
    """Load a media-relative path or an http(s) URL (downloads are cached on disk)."""
    if ref.startswith(("http://", "https://")):
        cache = settings.media_dir / "cache" / (hashlib.sha256(ref.encode()).hexdigest()[:24] + ".img")
        if not cache.is_file():
            cache.parent.mkdir(parents=True, exist_ok=True)
            resp = httpx.get(ref, timeout=30, follow_redirects=True)
            resp.raise_for_status()
            cache.write_bytes(resp.content)
        data = cache.read_bytes()
    else:
        path = (settings.media_dir / ref).resolve()
        if settings.media_dir.resolve() not in path.parents:
            raise ValueError(f"image path escapes media dir: {ref}")
        data = path.read_bytes()
    img = Image.open(io.BytesIO(data))
    return ImageOps.exif_transpose(img).convert("RGB")


def _cover(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    return ImageOps.fit(img, size, Image.Resampling.LANCZOS, centering=(0.5, 0.45))


def _contain_on_blur(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    bg = _cover(img, size).filter(ImageFilter.GaussianBlur(40))
    fg = ImageOps.contain(img, size, Image.Resampling.LANCZOS)
    bg.paste(fg, ((size[0] - fg.width) // 2, (size[1] - fg.height) // 2))
    return bg


def _gradient(size: tuple[int, int]) -> Image.Image:
    w, h = size
    top, bottom = (36, 36, 48), (10, 10, 14)
    grad = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        grad.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return grad.resize(size)


GRID_CELLS = {
    "single": [(0, 0, 1, 1)],
    "1:2": [(0, 0, 1, 0.5), (0, 0.5, 1, 1)],
    "1:3": [(0, 0, 1, 1 / 3), (0, 1 / 3, 1, 2 / 3), (0, 2 / 3, 1, 1)],
    "2:1": [(0, 0, 0.5, 1), (0.5, 0, 1, 1)],
    "2:2": [(0, 0, 0.5, 0.5), (0.5, 0, 1, 0.5), (0, 0.5, 0.5, 1), (0.5, 0.5, 1, 1)],
}


def _background(slide: Slide, size: tuple[int, int], keep_original: bool) -> Image.Image:
    refs = slide.images or ([slide.image] if slide.image else [])
    if not refs:
        return _gradient(size)
    cells = GRID_CELLS.get(slide.image_layout, GRID_CELLS["single"])
    if len(refs) == 1 or len(cells) == 1:
        img = load_image(refs[0])
        return _contain_on_blur(img, size) if keep_original else _cover(img, size)
    canvas = Image.new("RGB", size)
    w, h = size
    for i, (x0, y0, x1, y1) in enumerate(cells):
        box = (round(x0 * w), round(y0 * h), round(x1 * w), round(y1 * h))
        tile = _cover(load_image(refs[i % len(refs)]), (box[2] - box[0], box[3] - box[1]))
        canvas.paste(tile, box[:2])
    return canvas


def _wrap(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, max_w: int) -> list[str]:
    lines: list[str] = []
    for para in text.split("\n"):
        # CJK text has no spaces; wrap per character there.
        if CJK_RE.search(para) and " " not in para:
            tokens, joiner = list(para), ""
        else:
            tokens, joiner = para.split(" "), " "
        current = ""
        for tok in tokens:
            trial = f"{current}{joiner}{tok}" if current else tok
            if draw.textlength(trial, font=font) <= max_w or not current:
                current = trial
            else:
                lines.append(current)
                current = tok
        lines.append(current)
    return lines


def _text_block(item: TextItem, canvas_w: int) -> Image.Image:
    """Render one text item onto a transparent tile sized to its content."""
    size = _px(item, canvas_w)
    cjk = bool(CJK_RE.search(item.text))
    font = _font(item.font, size, cjk)
    width_frac = {"full": 0.94, "100%": 0.88, "80%": 0.76, "50%": 0.5}[item.text_width]
    max_w = round(canvas_w * width_frac)
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    lines = _wrap(probe, item.text.strip(), font, max_w)

    ascent, descent = font.getmetrics()
    line_h = ascent + descent
    gap = round(size * 0.12)
    stroke = max(2, round(size * 0.09)) if item.text_style == "outline" else 0
    pad_x, pad_y = (round(size * 0.35), round(size * 0.14)) if "background" in item.text_style else (0, 0)
    widths = [round(probe.textlength(line, font=font)) for line in lines]
    block_w = max(widths, default=0) + 2 * (pad_x + stroke)
    block_h = len(lines) * (line_h + 2 * pad_y) + (len(lines) - 1) * gap + 2 * stroke

    tile = Image.new("RGBA", (max(1, block_w), max(1, block_h)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(tile)
    color = COLORS[item.text_color]
    if item.text_style == "background":
        box_fill = (*color, 255)
        fill = COLORS["black"] if item.text_color in LIGHT_COLORS else COLORS["white"]
    elif item.text_style == "light_background":
        box_fill = (255, 255, 255, 225)
        fill = COLORS["black"] if item.text_color == "white" else color
    else:
        box_fill = None
        fill = color
    stroke_fill = COLORS["white"] if item.text_color == "black" else COLORS["black"]

    y = stroke
    for line, lw in zip(lines, widths):
        if item.text_align == "left":
            x = stroke + pad_x
        elif item.text_align == "right":
            x = block_w - stroke - pad_x - lw
        else:
            x = (block_w - lw) // 2
        if box_fill:
            draw.rounded_rectangle(
                (x - pad_x, y, x + lw + pad_x, y + line_h + 2 * pad_y), radius=round(size * 0.22), fill=box_fill
            )
        draw.text((x, y + pad_y), line, font=font, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill)
        y += line_h + 2 * pad_y + gap
    return tile


def render_slide(slide: Slide, spec: SlideshowSpec) -> Image.Image:
    size = CANVAS[spec.aspect_ratio]
    w, h = size
    img = _background(slide, size, spec.keep_original_aspect_ratio).convert("RGBA")

    overlay_on = {"hook": spec.overlay_hook, "cta": spec.overlay_cta}.get(slide.role, spec.overlay_body)
    if overlay_on and spec.background_opacity:
        img.alpha_composite(Image.new("RGBA", size, (0, 0, 0, round(255 * spec.background_opacity / 100))))

    blocks = [_text_block(item, w) for item in slide.text_items if item.text.strip()]
    if blocks:
        gap = round(h * 0.025)
        total = sum(b.height for b in blocks) + gap * (len(blocks) - 1)
        position = slide.text_position or spec.text_position
        margin = round(h * 0.12)
        if position == "top":
            y = margin
        elif position == "bottom":
            y = h - margin - total
        else:
            y = (h - total) // 2
        y = max(round(h * 0.04), y)
        for block, item in zip(blocks, (i for i in slide.text_items if i.text.strip())):
            side = round(w * 0.06)
            x = {"left": side, "right": w - side - block.width}.get(item.text_align, (w - block.width) // 2)
            img.alpha_composite(block, (max(0, x), y))
            y += block.height + gap
    return img.convert("RGB")


def render_slideshow(spec: SlideshowSpec, out_dir: Path) -> list[str]:
    """Render every slide to JPEG; returns media-relative paths."""
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for i, slide in enumerate(spec.slides):
        target = out_dir / f"slide_{i + 1:02d}.jpg"
        render_slide(slide, spec).save(target, "JPEG", quality=92, optimize=True)
        paths.append(str(target.relative_to(settings.media_dir)))
    return paths
