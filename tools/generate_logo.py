#!/usr/bin/env python3
"""
Generates the mod logo: the Shuffler Box itself, drawn as the isometric block a
player sees in their inventory, lit from above and standing on a dark plum field.

Deliberately *not* the Create-family badge that Create: Workers uses. That badge
says "addon for Create", and this mod is standalone -- borrowing the look would
claim a relationship it does not have.

The subject is the block's own six face textures, projected and scaled by a whole
number so its pixels stay square. That means it is the same box the player is
looking at in their hotbar, at any size, rather than a second drawing that has to
be kept in step with the first.

    python3 tools/generate_logo.py [--size 256] [output.png]

The size must be a multiple of 256, or CUBE_EDGE stops being a whole number of
texels and the pixels stop being square.
"""

import argparse
import math
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate_textures as textures

REFERENCE = 256                # the size every measurement below was tuned at
SS = 3                         # supersampling per axis, for the background only

# --- geometry, in reference pixels ----------------------------------------------
# CUBE_EDGE is the isometric cube's half-width; the subject is 2x that wide and
# 1.5x that tall. 96 leaves the box filling three quarters of the frame, which is
# as large as it goes before the drop shadow runs out of room.
CUBE_EDGE = 96
STROKE = 2
SHADOW_DROP = 7
SHADOW_BLUR = 6

# --- palette ---------------------------------------------------------------------
FIELD_TOP = (62, 39, 84)
FIELD_BOTTOM = (28, 18, 38)
GLOW = (129, 84, 170)
STROKE_COLOUR = (247, 244, 250)
SHADOW = (10, 5, 18)

GLOW_STRENGTH = 0.55
SHADOW_ALPHA = 0.5
CORNER_RADIUS = 0.14           # as a fraction of the size

# The three faces an isometric view shows, and Minecraft's own shading for them,
# lifted a little because this field is darker than a game world.
SHADING = {'top': 1.0, 'right': 0.82, 'left': 0.64}


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


# --- the subject -------------------------------------------------------------------
def draw_cube(size, scale):
    """
    The block in isometric projection. Returns (pixels, mask), both size*size, with
    the mask marking which pixels the cube covers.

    Faces are sampled by inverse-mapping each screen pixel back into face space, so
    the projection is exact and every texel lands on a whole number of pixels.
    """
    edge = CUBE_EDGE * scale
    faces = {
        'top': textures.face(*textures.FACES['top']),
        'right': textures.face(*textures.FACES['north']),   # north is on the right at yaw 225
        'left': textures.face(*textures.FACES['east']),     # east on the left
    }

    origin_x = size // 2
    origin_y = (size - int(1.5 * edge)) // 2 + edge // 2

    pixels = [[None] * size for _ in range(size)]
    mask = [[False] * size for _ in range(size)]

    for py in range(size):
        for px in range(size):
            x, y = px - origin_x, py - origin_y

            # Top face, from the apex; then the two side faces from the corners they
            # hang off. First hit wins -- they do not overlap.
            candidates = (
                ('top', 8 * x / edge + 16 * (y + edge / 2) / edge,
                 16 * (y + edge / 2) / edge - 8 * x / edge),
                ('left', 16 * (x + edge) / edge,
                 16 * y / edge - 16 * (x + edge) / edge / 2),
                ('right', 16 * x / edge,
                 16 * (y - edge / 2) / edge + 16 * x / edge / 2),
            )

            for name, u, v in candidates:
                if 0 <= u < 16 and 0 <= v < 16:
                    colour = textures.PALETTE[faces[name][int(v)][int(u)]]
                    shade = SHADING[name]
                    pixels[py][px] = tuple(int(c * shade) for c in colour[:3])
                    mask[py][px] = True
                    break

    return pixels, mask


