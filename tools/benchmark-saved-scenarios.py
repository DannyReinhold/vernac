#!/usr/bin/env python3
"""Measure saved Document Workspace edits through a warm Vernac daemon."""

import argparse
import hashlib
import json
from itertools import count
import math
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent.parent
EXAMPLE = ROOT / "examples/document-workspace-scenarios"
SCENARIOS = Path("src/test/vernac/example/documents/views.vernac")
DOCUMENTS = Path("src/main/vernac/example/documents/documents.vernac")
EDITOR = "example.documents.EditorView.authorizedActionIsVisible"
READER = "example.documents.ReaderView.hiddenActionAndDirectPostAreDenied"
ASSERTION = "expect (remaining == DocumentPresence ABSENT);"
DELETE = "documentStore delete: documentId."
VIEW_BINDING = "path id <- document.documentId.value;"
REMOVED_PAGE = 'page "Document removed"'
WORKLOADS = {"mvc-assertion": (SCENARIOS, ASSERTION), "delete-behavior": (DOCUMENTS, DELETE),
             "view-diagnostics": (DOCUMENTS, VIEW_BINDING), "view-render": (DOCUMENTS, REMOVED_PAGE)}
REPORTS = (
    ROOT / "vernac-compiler/target/surefire-reports/TEST-org.vernac.compiler.pipeline.ScenarioDaemonTest.xml",
    EXAMPLE / "target/surefire-reports/TEST-org.vernac.generated.VernacScenariosTest.xml",
)


def classpath():
    paths = {}
    for report in REPORTS:
        prop = ElementTree.parse(report).find("properties/property[@name='java.class.path']")
        if prop is None or not prop.get("value"):
            raise ValueError(f"Missing Java classpath in {report}")
        paths.update(dict.fromkeys(prop.get("value").split(os.pathsep)))
    return os.pathsep.join(paths)


def send(daemon, request, *, notification=False):
    daemon.stdin.write(json.dumps(request) + "\n")
    daemon.stdin.flush()
    if notification:
        return None
    reply = json.loads(daemon.stdout.readline())
    if reply.get("id") != request["id"] or reply.get("error") or "result" not in reply:
        raise ValueError(f"Unexpected response: {reply}")
    return reply["result"]


