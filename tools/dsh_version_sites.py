"""Every place in the repository that must name the bundled DSH version.

`tools/dsh-runtime/package.json` is the single source of truth. The sites below are
copies the app or its tooling needs at run time (Java constant, Gradle version name,
plugin peers, migration helper, patch recipes, generated proofs). `check()` lists the
ones that disagree with the lock; `set_mechanical()` rewrites the copies whose value
is a plain version string. Recipes and generated proofs are deliberately not rewritten
here: recipes are stamped by tools/scan-runtime-patches.py only after every anchor is
verified on the new tree, and proofs come from their own generators.
"""
import json
import re
from pathlib import Path

ASSETS = 'app/src/main/assets'
LOCK = 'tools/dsh-runtime/package.json'
CONSTANTS = 'app/src/main/java/com/deepseekharness/app/util/Constants.java'
GRADLE = 'app/build.gradle'
MIGRATION = ASSETS + '/rc1-migration.py'
PLUGINS = ASSETS + '/builtin-plugins'

_CONSTANT = re.compile(r'(public static final String DSH_VERSION = ")([^"]+)(";)')
_GRADLE = re.compile(r'(versionName "[^"]*-dsh)([^"]+)(")')
_MIGRATION = re.compile(r"^(VERSION = ')([^']+)(')$", re.M)


def locked(root):
    return json.loads((Path(root) / LOCK).read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']


def _regex_value(path, pattern):
    match = pattern.search(path.read_text(encoding='utf-8'))
    return match.group(2) if match else None


def _plugin_pins(root):
    """Exact @deepseek-ai/dsh* peer pins of the built-in plugins (ranges are left alone)."""
    for manifest in sorted((Path(root) / PLUGINS).glob('*/package.json')):
        peers = json.loads(manifest.read_text(encoding='utf-8')).get('peerDependencies') or {}
        for name, value in peers.items():
            if name.startswith('@deepseek-ai/dsh') and re.fullmatch(r'\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?', value):
                yield manifest.relative_to(root).as_posix() + ':' + name, value


def _recipes(root):
    assets = Path(root) / ASSETS
    for path in sorted(assets.glob('*.json')):
        try:
            value = json.loads(path.read_text(encoding='utf-8'))
        except ValueError:
            continue
        if isinstance(value, dict) and isinstance(value.get('patches'), list) and 'dshVersion' in value:
            yield path.relative_to(root).as_posix(), value['dshVersion']
    registry = json.loads((assets / 'runtime-patches.json').read_text(encoding='utf-8'))
    yield ASSETS + '/runtime-patches.json', registry.get('dshVersion')


def sites(root):
    """(site, value, kind) for every copy; kind is 'mechanical', 'recipe' or 'generated'."""
    root = Path(root)
    yield CONSTANTS, _regex_value(root / CONSTANTS, _CONSTANT), 'mechanical'
    yield GRADLE + ':versionName', _regex_value(root / GRADLE, _GRADLE), 'mechanical'
    yield MIGRATION, _regex_value(root / MIGRATION, _MIGRATION), 'mechanical'
    for site, value in _plugin_pins(root):
        yield site, value, 'mechanical'
    for site, value in _recipes(root):
        yield site, value, 'recipe'
    proof = json.loads((root / ASSETS / 'client-seed-proof.json').read_text(encoding='utf-8'))
    yield ASSETS + '/client-seed-proof.json', proof.get('dshVersion'), 'generated'
    descriptor = json.loads((root / ASSETS / 'runtime-descriptor.json').read_text(encoding='utf-8'))
    yield ASSETS + '/runtime-descriptor.json', descriptor.get('dshVersion'), 'generated'


def check(root):
    """Sites whose value differs from the lock, as (site, value, kind)."""
    expected = locked(root)
    return [row for row in sites(root) if row[1] != expected]


def _sub(path, pattern, version):
    text = path.read_text(encoding='utf-8')
    updated, count = pattern.subn(lambda m: m.group(1) + version + m.group(3), text)
    if count != 1:
        raise ValueError('DSH_VERSION_SITE_NOT_FOUND: ' + str(path))
    if updated != text:
        path.write_text(updated, encoding='utf-8')


def set_mechanical(root, version):
    """Rewrite the plain-string copies to `version`; returns the files changed."""
    root = Path(root)
    before = {p: p.read_bytes() for p in [root / CONSTANTS, root / GRADLE, root / MIGRATION]}
    _sub(root / CONSTANTS, _CONSTANT, version)
    _sub(root / GRADLE, _GRADLE, version)
    _sub(root / MIGRATION, _MIGRATION, version)
    for manifest in sorted((root / PLUGINS).glob('*/package.json')):
        text = manifest.read_text(encoding='utf-8')
        before[manifest] = text.encode('utf-8')
        data = json.loads(text)
        peers = data.get('peerDependencies') or {}
        changed = False
        for name, value in peers.items():
            if name.startswith('@deepseek-ai/dsh') and re.fullmatch(r'\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?', value) \
                    and value != version:
                # Rewrite only the value text so the file's own formatting stays intact.
                pattern = re.compile(r'("' + re.escape(name) + r'"\s*:\s*")' + re.escape(value) + '"')
                text, count = pattern.subn(lambda m: m.group(1) + version + '"', text)
                changed = changed or count > 0
        if changed:
            manifest.write_text(text, encoding='utf-8')
    return sorted(p.relative_to(root).as_posix() for p, data in before.items() if p.read_bytes() != data)
