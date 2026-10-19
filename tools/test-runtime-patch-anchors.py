#!/usr/bin/env python3
"""Every runtime patch recipe still applies to the locked DSH tree, and every recipe is
stamped with that tree's version. A core upgrade that moves an anchor fails here with the
full list (recipe, patch index, module) instead of one error at a time on the device."""
import importlib.util
import json
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('scan_runtime_patches', ROOT / 'tools/scan-runtime-patches.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


def raw_modules():
    selected = os.environ.get('DSHA_TEST_RUNTIME')
    if not selected:
        pointer = ROOT / 'app/build/test-runtimes/current.json'
        selected = json.loads(pointer.read_text(encoding='utf-8'))['raw']
    return Path(selected) / 'node_modules'


class RuntimePatchAnchorsTest(unittest.TestCase):
    def test_every_anchor_holds_on_the_locked_tree(self):
        modules = raw_modules()
        version = json.loads((modules / '@deepseek-ai/dsh/package.json').read_text(encoding='utf-8'))['version']
        locked = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
        self.assertEqual(locked, version, 'test runtime must be the locked DSH')
        rows = scanner.scan(modules)
        self.assertGreater(len(rows), 50, 'scanner found too few anchors; recipe discovery broke')
        broken = [row for row in rows if row[2] != 'OK']
        self.assertEqual([], broken, 'port these anchors (tools/scan-runtime-patches.py shows the same list)')

    def test_recipes_are_stamped_with_the_locked_version(self):
        locked = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
        stale = [path.name for path, recipe, _ in scanner.recipes() if recipe.get('dshVersion') not in (None, locked)]
        self.assertEqual([], stale)

    def test_scanner_reports_a_moved_anchor(self):
        # A synthetic tree where one recipe's module exists but its anchor does not.
        import tempfile
        path, recipe, module = next((p, r, m) for p, r, m in scanner.recipes() if m)
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / module
            target.parent.mkdir(parents=True)
            target.write_text('// upstream rewrote this module\n', encoding='utf-8')
            rows = [row for row in scanner.scan(folder) if row[0] == path.name]
            self.assertTrue(rows)
            self.assertTrue(all(row[2].startswith('ANCHOR_FOUND_0') for row in rows if row[3] == module))


if __name__ == '__main__':
    unittest.main()
