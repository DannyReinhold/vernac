#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
"""Run immutable version fixtures through the current independent SaaS application."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'examples/saas-document-workspace'
BASELINES = ROOT / 'tests/upgrades'
spec = importlib.util.spec_from_file_location('upgrade_snapshot', APP / 'upgrade_snapshot.py')
snapshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot)


def validate_report(path):
    report = ET.parse(path).getroot()
    suites = [report] if report.tag == 'testsuite' else list(report.iter('testsuite'))
    cases = [c for suite in suites for c in suite.findall('testcase')]
    names = [c.get('name', '').split()[0] for c in cases]
    if (len(cases) != 2 or set(names) != {'U01', 'U02'}
            or any(int(s.get(k, '0')) for s in [report, *suites] for k in ('failures', 'errors', 'skipped'))
            or any(c.find(k) is not None for c in cases for k in ('failure', 'error', 'skipped'))):
        raise ValueError('Incomplete or unsuccessful upgrade evidence')
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, help='Create a NEW synthetic candidate directory; never overwrites')
    args = parser.parse_args()
    report_dir = APP / 'target/upgrade-reports'
    report_dir.mkdir(parents=True, exist_ok=True)
    report = report_dir / 'TEST-UpgradeBaseline.xml'
    combined = ET.Element('testsuite', name='UpgradeBaseline', tests='0', failures='0', errors='0', skipped='0')
    if args.capture:
        if args.capture.exists():
            parser.error('Capture directory already exists; existing baselines are immutable')
        env = dict(os.environ, VERNAC_UPGRADE_CAPTURE=str(args.capture.resolve()))
        env.pop('VERNAC_UPGRADE_BASELINE', None)
        report.unlink(missing_ok=True)
        subprocess.run(['npx', 'playwright', 'test', '-c', 'upgrade.config.mjs'], cwd=APP/'browser', env=env, check=True)
        snapshot.read(args.capture)
        return
    registry = json.loads((BASELINES / 'registry.json').read_text())
    versions = registry['baselines']
    if not versions or len(versions) != len(set(versions)) or registry['minimum_supported'] != '0.6.0':
        raise ValueError('Invalid upgrade baseline registry')
    properties = ET.SubElement(combined, 'properties')
    total = 0
    for version in versions:
        if version != Path(version).name:
            raise ValueError('Unsafe baseline registry path')
        directory = BASELINES / version
        manifest = snapshot.read(directory)
        if manifest['version'] != version:
            raise ValueError('Baseline registry/version mismatch')
        ET.SubElement(properties, 'property', name='baseline.'+version, value=snapshot.digest(directory/'manifest.json'))
        if manifest['state'] != 'FROZEN' and os.environ.get('VERNAC_REQUIRE_FROZEN_BASELINES') == 'true':
            raise ValueError('Release requires a frozen baseline')
        report.unlink(missing_ok=True)
        env = dict(os.environ, VERNAC_UPGRADE_BASELINE=str(directory))
        env.pop('VERNAC_UPGRADE_CAPTURE', None)
        subprocess.run(['npx', 'playwright', 'test', '-c', 'upgrade.config.mjs'], cwd=APP/'browser', env=env, check=True)
        for case in validate_report(report):
            case.set('name', version + ' ' + case.get('name', ''))
            combined.append(case)
            total += 1
    combined.set('tests', str(total))
    ET.ElementTree(combined).write(report, encoding='utf-8', xml_declaration=True)
    print('All registered upgrade baselines passed without skips.')


if __name__ == '__main__':
    main()
