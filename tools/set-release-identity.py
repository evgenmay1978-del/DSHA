#!/usr/bin/env python3
"""Pin ci/release-identity.json to the owner's own signing key.

The fork cannot be signed with the upstream certificate, so it installs as its own package
(app/build.gradle applicationId) next to the old app. After creating a key once:

  keytool -genkeypair -keystore dsha-fork.jks -alias dsha -keyalg RSA -keysize 4096 \\
          -validity 36500 -dname "CN=DSHA fork"

run this with the same keystore/alias; it writes the certificate SHA-256 that
tools/sign-release-apks.py and the release checks require, and the package name from Gradle.
The keystore itself never enters the repository.

  python3 tools/set-release-identity.py --keystore dsha-fork.jks --alias dsha
  (keytool asks for the store password; DSHA_KEYSTORE_PASSWORD is used when set)
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IDENTITY = ROOT / 'ci/release-identity.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--keytool', default=shutil.which('keytool') or 'keytool')
    args = parser.parse_args()
    command = [args.keytool, '-exportcert', '-keystore', str(args.keystore), '-alias', args.alias]
    if os.environ.get('DSHA_KEYSTORE_PASSWORD'):
        command += ['-storepass:env', 'DSHA_KEYSTORE_PASSWORD']
    certificate = subprocess.run(command, check=True, stdout=subprocess.PIPE).stdout
    if not certificate:
        raise SystemExit('RELEASE_CERTIFICATE_EMPTY')
    application = re.search(r'applicationId\s+"([^"]+)"', (ROOT / 'app/build.gradle').read_text(encoding='utf-8'))
    identity = json.loads(IDENTITY.read_text(encoding='utf-8'))
    identity['packageName'] = application[1]
    identity['certificateSha256'] = hashlib.sha256(certificate).hexdigest()
    IDENTITY.write_text('{\n  "schema": 1,\n  "packageName": "%s",\n  "certificateSha256": "%s",\n  "schemes": ["v1", "v2", "v3"]\n}\n'
                        % (identity['packageName'], identity['certificateSha256']), encoding='utf-8')
    print('release identity:', identity['packageName'], identity['certificateSha256'])


if __name__ == '__main__':
    main()
