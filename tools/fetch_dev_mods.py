#!/usr/bin/env python3
"""
Installs the other mods a dev run is tested against, into a run directory's mods folder.

    python3 tools/fetch_dev_mods.py                     # into run/, what ./gradlew runClient uses
    python3 tools/fetch_dev_mods.py --list              # print the pinned set and exit
    python3 tools/fetch_dev_mods.py --with-jei          # add JEI, to find copycat items by name
    python3 tools/fetch_dev_mods.py --game-dir run-gametest
    python3 tools/fetch_dev_mods.py --clean             # take them out again

None of this is a dependency of the mod. Shuffler Box compiles, runs and passes its
GameTests with none of it installed, and nothing in the jar refers to any of it. It is
here so that a claim the README makes -- that a Create: Copycats+ panel placed out of the
box arrives carrying the material it was holding -- can be checked in a client rather than
reasoned about, and rechecked after a change to the placement path.

Release jars in a mods folder, rather than dependencies on the run classpath, for two
reasons. Create carries its own libraries (Flywheel, Ponder, Registrate) as jars nested
inside its jar, and FML only unpacks those when it loads the outer jar from a mods folder;
the same file on a run's classpath is read as one flat jar, the nested libraries never
register and Create dies during startup. And loading the file a player downloads is the
point of the exercise: a compatibility test wants the artifact, not a rebuild of it.

Versions are pinned below. Modrinth's API turns each one into a download URL and a sha512,
which is checked against the bytes that arrive, so a half-finished download fails here
instead of surfacing as a corrupt-jar crash twenty minutes later.
"""

import argparse
import hashlib
import json
import os
import urllib.parse
import urllib.request

API = 'https://api.modrinth.com/v2'
MINECRAFT_VERSION = '1.21.1'
LOADER = 'neoforge'
USER_AGENT = 'steal-my-mods/shuffler-box (dev mod fetcher)'

# The compatibility target and everything it requires. Copycats+ needs Create; Create needs
# nothing else on NeoForge, because what it needs it carries.
PINNED = [
    ('create', '6.0.10+mc1.21.1'),
    ('copycats', '3.0.6+mc.1.21.1-neoforge'),
]

# Optional, and not part of the compatibility question: an item search makes the twenty-odd
# copycat blocks findable without knowing what each one is called.
OPTIONAL = [
    ('jei', '19.44.0.403'),
]


def api(path, **query):
    url = f'{API}/{path}'
    if query:
        url += '?' + urllib.parse.urlencode(query)
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def resolve(slug, wanted):
    """The download URL, file name and sha512 of one pinned version."""
    versions = api(f'project/{slug}/version',
                   game_versions=json.dumps([MINECRAFT_VERSION]),
                   loaders=json.dumps([LOADER]))
    for version in versions:
        if version['version_number'] == wanted:
            primary = next((f for f in version['files'] if f['primary']), None)
            if primary is None:
                raise SystemExit(f'{slug} {wanted} lists no primary file to download.')
            return {
                'slug': slug,
                'version': wanted,
                'file': primary['filename'],
                'url': primary['url'],
                'sha512': primary['hashes']['sha512'],
            }
    available = ', '.join(v['version_number'] for v in versions[:5])
    raise SystemExit(f'{slug} has no {MINECRAFT_VERSION} {LOADER} version {wanted}. '
                     f'Latest are: {available}')


def download(entry, directory):
    path = os.path.join(directory, entry['file'])
    if os.path.exists(path) and sha512(path) == entry['sha512']:
        print(f'{path} (already there)')
        return
    request = urllib.request.Request(entry['url'], headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response:
        payload = response.read()
    digest = hashlib.sha512(payload).hexdigest()
    if digest != entry['sha512']:
        raise SystemExit(f'{entry["file"]} downloaded corrupt: sha512 {digest[:16]}... '
                         f'not {entry["sha512"][:16]}...')
    with open(path, 'wb') as handle:
        handle.write(payload)
    print(f'{path} ({len(payload) // 1024} KiB)')


def sha512(path):
    with open(path, 'rb') as handle:
        return hashlib.sha512(handle.read()).hexdigest()


def prune(directory, keep, slugs):
    """Drops versions of the mods this script manages that it no longer pins.

    Without this, bumping a pin leaves the old jar beside the new one and FML refuses to load two
    copies of the same mod -- so the fetch would have to be cleaned by hand to be useful. Only jars
    whose name is one of these slugs followed by a version number are touched, so anything else
    dropped into the folder by hand, and an optional mod not asked for this run, is left alone.
    """
    for name in sorted(os.listdir(directory)):
        if not name.endswith('.jar') or name in keep:
            continue
        for slug in slugs:
            prefix = f'{slug}-'
            if name.startswith(prefix) and name[len(prefix):len(prefix) + 1].isdigit():
                os.remove(os.path.join(directory, name))
                print(f'removed {os.path.join(directory, name)} (no longer pinned)')
                break


def clean(directory):
    if not os.path.isdir(directory):
        print(f'{directory} is not there; nothing to remove')
        return
    removed = [name for name in sorted(os.listdir(directory)) if name.endswith('.jar')]
    for name in removed:
        os.remove(os.path.join(directory, name))
        print(f'removed {os.path.join(directory, name)}')
    if not removed:
        print(f'{directory} holds no jars')


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--game-dir', default='run',
                        help="the run directory to install into (default: run, what runClient uses)")
    parser.add_argument('--with-jei', action='store_true', help='also install JEI')
    parser.add_argument('--list', action='store_true', help='print the pinned set and exit')
    parser.add_argument('--clean', action='store_true', help='remove every jar from the mods folder')
    args = parser.parse_args()

    directory = os.path.join(args.game_dir, 'mods')

    if args.clean:
        clean(directory)
        return

    wanted = PINNED + (OPTIONAL if args.with_jei else [])

    if args.list:
        for slug, version in wanted:
            print(f'{slug} {version}')
        return

    # Everything resolved before anything is written, so a version that does not exist fails the
    # run without having half-installed the ones before it.
    entries = [resolve(slug, version) for slug, version in wanted]

    os.makedirs(directory, exist_ok=True)
    for entry in entries:
        download(entry, directory)

    prune(directory, {entry['file'] for entry in entries}, [slug for slug, _ in wanted])


if __name__ == '__main__':
    main()
