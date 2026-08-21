#!/usr/bin/env python3
"""
Writes the block tag naming every copycat: the blocks that take their material from the
placer's off hand, and so the blocks the Shuffler Box fills instead of replacing.

    python3 tools/generate_copycat_tag.py                    # reads run/mods
    python3 tools/generate_copycat_tag.py --game-dir run-gametest

Read out of the installed Create and Copycats+ jars rather than typed out, because there are
fifty of them and the list grows every time Copycats+ adds a shape. Run
`python3 tools/fetch_dev_mods.py` first; this needs those jars present to read.

Every entry is written `required: false`, so the tag loads unchanged on an install that has
neither mod -- which is most of them. The tag is also the extension point: a datapack can add
a copycat-like block from some other mod and the box will fill that too, with no code here
knowing it exists.

Rerun after a Copycats+ update. A copycat missing from the tag is not a crash, it is the box
going back to replacing that block instead of filling it, which is only confusing.
"""

import argparse
import json
import os
import re
import zipfile

OUT = 'src/main/resources/data/shufflerbox/tags/block/fills_from_off_hand.json'

# The mods that have copycats, and the namespace each one registers them under.
SOURCES = [
    ('create-', 'create'),
    ('copycats-', 'copycats'),
]

BLOCKSTATE = re.compile(r'^assets/(?P<namespace>[a-z0-9_.-]+)/blockstates/(?P<block>[a-z0-9_]+)\.json$')


def copycats_in(jar_path, namespace):
    """Every block id in one jar whose name says it is a copycat."""
    found = set()
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.namelist():
            match = BLOCKSTATE.match(entry)
            if not match or match.group('namespace') != namespace:
                continue
            block = match.group('block')
            if 'copycat' in block:
                found.add(f'{namespace}:{block}')
    return found


def collect(directory):
    blocks = set()
    for name in sorted(os.listdir(directory)):
        if not name.endswith('.jar'):
            continue
        for prefix, namespace in SOURCES:
            if name.startswith(prefix):
                found = copycats_in(os.path.join(directory, name), namespace)
                print(f'{name}: {len(found)} copycats')
                blocks |= found
    return blocks


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--game-dir', default='run',
                        help='the run directory holding the mods to read (default: run)')
    parser.add_argument('output', nargs='?', default=OUT)
    args = parser.parse_args()

    directory = os.path.join(args.game_dir, 'mods')
    if not os.path.isdir(directory):
        raise SystemExit(f'{directory} is not there. Run tools/fetch_dev_mods.py first.')

    blocks = collect(directory)
    if not blocks:
        # Writing an empty tag would silently switch the copycat handling off, which is worse
        # than doing nothing: the box would go back to replacing copycats in hand.
        raise SystemExit(f'no copycats found in {directory}. Run tools/fetch_dev_mods.py first; '
                         f'{args.output} has been left alone.')

    tag = {'replace': False,
           'values': [{'id': block, 'required': False} for block in sorted(blocks)]}

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, 'w') as handle:
        json.dump(tag, handle, indent=2)
        handle.write('\n')
    print(f'{args.output} ({len(blocks)} blocks)')


if __name__ == '__main__':
    main()
