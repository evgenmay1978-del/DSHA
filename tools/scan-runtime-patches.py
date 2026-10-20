#!/usr/bin/env python3
"""Check every runtime patch recipe against an installed DSH tree, without writing to it.

For each recipe in app/src/main/assets (a JSON file with a `patches` list) the scanner
applies its patches in order to an in-memory copy of the target module, with the same
rule the builder and the device use: `before` must occur exactly once. It reports every
anchor that no longer holds, so a core upgrade gets the full list in one run instead of
one failure per rebuild.

  python3 tools/scan-runtime-patches.py <node_modules>            # report; exit 1 on any break
  python3 tools/scan-runtime-patches.py <node_modules> --stamp    # also set dshVersion in all
                                                                  # recipes + registry (only if clean)
Anchors that live inside tools/build-dsh-runtime.py itself are checked by building the archive.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
# Builder-owned recipe whose module is a constant in tools/build-dsh-runtime.py.
BUILDER_MODULES = {'client-combo-patch.json': '@deepseek-ai/dsh-client-modules/lib/index.js'}


def recipes():
    registry = json.loads((ASSETS / 'runtime-patches.json').read_text(encoding='utf-8'))
    modules = {row['asset']: row.get('module') for row in registry.get('active', []) if isinstance(row, dict)}
    modules.update(BUILDER_MODULES)
    for path in sorted(ASSETS.glob('*.json')):
        try:
            value = json.loads(path.read_text(encoding='utf-8'))
        except ValueError:
            continue
        if isinstance(value, dict) and isinstance(value.get('patches'), list) \
                and any(isinstance(p, dict) and 'before' in p for p in value['patches']):
            yield path, value, value.get('module') or modules.get(path.name)


def scan(modules_root):
    """Rows of (recipe, index, status, module); status 'OK' or a reason."""
    rows = []
    for path, recipe, default_module in recipes():
        texts = {}
        for index, patch in enumerate(recipe['patches']):
            if not isinstance(patch, dict) or 'before' not in patch:
                continue
            module = patch.get('module') or patch.get('file') or default_module
            if not module:
                rows.append((path.name, index, 'NO_MODULE', '-'))
                continue
            if module not in texts:
                target = Path(modules_root) / module
                texts[module] = target.read_text(encoding='utf-8', errors='replace') if target.is_file() else None
            text = texts[module]
            if text is None:
                rows.append((path.name, index, 'MODULE_MISSING', module))
                continue
            count = text.count(patch['before'])
            if count == 1:
                texts[module] = text.replace(patch['before'], patch.get('after', ''))
                rows.append((path.name, index, 'OK', module))
            elif patch.get('after') and patch['after'] in text:
                rows.append((path.name, index, 'ALREADY_PATCHED', module))
            else:
                rows.append((path.name, index, f'ANCHOR_FOUND_{count}_TIMES', module))
    return rows + java_owned(modules_root)


def _java_string(source, name):
    match = re.search(r'\b' + name + r'\s*=\s*"((?:[^"\\]|\\.)*)";', source)
    if not match:
        raise ValueError('JAVA_ANCHOR_SOURCE: ' + name)
    return json.loads('"' + match[1] + '"')


def java_owned(modules_root):
    """Anchors the app applies itself (RuntimeTools): the PDF recipe and the LAN settings patch.

    Neither is a `patches` list, so the loop above never saw them; a core upgrade that moved
    one only failed on the phone's first start (LAN_SETTINGS_PATCH_MISMATCH on 0.2.1).
    """
    rows = []
    pdf = json.loads((ASSETS / 'pdf-compat-patch.json').read_text(encoding='utf-8'))
    for key, module in (('mainBefore', pdf['module']), ('workerBefore', pdf['module']),
                        ('resourceBefore', pdf['resourceModule'])):
        target = Path(modules_root) / module
        if not target.is_file():
            rows.append(('pdf-compat-patch.json', key, 'MODULE_MISSING', module))
            continue
        count = target.read_text(encoding='utf-8', errors='replace').count(pdf[key])
        rows.append(('pdf-compat-patch.json', key, 'OK' if count == 1 else f'ANCHOR_FOUND_{count}_TIMES', module))
    java = (ROOT / 'app/src/main/java/com/deepseekharness/app/util/LanSettingsPatch.java').read_text(encoding='utf-8')
    before, after, host_only = (_java_string(java, name) for name in ('BEFORE', 'AFTER', 'HOST_ONLY_MIRROR'))
    module = '@deepseek-ai/dsh-client-ui-settings/lib/client.js'
    target = Path(modules_root) / module
    if not target.is_file():
        return rows + [('LanSettingsPatch.java', 0, 'MODULE_MISSING', module)]
    text = target.read_text(encoding='utf-8', errors='replace')
    legacy = text.count(before) == 1 or (text.count(after) == 1 and before not in text)
    modern = before not in text and after not in text and text.count(host_only) == 1 \
        and 'persistence === "memory"' not in text and 'isLoopback ? "host"' not in text
    return rows + [('LanSettingsPatch.java', 0, 'OK' if legacy or modern else 'UNKNOWN_SETTINGS_SHAPE', module)]


def stamp(version):
    """Set dshVersion in every recipe and the registry, keeping each file's formatting."""
    pattern = re.compile(r'("dshVersion"\s*:\s*")[^"]*(")')
    changed = []
    files = [path for path, _, _ in recipes()] + [ASSETS / 'runtime-patches.json']
    for path in files:
        text = path.read_text(encoding='utf-8')
        updated, count = pattern.subn(lambda m: m.group(1) + version + m.group(2), text, count=1)
        if count and updated != text:
            path.write_text(updated, encoding='utf-8')
            changed.append(path.name)
    return changed


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('node_modules', type=Path)
    parser.add_argument('--stamp', action='store_true', help='write the tree version into recipes when all anchors hold')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    version = json.loads((args.node_modules / '@deepseek-ai/dsh/package.json').read_text(encoding='utf-8'))['version']
    rows = scan(args.node_modules)
    broken = [row for row in rows if row[2] != 'OK']
    if args.json:
        print(json.dumps({'dshVersion': version, 'anchors': len(rows), 'broken': broken}, ensure_ascii=False, indent=2))
    else:
        print(f'DSH {version}: {len(rows) - len(broken)}/{len(rows)} anchors hold in {len({r[0] for r in rows})} recipes')
        for name, index, status, module in broken:
            print(f'  {name} #{index}: {status}  ({module})')
    if broken:
        raise SystemExit(1)
    if args.stamp:
        for name in stamp(version):
            print('stamped', name)


if __name__ == '__main__':
    main()
