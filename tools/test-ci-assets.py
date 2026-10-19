#!/usr/bin/env python3
"""The CI input bundle carries exactly the generated files git does not, and every archive in it
has a runtime-descriptor digest, so `ci-assets.py --pack` can accept a complete set."""
import importlib.util
import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ci_assets', ROOT / 'tools/ci-assets.py')
ci_assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci_assets)


class CiAssetsBundleTest(unittest.TestCase):
    def test_every_archive_has_a_descriptor_digest(self):
        inputs = json.loads((ROOT / 'app/src/main/assets/runtime-descriptor.json').read_text(encoding='utf-8'))['inputs']
        missing = [name for name in ci_assets.NAMES if not name.endswith('.inputs.json') and name not in inputs]
        self.assertEqual([], missing, 'verify_sources() would reject these with CI_ASSET_SOURCE_MISMATCH')

    def test_bundle_never_carries_tracked_files(self):
        paths = ['app/src/main/assets/' + name for name in ci_assets.NAMES]
        tracked = subprocess.run(['git', 'ls-files', '--', *paths], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
        self.assertEqual([], tracked)


if __name__ == '__main__':
    unittest.main()
