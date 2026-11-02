#!/usr/bin/env python3
"""Renders the screen saver's Aerials: original nature scenes as perfectly looping H.264 clips.

Everything is painted from noise, so there's no footage to license. Noise can be made to wrap
around, so a scene that scrolls by exactly one wrap per clip ends precisely where it began: real
travel with no cross-fade anywhere. Needs numpy and PyAV, plus Pillow for --still.

    tools/render_aerials.py --still out_dir        # one frame of each scene, to judge the look
    tools/render_aerials.py --video out_dir [name] # the clips
"""
import math
import multiprocessing
import os
import sys

import numpy as np

W, H, FPS, SECONDS = 1280, 720, 24, 32
FRAMES = FPS * SECONDS

_rng = np.random.default_rng(7)
_perm = np.tile(_rng.permutation(256), 2)
_values = _rng.random(256).astype(np.float32)

_y, _x = np.mgrid[0:H, 0:W].astype(np.float32)
X, Y = _x / H, _y / H  # X runs 0..1.78, Y runs 0..1
GRAIN = _rng.random((H, W), dtype=np.float32)


def noise(x, y, wx=0, wy=0):
    """Value noise, repeating every [wx] and [wy] units when they're set."""
    xf = np.floor(x)
    yf = np.floor(y)
    xi = xf.astype(np.int32)
    yi = yf.astype(np.int32)
    fx = x - xf
    fy = y - yf
    u = fx * fx * (3 - 2 * fx)
    v = fy * fy * (3 - 2 * fy)
    x0, x1 = xi, xi + 1
    y0, y1 = yi, yi + 1
    if wx:
        x0, x1 = x0 % wx, x1 % wx
    if wy:
        y0, y1 = y0 % wy, y1 % wy

    def at(ix, iy):
        return _values[_perm[(_perm[ix & 255] + iy) & 255]]

    a, b, c, d = at(x0, y0), at(x1, y0), at(x0, y1), at(x1, y1)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v


def fbm(x, y, octaves=5, gain=0.5, wx=0, wy=0):
    """Octaves of noise. Each doubles the frequency exactly, so wrapping holds at every scale."""
    total, amp, norm = 0.0, 0.5, 0.0
    for _ in range(octaves):
        total = total + amp * noise(x, y, wx, wy)
        norm += amp
        x = x * 2 + 11
        y = y * 2 + 7
        wx *= 2
        wy *= 2
        amp *= gain
    return total / norm


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def rgb(r, g, b):
    return np.array([r, g, b], dtype=np.float32)


def mix(a, b, t):
    if np.ndim(t) == 2:
        t = t[..., None]
    return a * (1 - t) + b * t


def add(img, color, k):
    return img + k[..., None] * color


def clouds(p):
    """Flying forward over a sea of cloud toward a low sun."""
    horizon = 0.30
    d = np.maximum(Y - horizon, 0.002)
    s = d * 3.0 + 0.1
    px = (X - 0.9) / s * 0.9
    py = 1.0 / s - p * 4  # Four noise units ahead per loop, the wrap of the finer layer below.

    def cover_at(ox, oy):
        warp = fbm(px * 0.5, py * 0.5, 4, wy=2)
        return fbm(px + warp * 1.8 + ox, py + warp * 1.8 + oy, 6, wy=4)

    c = cover_at(0, 0)
    light = np.clip((c - cover_at(0.08, 0.1)) * 7 + 0.55, 0, 1)
    sky = mix(rgb(0.10, 0.20, 0.42), rgb(0.98, 0.72, 0.48), smooth(0.32, 0.0, np.abs(Y - horizon)) ** 2)
    sky = add(sky, rgb(1, 0.75, 0.5), np.exp(-((X - 1.15) ** 2 / 0.09 + (Y - horizon + 0.02) ** 2 / 0.012)) * 0.9)
    cl = mix(mix(rgb(0.16, 0.24, 0.42), rgb(0.42, 0.38, 0.50), light), mix(rgb(0.60, 0.54, 0.62), rgb(1, 0.86, 0.74), light), smooth(0.45, 0.8, c))
    cl = add(cl, rgb(0.55, 0.30, 0.12), np.exp(-((X - 1.15) ** 2) / 0.35) * np.exp(-d * 3.5) * 0.8)
    return mix(sky, cl, smooth(0.30, 0.54, c) * smooth(horizon, horizon + 0.025, Y))


