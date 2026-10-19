#!/usr/bin/env python3
"""Recover the generated build inputs that git does not carry from an installed/released APK.

The Ubuntu base rootfs and the preinstalled tools archive do not change with DSH upgrades
(base10, split-runtime-v1), but their builders need network mirrors and the original
pre-optimisation rootfs, which only exists on the machine that first built them. An APK
already carries both exact archives, so this tool extracts them and accepts each one only if
it matches what the repository pins:

  offline-rootfs.bin   = runtime-descriptor input digest = recovery lock recovery-rootfs.bin,
                         and its dpkg status = tools/ubuntu-tools/packages.lock.json baseStatusSha256.
                         (prepare-standard-assets' rootfs filter is idempotent on this archive.)
  ubuntu-tools.bin     = runtime-descriptor input digest = committed ubuntu-tools.manifest.json,
                         whose package-lock digest is the current lock; its dpkg status matches.
  ubuntu-tools.inputs.json is then written exactly as tools/prepare-ubuntu-tools.py writes it.

  python3 tools/import-apk-assets.py path/to/dsha.apk [--recovery]
  --recovery also copies the rootfs to tools/recovery-runtime/archives/recovery-rootfs.bin.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
TOOLS_LOCK = ROOT / 'tools/ubuntu-tools/packages.lock.json'


def sha256_file(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            value.update(chunk)
    return value.hexdigest()


def dpkg_status(archive, mode):
    with tarfile.open(archive, mode) as tar:
        for item in tar:
            if item.name.removeprefix('./') == 'var/lib/dpkg/status':
                return hashlib.sha256(tar.extractfile(item).read()).hexdigest()
    raise ValueError('APK_ASSET_NO_DPKG_STATUS: ' + str(archive))


def extract(apk, name, target):
    with zipfile.ZipFile(apk) as package, package.open('assets/' + name) as source, \
            tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
        shutil.copyfileobj(source, output, 1 << 20)
    return Path(output.name)


def accept(temporary, target, expected, label):
    actual = sha256_file(temporary)
    if actual != expected:
        temporary.unlink()
        raise ValueError(f'APK_ASSET_DIGEST: {label} is {actual}, repository pins {expected}')
    temporary.replace(target)
    print('accepted', label, actual)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--recovery', action='store_true')
    args = parser.parse_args()
    descriptor = json.loads((ASSETS / 'runtime-descriptor.json').read_text(encoding='utf-8'))['inputs']
    recovery = {row['asset']: row['sha256'] for row in json.loads((ROOT / 'tools/recovery-runtime/lock.json').read_text(encoding='utf-8'))['archives']}
    lock = json.loads(TOOLS_LOCK.read_text(encoding='utf-8'))

    rootfs = ASSETS / 'offline-rootfs.bin'
    if descriptor['offline-rootfs.bin'] != recovery['recovery-rootfs.bin']:
        raise ValueError('APK_ASSET_ROOTFS_PINS_DIFFER: descriptor and recovery lock must name the same optimised rootfs')
    temporary = extract(args.apk, 'offline-rootfs.bin', rootfs)
    if dpkg_status(temporary, 'r|gz') != lock['baseStatusSha256']:
        temporary.unlink()
        raise ValueError('APK_ASSET_ROOTFS_STATUS: base dpkg status differs from the tools lock')
    accept(temporary, rootfs, descriptor['offline-rootfs.bin'], 'offline-rootfs.bin')

    manifest = json.loads((ASSETS / 'ubuntu-tools.manifest.json').read_text(encoding='utf-8'))
    if manifest['packageLockSha256'] != sha256_file(TOOLS_LOCK) or manifest['packages'] != len(lock['packages']) \
            or manifest['archiveSha256'] != descriptor['ubuntu-tools.bin']:
        raise ValueError('APK_ASSET_TOOLS_MANIFEST: committed manifest does not describe the current lock/descriptor')
    tools = ASSETS / 'ubuntu-tools.bin'
    temporary = extract(args.apk, 'ubuntu-tools.bin', tools)
    if dpkg_status(temporary, 'r:*') != manifest['statusSha256']:
        temporary.unlink()
        raise ValueError('APK_ASSET_TOOLS_STATUS')
    accept(temporary, tools, descriptor['ubuntu-tools.bin'], 'ubuntu-tools.bin')
    spec = importlib.util.spec_from_file_location('prepare_ubuntu_tools', ROOT / 'tools/prepare-ubuntu-tools.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    rows = lock['packages']
    metadata = {'inputs': builder.inputs(), 'archive_sha256': manifest['archiveSha256'],
                'installed_bytes': sum(int(row['Installed-Size']) * 1024 for row in rows),
                'base_status_sha256': lock['baseStatusSha256'], 'packages': len(rows),
                'layout': 'configured-overlay-v1', 'status_sha256': manifest['statusSha256']}
    (ASSETS / 'ubuntu-tools.inputs.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('wrote ubuntu-tools.inputs.json')

    if args.recovery:
        archives = ROOT / 'tools/recovery-runtime/archives'
        archives.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(rootfs, archives / 'recovery-rootfs.bin')
        print('copied recovery-rootfs.bin')


if __name__ == '__main__':
    main()
