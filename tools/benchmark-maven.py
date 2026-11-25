#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold
# SPDX-License-Identifier: Apache-2.0
"""Measure the existing Maven path for a view edit in an isolated checkout."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from release import WORKSPACE_CASES


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path("examples/document-workspace-scenarios")
SOURCE = Path("src/main/vernac/example/documents/documents.vernac")
REPORT = Path("target/surefire-reports/TEST-org.vernac.generated.VernacScenariosTest.xml")
MAVEN_ARGS = [
    "-B", "-ntp", "-pl", str(FIXTURE), "-am",
    "-Dtest=VernacScenariosTest", "-Dsurefire.failIfNoSpecifiedTests=false",
]
EXPECTED_CASES = {("example.documents." + case).replace(".", "$d")
                  for case in WORKSPACE_CASES["default"]}
EXPECTED_FAILURE = "example.documents.EditorView.authorizedActionIsVisible".replace(".", "$d")


def output(*command, cwd=ROOT):
    return subprocess.check_output(command, cwd=cwd, text=True,
                                   stderr=subprocess.STDOUT).strip()


def read_cases(report):
    cases = []
    for case in ET.parse(report).getroot().findall("testcase"):
        status = next((tag for tag in ("error", "skipped", "failure")
                       if case.find(tag) is not None), "passed")
        cases.append({"name": case.attrib["name"], "status": status,
                      "seconds": float(case.attrib["time"])})
    return cases


def verifies(sample, failure):
    expected = {name: "failure" if name == failure else "passed" for name in EXPECTED_CASES}
    actual = {case["name"]: case["status"] for case in sample["cases"]}
    expected_exit = 1 if failure else 0
    return (sample["exit_code"] == expected_exit
            and len(sample["cases"]) == len(expected) and actual == expected)


def measure(worktree, evidence, command, label, *, clean=False, failure=None):
    report = worktree / FIXTURE / REPORT
    report.unlink(missing_ok=True)
    goals = ["clean", "test"] if clean else ["test"]
    with (evidence / f"{label}.log").open("wb") as log:
        start = time.perf_counter()
        result = subprocess.run([*command, *MAVEN_ARGS, *goals], cwd=worktree,
                                stdout=log, stderr=subprocess.STDOUT)
        seconds = time.perf_counter() - start
    cases = read_cases(report) if report.is_file() else []
    if report.is_file():
        (evidence / f"{label}.xml").write_bytes(report.read_bytes())
    sample = {
        "label": label, "seconds": seconds, "exit_code": result.returncode,
        "expected_failure": failure, "cases": cases,
    }
    sample["verified"] = verifies(sample, failure)
    return sample


def run(worktree, evidence, command, metadata):
    source = worktree / FIXTURE / SOURCE
    original = source.read_bytes()
    # Replace both occurrences because the scenario checks the rendered response body.
    old_label = b'"Document removed"'
    if original.count(old_label) != 2:
        raise ValueError("Expected the reference DocumentRemoved page label and heading")
    edited = original.replace(old_label, b'"Removed from workspace"')
    metadata["fixture_files"] = [
        {"path": path.relative_to(worktree).as_posix(), "bytes": path.stat().st_size,
         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in sorted((worktree / FIXTURE / "src").rglob("*")) if path.is_file()
    ]
    metadata["samples"] = []
    for label, content, clean, failure in (
        ("clean", original, True, None),
        ("warm", original, False, None),
        ("view-edit", edited, False, EXPECTED_FAILURE),
        ("view-restore", original, False, None),
    ):
        if source.read_bytes() != content:
            source.write_bytes(content)
        sample = measure(worktree, evidence, command, label, clean=clean, failure=failure)
        metadata["samples"].append(sample)
        (evidence / "benchmark.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        print(f"{label}: {sample['seconds']:.3f}s ({'verified' if sample['verified'] else 'unexpected result'})",
              flush=True)
        if not sample["verified"]:
            return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", default="cac827eb45b603092f3c0ff549872589fa9c2e04")
    parser.add_argument("--maven", default="mvn", help="Maven executable")
    parser.add_argument("--settings", type=Path, help="Optional Maven settings file")
    parser.add_argument("--dependency-cache", required=True, help="Describe the initial Maven cache state")
    parser.add_argument("--output", type=Path, required=True, help="New directory for JSON, logs and XML")
    args = parser.parse_args()

    evidence = args.output.resolve()
    command = [str(Path(args.maven).resolve()) if Path(args.maven).is_file() else args.maven]
    if args.settings:
        command.extend(("-s", str(args.settings.resolve(strict=True))))
    revision = output("git", "rev-parse", "--verify", "--end-of-options", args.revision + "^{commit}")
    metadata = {
        "revision": revision,
        "platform": platform.platform(),
        "processor": platform.processor(),
        "logical_cpus": os.cpu_count(),
        "maven_and_jdk": output(*command, "--version"),
        "profile": "default",
        "dependency_cache": args.dependency_cache,
        "maven_args": MAVEN_ARGS,
    }
    evidence.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix="vernac-maven-baseline-") as directory:
        worktree = Path(directory) / "checkout"
        output("git", "worktree", "add", "--detach", str(worktree), revision)
        try:
            return run(worktree, evidence, command, metadata)
        finally:
            output("git", "worktree", "remove", "--force", str(worktree))


if __name__ == "__main__":
    raise SystemExit(main())