def ocean(p):
    """A low sun over open water, its light scattering in a path across the swell."""
    horizon = 0.40
    sun_x = 1.12
    d = np.maximum(Y - horizon, 0.0015)
    s = d * 2.4 + 0.012
    px = (X - 0.89) / s
    py = 1.0 / s

    # Swell rolling toward the viewer, wrapping once per loop, and swaying gently sideways.
    a = 2 * math.pi * p
    sway = math.cos(a) * 0.25

    def height(ex=0.0, ey=0.0):
        long_swell = fbm((px + ex) * 0.55 + sway, (py + ey) - p * 8, 4, wy=8)
        chop = fbm((px + ex) * 1.5 - sway * 2, (py + ey) * 3 - p * 32, 3, wy=32)
        return long_swell * 0.72 + chop * 0.28

    e = 0.03
    here = height()
    nx = (height(ex=e) - here) * 7
    ny = (height(ey=e) - here) * 7
    sky = mix(rgb(0.13, 0.20, 0.42), rgb(0.98, 0.56, 0.32), smooth(horizon - 0.40, horizon, Y) ** 1.6)
    sky = add(sky, rgb(1, 0.72, 0.42), np.exp(-(((X - sun_x) ** 2) / 0.06 + ((Y - horizon + 0.05) ** 2) / 0.03)) * 0.55)
    sky = add(sky, rgb(1, 0.93, 0.78), np.exp(-(((X - sun_x) ** 2 + (Y - horizon + 0.05) ** 2) / 0.0016)) * 1.6)
    fresnel = np.clip(np.exp(-d * 6.5) + ny * 0.9, 0, 1)
    water = mix(rgb(0.015, 0.075, 0.15), rgb(0.82, 0.48, 0.33), fresnel)
    water = water * (1 + np.clip(ny, -0.5, 0.5)[..., None] * 0.9)
    # The sun's path widens toward the viewer; each facet flashes when its slope lines up.
    width = 0.03 + d * 0.22
    path = np.exp(-((X - sun_x + nx * 0.36) ** 2) / (width * width))
    water = add(water, rgb(1, 0.78, 0.5), path * smooth(-0.06, 0.13, ny) * 1.45)
    return mix(sky, water, smooth(horizon - 0.001, horizon + 0.002, Y))


def aurora(p):
    """Northern lights rippling over dark peaks and their reflection in still water."""
    a = 2 * math.pi * p
    tx, ty = math.cos(a) * 1.1, math.sin(a) * 1.1
    shore = 0.76

    sy = np.where(Y < shore, Y, 2 * shore - Y + (fbm(X * 3, Y * 90, 2) - 0.5) * 0.03 * (Y > shore))

    img = mix(rgb(0.012, 0.018, 0.055), rgb(0.05, 0.14, 0.20), smooth(0.05, 0.72, sy))
    stars = (GRAIN > 0.9975) * ((GRAIN - 0.9975) / 0.0025) * (Y < shore)
    img = add(img, rgb(0.85, 0.9, 1), stars * (0.45 + 0.4 * np.sin(GRAIN * 9000 + a)))

    for layer, (center, width, strength) in enumerate([(0.42, 0.26, 1.0), (0.24, 0.16, 0.6)]):
        o = layer * 7.3
        curve = center + (fbm(X * 1.05 + o + tx * 0.5, np.full_like(X, o) + ty * 0.5, 4) - 0.5) * 0.34
        rays = fbm(X * 11 + o + curve * 7 + tx * 1.5, sy * 0.35 + ty * 1.5, 4)
        rays = smooth(0.28, 0.78, rays)
        fold = 0.55 + 0.45 * fbm(X * 2.3 + o - ty, np.full_like(X, 1.7 + o) + tx, 3)
        lift = np.clip((curve - sy) / width, 0, 8)
        # Rising sharply out of a soft foot, then fading high up, as real curtains do.
        glow = np.exp(-lift * 1.6) * np.exp(-np.maximum(sy - curve, 0) / 0.055)
        k = glow * (0.28 + rays * 0.95) * fold * strength
        band = mix(rgb(0.12, 0.98, 0.52), rgb(0.48, 0.20, 0.85), smooth(0.2, 1.5, lift))
        img = img + k[..., None] * band

    ridge = shore - 0.02 - (fbm(X * 3.1 + 2.0, np.full_like(X, 4.4), 6, 0.55) - 0.32) * 0.26
    peak_mask = smooth(ridge - 0.002, ridge + 0.002, sy)
    img = mix(img, rgb(0.006, 0.012, 0.022), peak_mask)
    near = shore - (fbm(X * 7.5 + 9.0, np.full_like(X, 1.1), 5, 0.55) - 0.30) * 0.11
    img = mix(img, rgb(0.002, 0.004, 0.009), smooth(near - 0.002, near + 0.002, sy))
    water = Y >= shore
    img = np.where(water[..., None], img * (0.62 - (Y - shore) * 0.6)[..., None] + rgb(0.002, 0.005, 0.012), img)
    return img


