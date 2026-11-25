#!/usr/bin/env python3
"""Generate Focus Ring visual identity assets (SVG, PNG, ICO) from one geometry spec."""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Canonical Focus Ring geometry on a 64x64 grid
TILE_RX = 15.0
BORDER_W = 1.4
BG_COLOR = (0, 0, 0)
BORDER_COLOR = (38, 38, 38)      # #262626
TRACK_COLOR = (54, 54, 60)       # #36363c
FG_COLOR = (250, 250, 250)       # #fafafa

ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <rect x="0.7" y="0.7" width="62.6" height="62.6" rx="14.3" fill="#000000" stroke="#262626" stroke-width="1.4"/>
  <circle cx="32" cy="32" r="16.5" fill="none" stroke="#36363c" stroke-width="5.4"/>
  <path d="M32 15.5A16.5 16.5 0 1 1 15.5 32" fill="none" stroke="#fafafa" stroke-width="5.4" stroke-linecap="round"/>
  <circle cx="32" cy="32" r="4.8" fill="#fafafa"/>
</svg>
"""

MARK_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">
  <circle cx="24" cy="24" r="16.5" fill="none" stroke="#36363c" stroke-width="5.4"/>
  <path d="M24 7.5A16.5 16.5 0 1 1 7.5 24" fill="none" stroke="#fafafa" stroke-width="5.4" stroke-linecap="round"/>
  <circle cx="24" cy="24" r="4.8" fill="#fafafa"/>
</svg>
"""


def sd_rounded_box(px: float, py: float, bx: float, by: float, r: float) -> float:
    qx = abs(px) - (bx - r)
    qy = abs(py) - (by - r)
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def render_png(size: int, supersample: int = 8) -> bytes:
    # Slightly bolder optical tuning at 16px so tray/toolbar icons stay crisp
    if size <= 16:
        ring_r, stroke_w, dot_r, border_w = 17.0, 6.2, 5.2, 2.0
    elif size <= 32:
        ring_r, stroke_w, dot_r, border_w = 16.8, 5.7, 5.0, 1.6
    else:
        ring_r, stroke_w, dot_r, border_w = 16.5, 5.4, 4.8, BORDER_W

    half_w = stroke_w * 0.5
    inv_ss = 1.0 / supersample
    scale = 64.0 / size
    total_samples = supersample * supersample

    raw = bytearray()
    for y in range(size):
        raw.append(0)  # PNG filter type 0 (None)
        for x in range(size):
            ar = ag = ab = aa = 0.0
            for sy in range(supersample):
                cy = (y + (sy + 0.5) * inv_ss) * scale
                dy = cy - 32.0
                for sx in range(supersample):
                    cx = (x + (sx + 0.5) * inv_ss) * scale
                    dx = cx - 32.0
                    d_tile = sd_rounded_box(dx, dy, 32.0, 32.0, TILE_RX)
                    if d_tile > 0.0:
                        continue
                    aa += 1.0
                    dist = math.hypot(dx, dy)
                    in_dot = dist <= dot_r
                    in_ring = abs(dist - ring_r) <= half_w
                    in_arc = (
                        (in_ring and not (dx < 0.0 and dy < 0.0))
                        or math.hypot(dx, dy + ring_r) <= half_w
                        or math.hypot(dx + ring_r, dy) <= half_w
                    )
                    if in_dot or in_arc:
                        c = FG_COLOR
                    elif in_ring:
                        c = TRACK_COLOR
                    elif d_tile > -border_w:
                        c = BORDER_COLOR
                    else:
                        c = BG_COLOR
                    ar += c[0]
                    ag += c[1]
                    ab += c[2]
            if aa == 0.0:
                raw.extend((0, 0, 0, 0))
            else:
                alpha = round((aa / total_samples) * 255.0)
                raw.extend((
                    round(ar / aa),
                    round(ag / aa),
                    round(ab / aa),
                    alpha,
                ))

    def chunk(tag: bytes, payload: bytes) -> bytes:
        crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), level=9)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", idat)
        + chunk(b"IEND", b"")
    )


def pack_ico(frames: list[tuple[int, bytes]]) -> bytes:
    header = struct.pack("<HHH", 0, 1, len(frames))
    offset = 6 + 16 * len(frames)
    entries = bytearray()
    blobs = bytearray()
    for size, png in frames:
        dim = 0 if size >= 256 else size
        entries.extend(struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(png), offset))
        blobs.extend(png)
        offset += len(png)
    return header + bytes(entries) + bytes(blobs)


def write_bytes(rel: str, data: bytes) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print(f"wrote {rel} ({len(data)} bytes)")


def main() -> None:
    write_bytes("assets/icon.svg", ICON_SVG.encode("utf-8"))
    write_bytes("assets/mark.svg", MARK_SVG.encode("utf-8"))
    write_bytes("ui/public/favicon.svg", ICON_SVG.encode("utf-8"))

    png_16 = render_png(16, supersample=12)
    png_32 = render_png(32, supersample=10)
    png_48 = render_png(48, supersample=8)
    png_128 = render_png(128, supersample=6)
    png_256 = render_png(256, supersample=6)

    write_bytes("app/icons/icon.png", png_256)
    write_bytes(
        "app/icons/icon.ico",
        pack_ico([(16, png_16), (32, png_32), (48, png_48), (256, png_256)]),
    )

    write_bytes("extension/icons/icon-16.png", png_16)
    write_bytes("extension/icons/icon-32.png", png_32)
    write_bytes("extension/icons/icon-48.png", png_48)
    write_bytes("extension/icons/icon-128.png", png_128)


if __name__ == "__main__":
    main()
