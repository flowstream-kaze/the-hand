#!/usr/bin/env python3
"""Regenerate The Hand launcher icons from the 1024px brand master.

Usage:
    python3 branding/generate-icons.py <master-1024.png>

Writes the adaptive foreground/monochrome layers and every legacy mipmap
density into app/src/main/res. The web/PWA assets live in the separate
flowstream-kaze/the-hand-windows repository and are derived from the same
master with the ffmpeg commands in that repository's README.
"""
import math
import subprocess
import sys
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'app' / 'src' / 'main' / 'res'
W = H = 1024
BG = (0, 65, 255)  # matches @color/ic_launcher_background


def run(args, stdin=None):
    return subprocess.run(args, input=stdin, check=True,
                          capture_output=True).stdout


def encode(raw, size, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgba',
         '-s', f'{size}x{size}', '-i', '-', '-frames:v', '1', str(path)], raw)


def scale(source, size, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    run(['ffmpeg', '-v', 'error', '-y', '-i', str(source), '-vf',
         f'scale={size}:{size}:flags=lanczos,format=rgba',
         '-frames:v', '1', str(output)])


def rounded(source, size, output, circle=False, inset=0.025):
    """Composite the full-bleed master into a squircle or circle with alpha."""
    raw = bytearray(run(['ffmpeg', '-v', 'error', '-i', str(source), '-vf',
                         f'scale={size}:{size}:flags=lanczos',
                         '-f', 'rawvideo', '-pix_fmt', 'rgba', '-frames:v', '1', '-']))
    margin = size * inset
    radius = (size - 2 * margin) / 2 if circle else size * 0.22
    half = (size - 1) / 2
    box = half - margin
    samples = 4
    for y in range(size):
        for x in range(size):
            hit = 0
            for sy in range(samples):
                for sx in range(samples):
                    px = x + (sx + .5) / samples - half - .5
                    py = y + (sy + .5) / samples - half - .5
                    if circle:
                        inside = px * px + py * py <= box * box
                    else:
                        qx = abs(px) - (box - radius)
                        qy = abs(py) - (box - radius)
                        inside = math.hypot(max(qx, 0), max(qy, 0)) <= radius
                    hit += int(inside)
            i = (y * size + x) * 4 + 3
            raw[i] = raw[i] * hit // (samples * samples)
    encode(raw, size, output)


def main(master):
    master = Path(master).resolve()
    flat = bytearray()
    src = bytearray(run(['ffmpeg', '-v', 'error', '-i', str(master),
                         '-f', 'rawvideo', '-pix_fmt', 'rgba', '-frames:v', '1', '-']))
    for i in range(0, len(src), 4):
        a = src[i + 3] / 255
        flat += bytes((round(src[i] * a + BG[0] * (1 - a)),
                       round(src[i + 1] * a + BG[1] * (1 - a)),
                       round(src[i + 2] * a + BG[2] * (1 - a)), 255))
    opaque = Path('/tmp/the-hand-master-opaque.png')
    encode(flat, W, opaque)

    # Adaptive layers: the launcher supplies the background colour, so the
    # foreground carries the artwork and the monochrome layer only the glyph.
    scale(opaque, 432, RES / 'drawable-xxxhdpi' / 'ic_launcher_foreground.png')

    white = bytearray(min(flat[i], flat[i + 1], flat[i + 2]) >= 205
                      for i in range(0, len(flat), 4))
    seen, keep = bytearray(W * H), bytearray(W * H)
    for start in range(W * H):
        if not white[start] or seen[start]:
            continue
        queue, component, seen[start] = deque([start]), [], 1
        while queue:
            p = queue.popleft()
            component.append(p)
            x = p % W
            for n in (p - 1 if x else -1, p + 1 if x + 1 < W else -1,
                      p - W if p >= W else -1, p + W if p + W < W * H else -1):
                if n >= 0 and white[n] and not seen[n]:
                    seen[n] = 1
                    queue.append(n)
        # Keep the fist and arrow; drop the three small browser dots.
        if len(component) >= 2000:
            for p in component:
                keep[p] = 1
    region = bytearray(keep)
    for _ in range(3):  # widen so antialiased glyph edges are included
        grown = bytearray(region)
        for p in range(W * H):
            if not region[p]:
                continue
            x = p % W
            if x:
                grown[p - 1] = 1
            if x + 1 < W:
                grown[p + 1] = 1
            if p >= W:
                grown[p - W] = 1
            if p + W < W * H:
                grown[p + W] = 1
        region = grown
    mono = bytearray()
    for p in range(W * H):
        level = min(flat[p * 4:p * 4 + 3])
        alpha = max(0, min(255, round((level - 155) * 255 / 70))) if region[p] else 0
        mono += bytes((255, 255, 255, alpha))
    mono_path = Path('/tmp/the-hand-monochrome.png')
    encode(mono, W, mono_path)
    scale(mono_path, 432, RES / 'drawable-xxxhdpi' / 'ic_launcher_monochrome.png')

    # Legacy launchers (pre-API 26) need pre-masked bitmaps.
    for density, size in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                         ('xxhdpi', 144), ('xxxhdpi', 192)):
        base = RES / f'mipmap-{density}'
        rounded(opaque, size, base / 'ic_launcher.png')
        rounded(opaque, size, base / 'ic_launcher_round.png', circle=True, inset=0.015)
    print(f'Regenerated launcher icons in {RES} from {master}')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
