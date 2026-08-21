#!/usr/bin/env python3
"""
Writes the GameTest template the tests are run inside: a flat 9x5x9 platform, one
layer of polished andesite at relative y=0 and air above it.

A GameTest needs a structure and a structure is a binary NBT file, which is
normally produced by standing in a world with a structure block. Generating it
instead keeps the fixture readable and regenerable -- the alternative is an
opaque blob in the repository that nobody can change without launching the game.

    python3 tools/generate_test_structure.py

Rerun after editing; it overwrites in place.
"""

import gzip
import os
import struct

OUT = 'src/main/resources/data/shufflerbox/structure/platform.nbt'

DATA_VERSION = 3955          # 1.21.1, from SharedConstants.WORLD_VERSION
WIDTH, HEIGHT, DEPTH = 9, 5, 9
FLOOR = 'minecraft:polished_andesite'

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def name(text):
    encoded = text.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def tag_int(key, value):
    return bytes([TAG_INT]) + name(key) + struct.pack('>i', value)


def tag_string(key, value):
    encoded = value.encode('utf-8')
    return bytes([TAG_STRING]) + name(key) + struct.pack('>H', len(encoded)) + encoded


def int_list(key, values):
    """A TAG_List of TAG_Int -- what the structure format uses for size and pos."""
    payload = struct.pack('>i', len(values)) + b''.join(struct.pack('>i', v) for v in values)
    return bytes([TAG_LIST]) + name(key) + bytes([TAG_INT]) + payload


def compound_list(key, entries):
    """A TAG_List of TAG_Compound, each entry already serialised as a payload."""
    payload = struct.pack('>i', len(entries)) + b''.join(e + bytes([TAG_END]) for e in entries)
    return bytes([TAG_LIST]) + name(key) + bytes([TAG_COMPOUND]) + payload


def main():
    palette = [tag_string('Name', FLOOR)]
    blocks = [
        int_list('pos', [x, 0, z]) + tag_int('state', 0)
        for x in range(WIDTH)
        for z in range(DEPTH)
    ]

    body = (int_list('size', [WIDTH, HEIGHT, DEPTH])
            + compound_list('palette', palette)
            + compound_list('blocks', blocks)
            + compound_list('entities', [])
            + tag_int('DataVersion', DATA_VERSION))

    root = bytes([TAG_COMPOUND]) + name('') + body + bytes([TAG_END])

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, 'wb') as handle:
        handle.write(root)
    print(f'{OUT} ({WIDTH}x{HEIGHT}x{DEPTH}, floor of {FLOOR})')


if __name__ == '__main__':
    main()
