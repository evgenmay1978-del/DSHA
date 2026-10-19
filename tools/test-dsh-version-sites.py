#!/usr/bin/env python3
"""Every copy of the bundled DSH version agrees with tools/dsh-runtime/package.json,
and tools/set-dsh-version.py rewrites the plain copies without touching formatting."""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dsh_version_sites as sites  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


class DshVersionSitesTest(unittest.TestCase):
    def test_repository_copies_match_the_lock(self):
        mismatched = sites.check(ROOT)
        self.assertEqual([], mismatched, 'run python3 tools/set-dsh-version.py --check and follow its steps')

    def test_every_kind_of_site_is_found(self):
        kinds = {kind for _, _, kind in sites.sites(ROOT)}
        self.assertEqual({'mechanical', 'recipe', 'generated'}, kinds)
        names = [site for site, _, _ in sites.sites(ROOT)]
        for required in (sites.CONSTANTS, sites.GRADLE + ':versionName', sites.MIGRATION,
                         sites.ASSETS + '/runtime-patches.json', sites.ASSETS + '/runtime-descriptor.json'):
            self.assertIn(required, names)
        self.assertTrue(any(':@deepseek-ai/dsh' in name for name in names), 'built-in plugin peer pins')

    def test_recovery_test_install_matches_the_emergency_runtime(self):
        # The emergency runtime does not follow core upgrades; its host-test install must stay on its version.
        locked = json.loads((ROOT / 'tools/recovery-runtime/lock.json').read_text(encoding='utf-8'))['dshVersion']
        install = ROOT / 'tools/recovery-runtime/test-install'
        package = json.loads((install / 'package.json').read_text(encoding='utf-8'))
        lock = json.loads((install / 'package-lock.json').read_text(encoding='utf-8'))
        self.assertEqual(locked, package['dependencies']['@deepseek-ai/dsh'])
        self.assertEqual(locked, lock['packages']['node_modules/@deepseek-ai/dsh']['version'])

    def test_set_mechanical_rewrites_copies_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for relative in (sites.LOCK, sites.CONSTANTS, sites.GRADLE, sites.MIGRATION):
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, root / relative)
            shutil.copytree(ROOT / sites.PLUGINS, root / sites.PLUGINS)
            changed = sites.set_mechanical(root, '9.8.7-rc.6')
            self.assertIn(sites.CONSTANTS, changed)
            self.assertIn(sites.GRADLE, changed)
            self.assertIn(sites.MIGRATION, changed)
            self.assertTrue(any(name.endswith('/package.json') for name in changed))
            self.assertEqual([], sites.set_mechanical(root, '9.8.7-rc.6'))
            self.assertIn('DSH_VERSION = "9.8.7-rc.6";', (root / sites.CONSTANTS).read_text(encoding='utf-8'))
            self.assertIn('-dsh9.8.7-rc.6"', (root / sites.GRADLE).read_text(encoding='utf-8'))
            self.assertIn("VERSION = '9.8.7-rc.6'", (root / sites.MIGRATION).read_text(encoding='utf-8'))
            for manifest in (root / sites.PLUGINS).glob('*/package.json'):
                original = (ROOT / manifest.relative_to(root)).read_text(encoding='utf-8')
                updated = manifest.read_text(encoding='utf-8')
                # Only version strings move; keys, ranges and layout stay byte-identical.
                self.assertEqual(original.replace(sites.locked(ROOT), '9.8.7-rc.6'), updated)
                for name, value in (json.loads(updated).get('peerDependencies') or {}).items():
                    if name.startswith('@deepseek-ai/dsh') and value[0].isdigit():
                        self.assertEqual('9.8.7-rc.6', value)


if __name__ == '__main__':
    unittest.main()
