#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
"""Run every independent scenario and reject incomplete, skipped or stale evidence."""
import json,pathlib,subprocess,sys
ROOT=pathlib.Path(__file__).resolve().parent
EXPECTED={f'E{i:02}' for i in range(1,30)}
def validate(report):
    rows=report['results'];ids=[row['id'] for row in rows]
    def require(condition):
        if not condition:raise ValueError('Incomplete SaaS acceptance evidence')
    require(report['status']=='passed' and report.get('globalErrors',0)==0)
    require(len(ids)==len(EXPECTED) and set(ids)==EXPECTED)
    require(len(report['expected'])==len(EXPECTED))
    require(all(row['status']=='passed' and row['errors']==0 for row in rows))
    require({case for row in rows for case in row['usecases']}=={f'S{i:02}' for i in range(1,21)})
def main():
    report=ROOT/'target/acceptance-reports/scenarios.json'
    report.unlink(missing_ok=True)
    result=subprocess.run(['npx','playwright','test'],cwd=ROOT/'browser')
    if result.returncode:return result.returncode
    try:validate(json.loads(report.read_text()))
    except Exception:
        print('SaaS acceptance evidence incomplete or unsuccessful.');return 1
    print('All 29 independent SaaS scenarios passed; S01–S20 represented.');return 0
if __name__=='__main__':sys.exit(main())
