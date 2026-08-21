#!/usr/bin/env python3
"""
Draws the block's six faces. There is no separate item sprite: the item renders the
block model, the way every other block item does, so a Shuffler Box in the inventory
is the same box you put down. A flat sprite was tried first and read as a purple
rectangle among a hotbar of 3D blocks.

Each face is a shulker box side -- a lighter lid on top, a seam, then the darker
base -- carrying die pips. Faces are numbered so opposite faces sum to seven, like a
real die, and the three the inventory happens to show (top, north, east) are the 1,
2 and 3.

    python3 tools/generate_textures.py

Rerun after editing; it overwrites in place.
"""

import os
import struct
import zlib

OUT = 'src/main/resources/assets/shufflerbox/textures/block'

# --- palette -------------------------------------------------------------------
# A purple in the same family as a vanilla shulker box, drawn from scratch rather
# than sampled: Mojang's textures are not ours to copy.
PALETTE = {
    '#': (58, 33, 74, 255),       # outline and the lid seam
    's': (106, 66, 133, 255),     # base shadow
    'B': (138, 88, 168, 255),     # base
    'h': (166, 116, 196, 255),    # base highlight
    'l': (160, 112, 190, 255),    # lid shadow
    'L': (188, 142, 216, 255),    # lid
    'H': (208, 170, 232, 255),    # lid highlight
    'w': (243, 240, 247, 255),    # pip
    'g': (196, 188, 208, 255),    # pip shading
}

TONES = {
    'base': {'fill': 'B', 'light': 'h', 'dark': 's'},
    'lid': {'fill': 'L', 'light': 'H', 'dark': 'l'},
}

# --- the shulker box banding ---------------------------------------------------
# The lid takes the top of a side face and the base the rest, with a hard seam
# between them. The seam sits at row 6 because that is the gap between the first
# and second rows of pips -- anywhere else and it would cut a pip in half.
SEAM_ROW = 6

# --- die faces -----------------------------------------------------------------
# Which cells of a 3x3 grid a face fills, keyed by pip count. Read as (column, row)
# from the top left.
PIPS = {
    1: [(1, 1)],
    2: [(0, 0), (2, 2)],
    3: [(0, 0), (1, 1), (2, 2)],
    4: [(0, 0), (2, 0), (0, 2), (2, 2)],
    5: [(0, 0), (2, 0), (1, 1), (0, 2), (2, 2)],
    6: [(0, 0), (0, 1), (0, 2), (2, 0), (2, 1), (2, 2)],
}

PIP_SIZE = 3
PIP_OFFSETS = (3, 7, 11)   # top-left of each grid column/row, in pixels

# A die's one is a single large pip, not a small one adrift in an empty face -- and on
# this block the one is the lid, the face an inventory slot shows most of. Four wide
# rather than five so it lands dead centre of a sixteen-pixel face.
ONE_PIP_SIZE = 4
ONE_PIP_OFFSET = 6

# Opposite faces sum to seven. Within that, the numbering is chosen so the three
# faces an inventory slot shows -- top, north and east, at the standard [30, 225]
# viewing angle -- come out as 1, 2 and 3.
FACES = {
    'top': (1, 'lid'),
    'bottom': (6, 'base'),
    'north': (2, 'side'),
    'south': (5, 'side'),
    'east': (3, 'side'),
    'west': (4, 'side'),
}


def row_tones(kind):
    """Which tone each of the 16 rows is drawn in. Only a side face is banded."""
    if kind != 'side':
        return [kind] * 16

    return ['lid' if row <= SEAM_ROW else 'base' for row in range(16)]


def face(pip_count, kind):
    """A 16x16 face: outlined, bevelled, banded if it is a side, and pipped."""
    tones = row_tones(kind)
    grid = [[TONES[tones[y]]['fill']] * 16 for y in range(16)]

    for i in range(16):
        grid[0][i] = grid[15][i] = grid[i][0] = grid[i][15] = '#'

    for i in range(1, 15):
        grid[1][i] = TONES[tones[1]]['light']
        grid[14][i] = TONES[tones[14]]['dark']
        grid[i][1] = TONES[tones[i]]['light']
        grid[i][14] = TONES[tones[i]]['dark']

    # The corners where a light bevel meets a dark one; the dark wins, so the cube
    # keeps a clean edge.
    grid[1][14] = TONES[tones[1]]['dark']
    grid[14][1] = TONES[tones[14]]['dark']

    if kind == 'side':
        # The seam, and a lit top edge on the base below it so the two read as two
        # slabs rather than one panel that changes colour.
        for x in range(1, 15):
            grid[SEAM_ROW][x] = '#'
            grid[SEAM_ROW + 1][x] = TONES['base']['light']
        grid[SEAM_ROW + 1][14] = TONES['base']['dark']

    size = ONE_PIP_SIZE if pip_count == 1 else PIP_SIZE
    for col, row in PIPS[pip_count]:
        if pip_count == 1:
            x0 = y0 = ONE_PIP_OFFSET
        else:
            x0, y0 = PIP_OFFSETS[col], PIP_OFFSETS[row]

        for y in range(y0, y0 + size):
            for x in range(x0, x0 + size):
                # The far corner of a pip is shaded, so it reads as drilled into the
                # face rather than painted onto it. The large single pip carries that
                # shading along both far edges, which a three-pixel pip is too small for.
                corner = x == x0 + size - 1 and y == y0 + size - 1
                edge = size > PIP_SIZE and (x == x0 + size - 1 or y == y0 + size - 1)
                grid[y][x] = 'g' if corner or edge else 'w'

    return [''.join(row) for row in grid]


# --- PNG ------------------------------------------------------------------------
def write_png(path, rows):
    """Writes a character grid out as an 8-bit RGBA PNG."""
    width, height = len(rows[0]), len(rows)
    raw = bytearray()
    for row in rows:
        raw.append(0)                      # filter type 0 (None)
        for ch in row:
            raw.extend(PALETTE[ch])

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
           + chunk(b'IEND', b''))

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)
    print(f'{path} ({width}x{height})')


def main():
    for name, (pip_count, kind) in FACES.items():
        write_png(f'{OUT}/shuffler_box_{name}.png', face(pip_count, kind))


if __name__ == '__main__':
    main()