def grow(mask, size, reach):
    """The mask spread outwards by {@code reach} pixels -- a Chebyshev dilation."""
    grown = [[False] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            if not mask[y][x]:
                continue
            for dy in range(-reach, reach + 1):
                for dx in range(-reach, reach + 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < size and 0 <= nx < size:
                        grown[ny][nx] = True

    return grown


def blur(field, size, radius):
    """A separable box blur, run twice, which is close enough to a gaussian here."""
    for _ in range(2):
        rows = []
        for y in range(size):
            row, running = [0.0] * size, 0.0
            for x in range(size + radius):
                if x < size:
                    running += field[y][x]
                if x - 2 * radius - 1 >= 0:
                    running -= field[y][x - 2 * radius - 1]
                if x - radius >= 0:
                    row[x - radius] = running / (2 * radius + 1)
            rows.append(row)

        field = [[rows[y][x] for x in range(size)] for y in range(size)]
        field = [list(col) for col in zip(*field)]   # transpose, blur the other axis
        rows = []
        for y in range(size):
            row, running = [0.0] * size, 0.0
            for x in range(size + radius):
                if x < size:
                    running += field[y][x]
                if x - 2 * radius - 1 >= 0:
                    running -= field[y][x - 2 * radius - 1]
                if x - radius >= 0:
                    row[x - radius] = running / (2 * radius + 1)
            rows.append(row)

        field = [list(col) for col in zip(*rows)]

    return field


# --- the field ---------------------------------------------------------------------
def draw_field(size, scale):
    """The rounded plum square behind the box, supersampled so its corners are smooth."""
    hi = size * SS
    radius = CORNER_RADIUS * hi
    centre = hi / 2
    glow_radius = 0.52 * hi

    field = [[(0, 0, 0, 0)] * size for _ in range(size)]

    for py in range(size):
        for px in range(size):
            total = [0.0, 0.0, 0.0, 0.0]

            for sy in range(SS):
                for sx in range(SS):
                    x, y = px * SS + sx + 0.5, py * SS + sy + 0.5

                    # Inside the rounded square?
                    dx = max(radius - x, x - (hi - radius), 0.0)
                    dy = max(radius - y, y - (hi - radius), 0.0)
                    if math.hypot(dx, dy) > radius:
                        continue

                    colour = lerp(FIELD_TOP, FIELD_BOTTOM, y / hi)

                    # A soft pool of light behind where the box will stand, so the
                    # subject is not a dark shape on a dark ground.
                    away = math.hypot(x - centre, y - centre * 0.92) / glow_radius
                    if away < 1.0:
                        colour = lerp(colour, GLOW, GLOW_STRENGTH * (1.0 - away) ** 2)

                    total[0] += colour[0]
                    total[1] += colour[1]
                    total[2] += colour[2]
                    total[3] += 255.0

            samples = SS * SS
            if total[3] > 0:
                # Average the colour over the covered samples only, or the edge
                # darkens towards black as coverage falls off.
                covered = total[3] / 255.0
                field[py][px] = (
                    int(total[0] / covered), int(total[1] / covered),
                    int(total[2] / covered), int(total[3] / samples))

    return field


def render(size):
    scale = size // REFERENCE
    field = draw_field(size, scale)
    pixels, mask = draw_cube(size, scale)

    # The drop shadow: the cube's own silhouette, dropped, spread and blurred.
    dropped = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            if mask[y][x]:
                below = y + SHADOW_DROP * scale
                if below < size:
                    dropped[below][x] = 1.0
    shadow = blur(grow_alpha(dropped, size, STROKE * scale), size, SHADOW_BLUR * scale)

    stroke = grow(mask, size, STROKE * scale)

    out = []
    for y in range(size):
        row = []
        for x in range(size):
            r, g, b, a = field[y][x]

            if a > 0 and shadow[y][x] > 0.01:
                t = min(1.0, shadow[y][x]) * SHADOW_ALPHA
                r, g, b = (int(v) for v in lerp((r, g, b), SHADOW, t))

            if mask[y][x]:
                r, g, b = pixels[y][x]
                a = 255
            elif stroke[y][x] and a > 0:
                r, g, b = STROKE_COLOUR
                a = 255

            row.append((r, g, b, a))
        out.append(row)

    return out


def grow_alpha(field, size, reach):
    """Dilation over a float field, for the shadow."""
    mask = [[value > 0.5 for value in row] for row in field]
    grown = grow(mask, size, reach)
    return [[1.0 if grown[y][x] else 0.0 for x in range(size)] for y in range(size)]


def write_png(path, rows):
    width, height = len(rows[0]), len(rows)
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
           + chunk(b'IEND', b''))

    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)
    print(f'{path} ({width}x{height})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', nargs='?', default='src/main/resources/shufflerbox_icon.png')
    parser.add_argument('--size', type=int, default=REFERENCE)
    args = parser.parse_args()

    if args.size % REFERENCE != 0:
        parser.error(f'size must be a multiple of {REFERENCE}, or the box stops being pixel-aligned')

    write_png(args.output, render(args.size))


if __name__ == '__main__':
    main()
