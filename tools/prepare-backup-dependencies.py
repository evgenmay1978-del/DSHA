#!/usr/bin/env python3
"""Stage the locked backup-engine JARs in app/build/backup-dependencies for host tests.

Each JAR comes from the Gradle cache when present, otherwise from Maven Central, and is
accepted only if its SHA-256 equals tools/backup-dependencies.lock.json. Nothing here enters
the APK; Gradle resolves the same coordinates for the build itself."""
import hashlib
import json
import os
import shutil
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'app/build/backup-dependencies'
MAVEN = 'https://repo1.maven.org/maven2'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    lock = json.loads((ROOT / 'tools/backup-dependencies.lock.json').read_text(encoding='utf-8'))
    if lock.get('version') != 1 or not isinstance(lock.get('dependencies'), list):
        raise SystemExit('BACKUP_DEPENDENCY_LOCK_SCHEMA')
    TARGET.mkdir(parents=True, exist_ok=True)
    gradle = Path(os.environ.get('GRADLE_USER_HOME') or Path.home() / '.gradle')
    for row in lock['dependencies']:
        filename = f"{row['name']}-{row['version']}.jar"
        target = TARGET / filename
        if target.is_file() and sha256(target) == row['sha256']:
            print('ok', filename)
            continue
        cached = sorted((gradle / 'caches/modules-2/files-2.1' / row['group'] / row['name'] / row['version']).glob('**/' + filename))
        source = next((path for path in cached if sha256(path) == row['sha256']), None)
        if source is not None:
            shutil.copyfile(source, target)
        else:
            url = f"{MAVEN}/{row['group'].replace('.', '/')}/{row['name']}/{row['version']}/{filename}"
            with tempfile.NamedTemporaryFile(dir=TARGET, delete=False) as stream, urllib.request.urlopen(url, timeout=60) as response:
                shutil.copyfileobj(response, stream)
                temporary = Path(stream.name)
            if sha256(temporary) != row['sha256']:
                temporary.unlink()
                raise SystemExit('BACKUP_DEPENDENCY_SHA256: ' + filename)
            temporary.replace(target)
        print('staged', filename)


if __name__ == '__main__':
    main()