def _ridge_layers():
    layers = []
    for i in range(6):
        t = i / 5
        base = 0.44 + t * 0.46
        f = 1.5 + i * 0.75
        layers.append((t, base + (0.5 - fbm(X * f + i * 9.1, np.full_like(X, i * 3.3), 6, 0.52)) * (0.40 - t * 0.12)))
    return layers


def ridges(p):
    """Ranges at sunrise, with mist drifting through the valleys between them."""
    img = mix(rgb(0.28, 0.40, 0.66), rgb(0.98, 0.72, 0.60), smooth(0.0, 0.62, Y))
    img = add(img, rgb(1, 0.8, 0.6), np.exp(-(((X - 0.6) ** 2 + ((Y - 0.46) * 1.2) ** 2) / 0.022)) * 0.75)
    tex = fbm(X * 22, Y * 22, 3)

    for t, top in _ridge_layers():
        near, far = rgb(0.05, 0.08, 0.13), rgb(0.66, 0.60, 0.66)
        col = mix(far, near, np.full_like(X, t ** 0.9))
        col = col * (0.92 + tex * 0.16)[..., None]
        haze = np.clip((Y - top) * 3, 0, 1) * (0.5 - t * 0.45)
        layer = col + (rgb(0.95, 0.72, 0.62) - col) * haze[..., None]
        img = mix(img, layer, smooth(top - 0.002, top + 0.002, Y))

        # Nearer mist drifts faster, each wrapping a whole number of times per loop.
        drift = 2 + round(t * 3)
        o2 = t * 37

        warp = fbm(X * 1.3 + o2 + p, Y * 3.0, 3, wx=1)
        mist = fbm(X * 2 + o2 + p * drift + warp * 0.8, Y * 7.5 + warp * 1.2, 5, wx=drift)
        # Pooled in the valley below this range's skyline, clear of the skyline itself.
        band = smooth(0.03, 0.12, Y - top) * smooth(0.30, 0.10, Y - top)
        m = smooth(0.36, 0.66, mist) * band * (0.9 - t * 0.55)
        img = mix(img, rgb(1.0, 0.82, 0.74), m)
    return img


_dune_base = None


def _dunes_static():
    global _dune_base
    if _dune_base is None:
        def height(x, warp):
            q = (x * 1.3 + Y * 3.4 + warp) * 3.1
            f = q - np.floor(q)
            ridge = np.where(f < 0.72, f / 0.72, (1 - f) / 0.28) ** 1.3
            return ridge * 0.6 + fbm(x * 3, Y * 5 + warp, 4) * 0.4

        e = 2.0 / H
        warp = fbm(X * 1.1 + 3, Y * 1.6, 4) * 2.4
        warp2 = fbm((X + e) * 1.1 + 3, Y * 1.6, 4) * 2.4
        slope = (height(X + e, warp2) - height(X, warp)) / e * 0.018
        light = np.clip(0.58 - slope * 0.85, 0, 1)
        light = np.clip(light + np.sin(X * 40 + Y * 90 + warp * 22) * 0.018 * smooth(0.2, 0.8, light), 0, 1)
        _dune_base = (light, warp)
    return _dune_base


def dunes(p):
    """Sand seen from high up, with cloud shadows crossing it and spindrift blowing off the crests."""
    light, warp = _dunes_static()

    shadow = fbm(X * 1.25 - p * 3, Y * 1.25, 5, wx=3)
    streaks = fbm(X * 9 + warp * 3 - p * 16, Y * 34 + warp * 7, 3, wx=16)
    shade = 0.56 + 0.44 * smooth(0.40, 0.62, shadow)
    blown = smooth(0.52, 0.80, streaks) * smooth(0.68, 0.98, light) * 0.13
    lit = np.clip((light + blown) * shade, 0, 1)
    return mix(mix(rgb(0.30, 0.12, 0.10), rgb(0.80, 0.42, 0.20), smooth(0.12, 0.55, lit)), rgb(1, 0.76, 0.48), smooth(0.5, 0.95, lit))


