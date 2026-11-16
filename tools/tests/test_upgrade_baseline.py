"""Reject corrupted fixtures, rewritten migrations and incomplete restore evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded

snapshot = module('snapshot_test', ROOT/'examples/saas-document-workspace/upgrade_snapshot.py')
runner = module('upgrade_runner_test', ROOT/'tools/verify-upgrades.py')

class UpgradeBaselineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        original = snapshot.ROOT
        snapshot.ROOT = self.root
        self.addCleanup(setattr, snapshot, 'ROOT', original)
        self.baseline = self.root/'baseline'
        self.baseline.mkdir()
        for name in snapshot.FILES:
            (self.baseline/name).write_text('synthetic')
        self.migration = self.root/'migration.sql'
        self.migration.write_text('CREATE TABLE original (id uuid PRIMARY KEY);')
        self.row = dict(schema='module', installed_rank=1, version='1', description='initial',
                        type='SQL', script='V1__initial.sql', checksum=42, success=True)
        self.manifest = dict(format=1, version='0.6.0', state='CANDIDATE', synthetic_only=True,
                             source_sha='a'*40, files={n:snapshot.digest(self.baseline/n) for n in snapshot.FILES},
                             migration_files={'migration.sql':snapshot.digest(self.migration)}, history=[self.row])
        self.write_manifest()

    def write_manifest(self):
        (self.baseline/'manifest.json').write_text(json.dumps(self.manifest))

    def test_intact_candidate_loads_and_historical_checksums_are_preserved(self):
        snapshot.read(self.baseline)
        snapshot.check_history([self.row], [self.row, dict(self.row, installed_rank=2, version='2')])
        with self.assertRaisesRegex(ValueError, 'history changed'):
            snapshot.check_history([self.row], [dict(self.row, checksum=43)])
        with self.assertRaisesRegex(ValueError, 'history changed'):
            snapshot.check_history([self.row], [])
        with self.assertRaisesRegex(ValueError, 'Failed migration'):
            snapshot.check_history([self.row], [self.row, dict(self.row, installed_rank=2, success=False)])

    def test_corrupt_or_missing_dump_cannot_reach_restore(self):
        (self.baseline/'database.sql').write_text('modified dump')
        with self.assertRaisesRegex(ValueError, 'file changed'):
            snapshot.read(self.baseline)
        (self.baseline/'database.sql').unlink()
        with self.assertRaisesRegex(ValueError, 'file changed'):
            snapshot.read(self.baseline)

    def test_rewriting_or_removing_an_old_migration_is_rejected(self):
        self.migration.write_text('rewritten history')
        with self.assertRaisesRegex(ValueError, 'Historical migration'):
            snapshot.read(self.baseline)
        self.migration.unlink()
        with self.assertRaisesRegex(ValueError, 'Historical migration'):
            snapshot.read(self.baseline)

    def test_unsafe_inventory_non_synthetic_and_failed_history_are_rejected(self):
        for updates in ({'migration_files':{'../escape.sql':'a'*64}}, {'synthetic_only':False},
                        {'history':[]}, {'history':[dict(self.row, success=False)]},
                        {'files':{'../escape.sql':'a'*64}}):
            original = dict(self.manifest)
            self.manifest.update(updates)
            self.write_manifest()
            with self.assertRaises(ValueError):snapshot.read(self.baseline)
            self.manifest = original

    def test_capture_never_overwrites_even_a_candidate(self):
        with self.assertRaises(FileExistsError):
            snapshot.capture(self.baseline, 'unused', {}, 'unused', {}, {})
        self.assertEqual((self.baseline/'database.sql').read_text(), 'synthetic')

    def test_report_requires_every_named_case_without_skips(self):
        p = self.root/'report.xml'
        good = '<testsuites><testsuite tests="2" failures="0" errors="0" skipped="0"><testcase name="U01 retained"/><testcase name="U02 recovery"/></testsuite></testsuites>'
        p.write_text(good)
        self.assertEqual(len(runner.validate_report(p)), 2)
        for bad in (good.replace('U02', 'U01'), good.replace('skipped="0"', 'skipped="1"'),
                    good.replace('<testcase name="U02 recovery"/>', ''),
                    good.replace('<testcase name="U02 recovery"/>', '<testcase name="U02 recovery"><failure/></testcase>')):
            p.write_text(bad)
            with self.assertRaises(ValueError):runner.validate_report(p)

if __name__ == '__main__':unittest.main()