class McpClient:
    def __init__(self, daemon):
        self.daemon = daemon
        self.ids = count(1)
        reply = self.request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                             "clientInfo": {"name": "vernac-benchmark", "version": "1"}})
        if reply.get("protocolVersion") != "2025-11-25":
            raise ValueError(f"Unexpected MCP protocol version: {reply}")
        send(self.daemon, {"jsonrpc": "2.0", "method": "notifications/initialized"}, notification=True)

    def request(self, method, params):
        return send(self.daemon, {"jsonrpc": "2.0", "id": next(self.ids), "method": method, "params": params})

    def tool(self, name, **arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError") or "structuredContent" not in result:
            raise ValueError(f"Unexpected MCP tool result: {result}")
        return result["structuredContent"]

    def background_result(self, revision):
        run = self.tool("start_affected_saved", revision=revision)
        deadline = time.monotonic() + 30
        while True:
            status = self.tool("run_status", runId=run["runId"])
            if (status.get("runId"), status.get("revision")) != (run["runId"], run["revision"]):
                raise ValueError(f"Unexpected background run status: {status}")
            if status["state"] == "COMPLETED":
                return status["result"]
            if status["state"] != "RUNNING":
                raise ValueError(f"Unexpected background run state: {status}")
            if time.monotonic() >= deadline:
                raise ValueError(f"Timed out waiting for background run {run['runId']}")
            time.sleep(0.01)


def measure_response(request, workload, edit, start):
    diagnostics = workload == "view-diagnostics"
    result = request(max(0, edit - 1), diagnostics)
    elapsed = (time.perf_counter_ns() - start) / 1_000_000
    if result.get("revision") != edit:
        raise ValueError(f"Unexpected revision for edit {edit}: {result}")
    if diagnostics:
        errors = [item for item in result["diagnostics"] if item["severity"] == "ERROR"]
        invalid = edit != 0 and edit % 5 == 0
        if result["valid"] != (not invalid) or bool(errors) != invalid:
            raise ValueError(f"Unexpected diagnostics for edit {edit}: {result}")
        if invalid and not any(
                f"document.missingBinding{edit}.value" in item["message"]
                and item["location"]["sourceName"].replace("\\", "/").endswith(DOCUMENTS.as_posix())
                and item["location"]["line"] > 0 for item in errors):
            raise ValueError(f"Missing typed view binding diagnostic for edit {edit}: {result}")
        return {"edit": edit, "ms": elapsed, "valid": result["valid"], "diagnostics": result["diagnostics"]}
    expected = {EDITOR: "FAILED" if edit and edit % 5 == 0 else "PASSED", READER: "PASSED"}
    selected = result.get("selected", [])
    if (result.get("outcomes") != expected
            or sorted(item["id"] for item in selected) != sorted(expected)):
        raise ValueError(f"Unexpected response for edit {edit}: {result}")
    return {"edit": edit, "ms": elapsed, "selected": selected, "outcomes": expected}


def edit_source(workload, original, original_text, edit):
    if workload == "view-render":
        message = "Archived document" if edit % 5 == 0 else "Document removed"
        return original.replace('"Document removed"', f'"{message} {edit}"')
    if workload == "view-diagnostics":
        updated = original.replace('heading "Document workspace";', f'heading "Document workspace {edit}";')
        if edit % 5 == 0:
            return updated.replace(original_text, f"path id <- document.missingBinding{edit}.value;")
        return updated
    expected_sum = edit + (2 if edit % 5 == 0 else 1)
    predicate = f"{edit} + 1 == {expected_sum}"
    if workload == "mvc-assertion":
        replacement = f"expect (remaining == DocumentPresence ABSENT && {predicate});"
    else:
        replacement = f"require {predicate} else BenchmarkRejected.\n        {DELETE}"
    return original.replace(original_text, replacement)


def measure_session(java, cp, workload, warmups, edits, transport, launcher):
    relative_source, original_text = WORKLOADS[workload]
    with tempfile.TemporaryDirectory(prefix="vernac-mvc-benchmark-") as scratch:
        project = Path(scratch)
        shutil.copytree(EXAMPLE / "src", project / "src")
        for directory in ("classes", "test-classes"):
            shutil.copytree(EXAMPLE / "target" / directory, project / "target" / directory)
        source = project / relative_source
        original = source.read_text(encoding="utf-8")
        if original.count(original_text) != 1:
            raise ValueError(f"Expected one benchmark edit site in {relative_source}")
        environment = None
        if transport == "daemon":
            command = [java, "-cp", cp, "org.vernac.compiler.pipeline.ScenarioDaemonLauncher", str(project), "mvc"]
        else:
            command = [str(launcher), "mcp", str(project), "mvc"]
            environment = os.environ | {"VERNAC_PROJECT_CLASSPATH": cp, "JAVA_HOME": str(Path(java).parent.parent)}
        with tempfile.TemporaryFile(mode="w+t", encoding="utf-8") as daemon_log:
            start = time.perf_counter_ns()
            with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=daemon_log, text=True, encoding="utf-8", env=environment) as daemon:
                try:
                    if transport == "daemon":
                        def request(revision, diagnostics):
                            return send(daemon, {"id": revision, "op": "checkSaved" if diagnostics else "runSaved",
                                                 "revision": revision})
                    else:
                        client = McpClient(daemon)

                        def request(revision, diagnostics):
                            if transport == "mcp-background":
                                return client.background_result(revision)
                            return client.tool("check_saved" if diagnostics else "test_affected_saved",
                                               revision=revision)
                    cold = measure_response(request, workload, 0, start)
                    samples = []
                    for edit in range(1, warmups + edits + 1):
                        updated = edit_source(workload, original, original_text, edit)
                        start = time.perf_counter_ns()
                        source.write_text(updated, encoding="utf-8")
                        sample = measure_response(request, workload, edit, start)
                        if edit > warmups:
                            samples.append(sample)
                    daemon.stdin.close()
                    daemon.wait(timeout=10)
                    if daemon.returncode != 0:
                        raise ValueError(f"Daemon exited with status {daemon.returncode}")
                except (ValueError, OSError, subprocess.TimeoutExpired):
                    daemon.kill()
                    daemon.wait()
                    daemon_log.seek(0)
                    sys.stderr.write(daemon_log.read())
                    raise
        times = sorted(sample["ms"] for sample in samples)
        return {"cold": cold, "samples": samples, "p50_ms": statistics.median(times),
                "p95_ms": times[math.ceil(.95 * edits) - 1]}