def earth(p):
    """The planet's edge from orbit at night: sunrise on the rim, cloud and city lights turning below."""
    cx, cy, radius = 1.0, 2.05, 1.72
    r = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)
    edge = r - radius
    angle = np.arctan2(X - cx, cy - Y)
    dawn = np.exp(-((angle - 0.38) ** 2) / 0.09)

    img = (GRAIN > 0.9985) * ((GRAIN - 0.9985) / 0.0015) * (edge > 0.02)
    img = np.repeat(img[..., None], 3, axis=2).astype(np.float32)
    outside = np.maximum(edge, 0)
    img = add(img, rgb(0.20, 0.46, 1.0), np.exp(-outside / 0.03) * 0.9 + np.exp(-outside / 0.11) * 0.42)
    img = add(img, rgb(1, 0.68, 0.36), dawn * np.exp(-outside / 0.035) * 1.6)

    lat = np.clip(-edge / 0.9, 0, 1)
    u = angle * 3.2 / (0.25 + lat * 0.75)
    v = 0.8 / (0.06 + lat * 1.2)

    # The surface turns underneath, and cloud a little faster over it, each by whole wraps per loop.
    ground = u + p * 4
    land = smooth(0.46, 0.60, fbm(ground * 2 + 5, v * 1.8, 6, wx=4))
    surf = mix(rgb(0.012, 0.03, 0.075), rgb(0.03, 0.045, 0.05), land)
    city = (fbm(ground * 26 + 3, v * 26, 3, wx=52) > 0.70) * land * smooth(0.45, 0.75, fbm(ground * 4 + 9, v * 4, 4, wx=8))
    surf = add(surf, rgb(1, 0.72, 0.38), city * 0.8)
    high = u + p * 6
    cloud_warp = fbm(high, v, 3, wx=3)
    cloud = smooth(0.52, 0.72, fbm(high * 3 + 2, v * 3.2 + cloud_warp * 2, 6, wx=9))
    surf = surf * (1 - cloud * 0.55)[..., None]
    surf = add(surf, rgb(0.9, 0.85, 0.85), cloud * (0.03 + dawn * np.exp(-lat * 7) * 0.55))
    surf = add(surf, rgb(0.18, 0.40, 0.9), np.exp(-lat * 26) * 0.5)
    surf = add(surf, rgb(1, 0.55, 0.28), dawn * np.exp(-lat * 11) * 0.7)
    return np.where((edge < 0)[..., None], surf, img)


SCENES = {"clouds": clouds, "ocean": ocean, "aurora": aurora, "ridges": ridges, "dunes": dunes, "earth": earth}


def frame(args):
    name, i = args
    return to_bytes(SCENES[name](i / FRAMES))


def to_bytes(img):
    # Fine dither, which keeps dark skies from banding. Fixed per pixel, so it costs the encoder little.
    return (np.clip(img + (GRAIN[..., None] - 0.5) / 255, 0, 1) * 255 + 0.5).astype(np.uint8)


def render_video(name, out_dir):
    import av

    path = os.path.join(out_dir, f"{name}.mp4")
    container = av.open(path, "w", options={"movflags": "faststart"})
    stream = container.add_stream("libx264", rate=FPS, options={"crf": "22", "preset": "slow", "tune": "film"})
    stream.width, stream.height, stream.pix_fmt = W, H, "yuv420p"
    with multiprocessing.Pool() as pool:
        for i, pixels in enumerate(pool.imap(frame, [(name, n) for n in range(FRAMES)], chunksize=4)):
            for packet in stream.encode(av.VideoFrame.from_ndarray(pixels, format="rgb24")):
                container.mux(packet)
            if i % 96 == 0:
                print(name, i, flush=True)
    for packet in stream.encode():
        container.mux(packet)
    container.close()
    print(path, os.path.getsize(path) // 1024, "KB", flush=True)


def render_stills(out_dir):
    from PIL import Image

    names = list(SCENES)
    cell_w, cell_h = 640, 360
    sheet = Image.new("RGB", (cell_w * 2 + 8, (cell_h + 8) * math.ceil(len(names) / 2)), (40, 40, 40))
    with multiprocessing.Pool() as pool:
        for i, pixels in enumerate(pool.map(frame, [(n, FRAMES // 3) for n in names])):
            image = Image.fromarray(pixels)
            image.save(os.path.join(out_dir, f"still_{names[i]}.png"))
            sheet.paste(image.resize((cell_w, cell_h)), ((i % 2) * (cell_w + 8), (i // 2) * (cell_h + 8)))
    sheet.save(os.path.join(out_dir, "stills.png"))


if __name__ == "__main__":
    mode, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    if mode == "--still":
        render_stills(out)
    else:
        for scene in sys.argv[3:] or SCENES:
            render_video(scene, out)
