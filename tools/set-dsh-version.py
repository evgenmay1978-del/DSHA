#!/usr/bin/env python3
"""Start a DSH core upgrade: move the lock and every plain version copy to a new version.

  python3 tools/set-dsh-version.py 0.2.2-rc.1       # rewrite lock deps + mechanical copies
  python3 tools/set-dsh-version.py --check          # list copies that disagree with the lock

The script does not touch patch recipes or generated proofs; it prints the remaining steps.
"""
import argparse
import json
import re
from pathlib import Path

import dsh_version_sites as sites

ROOT = Path(__file__).resolve().parents[1]
NEXT_STEPS = '''Next steps (each one fails loudly when something needs review):
  1. npm install --package-lock-only --os=linux --cpu=arm64 --libc=glibc --ignore-scripts \\
       --no-audit --no-fund --prefix tools/dsh-runtime     (fix peer conflicts it reports)
  2. python3 tools/prepare-dsh-runtime.py                   (npm ci + build dsh-runtime.bin;
       builder-owned anchors fail here with the module name)
  3. python3 tools/scan-runtime-patches.py app/build/locked-dsh-runtime/node_modules
       → port every anchor it reports, then rerun with --stamp
  4. python3 tools/prepare-tool-call-identity.py app/build/locked-dsh-runtime/node_modules
  5. review tools/prepare-runtime-descriptor.py READ_COMPATIBILITY for the new version
  6. regenerate: client-seed-proof.json (new frontend bundle), prepare-backup-assets.py --write,
     generate-third-party-notices.py, prepare-runtime-descriptor.py --write
  7. python3 tools/set-dsh-version.py --check  must print nothing; then run host tests'''


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('version', nargs='?')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check == bool(args.version):
        parser.error('pass a version or --check')
    if args.check:
        rows = sites.check(ROOT)
        for site, value, kind in rows:
            print(f'{kind:10} {site}: {value} (lock: {sites.locked(ROOT)})')
        raise SystemExit(1 if rows else 0)
    if not re.fullmatch(r'\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?', args.version):
        parser.error('not a semver version: ' + args.version)
    lock = ROOT / sites.LOCK
    old = sites.locked(ROOT)
    package = json.loads(lock.read_text(encoding='utf-8'))
    moved = 0
    for name, value in package['dependencies'].items():
        if name.startswith('@deepseek-ai/') and value == old:
            package['dependencies'][name] = args.version
            moved += 1
    if package.get('version') == old:
        package['version'] = args.version
    lock.write_text(json.dumps(package, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    changed = sites.set_mechanical(ROOT, args.version)
    print(f'lock: {moved} @deepseek-ai packages {old} -> {args.version}')
    for name in changed:
        print('updated', name)
    print(NEXT_STEPS)


if __name__ == '__main__':
    main()