def positive_int(value):
    count = int(value)
    if count < 1:
        raise argparse.ArgumentTypeError("expected a positive count")
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java", help="path to the JDK 21 launcher")
    parser.add_argument("--workload", choices=WORKLOADS, default="mvc-assertion")
    parser.add_argument("--sessions", type=positive_int, default=3)
    parser.add_argument("--warmups", type=positive_int, default=20)
    parser.add_argument("--edits", type=positive_int, default=100)
    parser.add_argument("--transport", choices=("daemon", "mcp", "mcp-background"), default="daemon")
    parser.add_argument("--launcher", type=Path, help="path to the extracted Vernac launcher for MCP")
    parser.add_argument("--max-p95-ms", type=positive_int, help="return status 1 if any session misses this budget")
    args = parser.parse_args()
    uses_mcp = args.transport != "daemon"
    if uses_mcp != (args.launcher is not None):
        parser.error("--launcher is required for MCP and applies only to MCP transports")
    if args.transport == "mcp-background" and args.workload == "view-diagnostics":
        parser.error("Background MCP runs require a test workload")
    launcher = args.launcher.resolve() if args.launcher else None

    try:
        java = shutil.which(args.java)
        if java is None:
            raise ValueError(f"Java launcher not found: {args.java}")
        java = str(Path(java).resolve())
        cp = classpath()
        jdk = subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr.strip()
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        report = {"revision": revision, "os": platform.platform(), "machine": platform.machine(),
                  "logical_cpus": os.cpu_count(),
                  "available_cpus": len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
                  "jdk": jdk, "workload": args.workload, "source": WORKLOADS[args.workload][0].as_posix(),
                  "profile": "mvc", "transport": args.transport,
                  "warmups": args.warmups, "edits_per_session": args.edits, "sessions": []}
        if launcher is not None:
            libraries = list(launcher.parent.parent.glob("lib/vernac-compiler-*.jar"))
            if len(libraries) != 1:
                raise ValueError("Expected one compiler JAR beside the extracted launcher")
            with libraries[0].open("rb") as compiler:
                report["cli_compiler_sha256"] = hashlib.file_digest(compiler, "sha256").hexdigest()
        if args.transport == "mcp-background":
            report["poll_interval_ms"] = 10
        for index in range(1, args.sessions + 1):
            result = measure_session(java, cp, args.workload, args.warmups, args.edits, args.transport, launcher)
            report["sessions"].append(result)
            print(f"Session {index}: p50 {result['p50_ms']:.1f} ms, p95 {result['p95_ms']:.1f} ms", file=sys.stderr)
    except (OSError, ValueError, ElementTree.ParseError, subprocess.SubprocessError) as failure:
        print(f"Benchmark failed: {failure}", file=sys.stderr)
        return 2

    print(json.dumps(report, indent=2))
    return int(args.max_p95_ms is not None
               and any(session["p95_ms"] > args.max_p95_ms for session in report["sessions"]))


if __name__ == "__main__":
    sys.exit(main())
