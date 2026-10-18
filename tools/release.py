#!/usr/bin/env python3
"""Release checks and Marketplace upload. Standard library only; no credentials in output."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

MODULES = ("vernac", "vernac-runtime", "vernac-runtime-json", "vernac-runtime-http",
           "vernac-runtime-jdbc", "vernac-jdbc-spring-boot-starter", "vernac-compiler", "vernac-maven-plugin")
CENTRAL = "https://repo.maven.apache.org/maven2/org/vernac"
MARKETPLACE = "https://plugins.jetbrains.com/api/updates/upload"
PLUGIN_ID = "org.vernac"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
REQUIRED_TESTS = ("AdapterBindingsTest","PortModuleTest", "PortModuleSymbolsTest", "TypeModuleTest", "TypeModuleExecutionTest", "ApplicationDefinitionTest", "ApplicationDefinitionSymbolsTest", "ModuleDefinitionSymbolsTest", "InboundErrorPolicyExecutionTest", "InboundErrorPolicyContractTest", "InboundErrorPolicySymbolsTest", "EntitySchemaPostgresTest", "EventStoragePostgresTest", "EventWorkerPostgresTest",
                  "RepositoryQueryPostgresTest", "ScalarQueryPostgresTest", "ScalarStoragePostgresTest",
                  "RequiredFieldBackfillTest", "SchemaMojoTest", "PluginDistributionIT",
    "DocumentationExamplesTest", "InboundHttpContractTest", "InboundHttpExecutionTest",
    "InboundHttpRuntimeTest", "InboundHttpSymbolsTest", "PaginationTest", "JdbcCursorCodecTest", "PaginationHttpExecutionTest", "JsonPagesTest", "PaginationResultContractTest")

# 0.4.0 acceptance: module contracts/lifecycle, pagination, vocabulary and diagrams.
REQUIRED_TESTS += (
    "PersistentModuleTest", "ModulePersistenceTest", "EventModuleTest",
    "ModulePersistenceMojoTest", "ModuleMigrationPostgresTest", "ModuleCoordinatorTest",
    "PersistentModuleSymbolsTest", "EventModuleSymbolsTest", "PaginationResultSymbolsTest",
    "VocabularyTest", "VocabularySymbolsTest", "DomainDiagramsTest", "DiagramMojoTest",
    "DiagramSelectionTest",
)


# 0.5.0 debugger foundation must execute, including the real JVM/JDI test.
REQUIRED_TESTS += ("BehaviorSourceMapTest", "BehaviorDebugExecutionTest", "VernacRuntimeFingerprintTest",
                   "ValidationSourceMapTest", "ValidationDebugExecutionTest",
                   "ListenerSourceMapTest", "ListenerDebugExecutionTest",
                   "VernacDebuggerHostLexerTest", "VernacExpressionFactoryTest",
                   "AdapterSourceMapTest", "AdapterDebugExecutionTest", "VernacViewInspectorTest", "VernacTypeReferencesTest", "VernacTypeReferenceTest", "VernacSpringBootTemplateTest", "SpringBootStarterExecutionTest", "InboundMvcContractTest", "InboundMvcExecutionTest", "InboundMvcSymbolsTest", "ImplementationContractsExecutionTest")


# Live editor APIs are a 0.5.0 release feature, including native Java PSI lookup.
REQUIRED_TESTS += ("EditorValueObjectsTest", "BehaviorJavaProjectionTest",
                   "VernacLivePackageTest", "VernacLiveUnionScopeTest",
                   "VernacLiveShortNamesCacheTest", "VernacLiveValueTest")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version(value):
    require(re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value),
            "Use a release version such as 0.4.0, without SNAPSHOT.")
    return value


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def summary(text):
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(text + "\n")


def plugin_jar(path, release):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries.")
        require("vernac-intellij/server/vernac-lsp.jar" in names, "Bundled LSP is missing.")
        jar = archive.read(f"vernac-intellij/lib/vernac-intellij-{release}.jar")
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        root = ET.fromstring(archive.read("META-INF/plugin.xml"))
        require(root.findtext("id") == PLUGIN_ID, "Unexpected Marketplace plugin ID.")
        require(root.findtext("version") == release, "Plugin version differs from release version.")
        require(archive.read("vernac-version.txt").decode().strip() == release,
                "New-project dependency version differs from release version.")
    return jar


def candidate(release, output):
    release = version(release)
    source = Path(f"vernac-intellij/target/vernac-intellij-{release}-plugin.zip")
    plugin_jar(source, release)
    output.mkdir(parents=True, exist_ok=False)
    target = output / source.name
    shutil.copyfile(source, target)
    manifest = {"version": release, "plugin_id": PLUGIN_ID, "file": target.name,
                "sha256": digest(target), "commit": os.environ["GITHUB_SHA"],
                "repository": os.environ["GITHUB_REPOSITORY"], "run_id": os.environ["GITHUB_RUN_ID"],
                "attempt": os.environ["GITHUB_RUN_ATTEMPT"], "dry_run": os.environ["DRY_RUN"] == "true"}
    (output / "release.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    summary(f"### Plugin candidate {release}\nCommit: `{manifest['commit']}`\n\nSHA-256: `{manifest['sha256']}`\n\n"
            f"Source run: `{manifest['run_id']}`, attempt `{manifest['attempt']}`.\n"
            "Destination: JetBrains Marketplace, org.vernac, Stable (after Central availability).")


def verify_candidate(directory, release, run_id, attempt):
    version(release)
    manifest = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    require(manifest["version"] == release and manifest["plugin_id"] == PLUGIN_ID, "Wrong release artifact.")
    require(manifest["run_id"] == run_id and manifest["attempt"] == attempt, "Wrong source run/attempt.")
    require(manifest["repository"] == os.environ["GITHUB_REPOSITORY"], "Wrong source repository.")
    require(re.fullmatch(r"[0-9a-f]{40}", manifest["commit"]), "Invalid source commit.")
    require(manifest["file"] == f"vernac-intellij-{release}-plugin.zip", "Unexpected ZIP name.")
    path = directory / manifest["file"]
    require(digest(path) == manifest["sha256"], "Plugin ZIP checksum mismatch.")
    plugin_jar(path, release)
    return manifest, path


def verify_provenance(directory, release, run_id, attempt, run_file, jobs_file, dry_run):
    manifest, _ = verify_candidate(directory, release, run_id, attempt)
    run = json.loads(run_file.read_text())
    jobs = json.loads(jobs_file.read_text())
    require(str(run["id"]) == run_id and str(run["run_attempt"]) == attempt, "Source run/attempt mismatch.")
    require(run["repository"]["full_name"] == manifest["repository"], "Source repository mismatch.")
    require(run["head_sha"] == manifest["commit"], "Source commit mismatch.")
    require(run["event"] == "workflow_dispatch" and run["path"] == ".github/workflows/release.yml",
            "Only candidates from the manual release workflow are accepted.")
    required_steps = {"Verify complete project", "Require release test execution", "Archive plugin candidate"}
    require(any(j["name"] == "Build and release" and required_steps <=
                {step["name"] for step in j.get("steps", []) if step["conclusion"] == "success"}
                for j in jobs["jobs"]), "Source candidate verification/archive steps did not succeed in this attempt.")
    require(dry_run or manifest["dry_run"] is False, "A dry-run candidate cannot be published. Run a real release first.")
    summary(f"Verified plugin {release}, source `{manifest['commit']}`, SHA-256 `{manifest['sha256']}`.")


def central_paths(release):
    version(release)
    for module in MODULES:
        for extension in (("pom",) if module == "vernac" else ("pom", "jar")):
            yield f"{module}/{release}/{module}-{release}.{extension}"


def available(path):
    try:
        with urllib.request.urlopen(urllib.request.Request(f"{CENTRAL}/{path}", method="HEAD"), timeout=20) as response:
            return response.status == 200
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise RuntimeError(f"Central availability check failed (HTTP {error.code}).") from None
    except (urllib.error.URLError, TimeoutError):
        raise RuntimeError("Central availability check failed; no upload was attempted.") from None


def central(release, timeout, unpublished=False):
    paths = list(central_paths(release))
    if unpublished:
        require(not any(available(path) for path in paths),
                "This version already exists on Central. Do not redeploy; use the plugin-only workflow if appropriate.")
        return
    deadline = time.monotonic() + timeout
    while True:
        paths = [path for path in paths if not available(path)]
        if not paths:
            summary(f"All eight Vernac coordinates at {release} are available on Maven Central.")
            return
        require(time.monotonic() < deadline,
                "Central is not ready. Publish the validated deployment in the Portal, then retry only the plugin workflow.")
        print(f"Waiting for {len(paths)} Central files; review/publish the existing Portal deployment.", flush=True)
        time.sleep(min(30, max(0, deadline - time.monotonic())))


def audit_tests():
    reports = list(Path(".").glob("*/target/surefire-reports/TEST-*.xml"))
    reports += list(Path(".").glob("*/target/failsafe-reports/TEST-*.xml"))
    seen = set()
    for path in reports:
        suite = ET.parse(path).getroot()
        name = suite.get("name", "").split(".")[-1]
        require(all(int(suite.get(key, "0")) == 0 for key in ("failures", "errors", "skipped")),
                f"Release test report has failures, errors or skipped tests: {path}")
        if int(suite.get("tests", "0")) > 0:
            seen.add(name)
    require(set(REQUIRED_TESTS) <= seen, "Missing required release test reports (PostgreSQL, modules, pagination, plugin, vocabulary, diagrams or HTTP/documentation).")
    summary("Release tests passed, including required PostgreSQL, modules, pagination, plugin, vocabulary, diagrams and HTTP/documentation suites; no skipped tests in reactor module reports.")


def consumer(directory, release, output):
    """Use the packaged template, including its actual source/tests, with fresh Central dependencies."""
    release = version(release)
    output.mkdir(parents=True, exist_ok=False)
    jar = plugin_jar(directory / f"vernac-intellij-{release}-plugin.zip", release)
    prefix = "projectTemplates/vernac-basic/"
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        for name in archive.namelist():
            if not name.startswith(prefix) or name.endswith("/"):
                continue
            relative = PurePosixPath(name[len(prefix):])
            require(not relative.is_absolute() and ".." not in relative.parts and "\\" not in str(relative), "Unsafe template path.")
            target = output.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    pom = output / "pom.xml"
    tree = ET.parse(pom)
    root = tree.getroot()
    root.find("m:properties/m:vernac.version", NS).text = release
    dependencies = root.find("m:dependencies", NS)
    for module in ("vernac-runtime-http", "vernac-jdbc-spring-boot-starter"):
        dependency = ET.SubElement(dependencies, "{" + NS["m"] + "}dependency")
        for tag, value in (("groupId", "org.vernac"), ("artifactId", module), ("version", release)):
            ET.SubElement(dependency, "{" + NS["m"] + "}" + tag).text = value
    ET.register_namespace("", NS["m"])
    tree.write(pom, encoding="utf-8", xml_declaration=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def upload(directory, release, run_id, attempt):
    manifest, path = verify_candidate(directory, release, run_id, attempt)
    require(manifest["dry_run"] is False, "Dry-run artifacts cannot be uploaded.")
    token = os.environ.get("JETBRAINS_MARKETPLACE_TOKEN", "")
    require(token and not any(c.isspace() for c in token), "Missing or malformed JETBRAINS_MARKETPLACE_TOKEN.")
    require(path.stat().st_size <= 400_000_000, "Plugin ZIP exceeds Marketplace size limit.")
    boundary = "vernac" + uuid.uuid4().hex
    pieces = []
    for name, value in (("xmlId", PLUGIN_ID), ("isHidden", "false"), ("containsAds", "false")):
        pieces.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    pieces.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: application/zip\r\n\r\n'.encode())
    pieces.extend((path.read_bytes(), f"\r\n--{boundary}--\r\n".encode()))
    request = urllib.request.Request(MARKETPLACE, data=b"".join(pieces), headers={
        "Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary}, method="POST")
    try:
        # No redirects, response-body logging or automatic retries on this side-effecting request.
        with urllib.request.build_opener(NoRedirect).open(request, timeout=120) as response:
            require(200 <= response.status < 300, "Unexpected Marketplace response.")
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Marketplace upload returned HTTP {error.code}. Inspect the existing update in Marketplace before retrying. Response omitted.") from None
    except (urllib.error.URLError, TimeoutError):
        raise RuntimeError("Marketplace upload outcome is uncertain. Inspect Marketplace before retrying; no automatic retry was performed.") from None
    summary(f"Marketplace accepted Vernac {release} for the Stable channel. Acceptance is not approval; check review/public availability before announcing the release.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("version", "modules", "candidate", "provenance", "central", "unpublished", "tests", "consumer", "upload"))
    parser.add_argument("--version", default=os.environ.get("RELEASE_VERSION", ""))
    parser.add_argument("--directory", type=Path, default=Path("release-candidate"))
    parser.add_argument("--run-id", default=os.environ.get("SOURCE_RUN_ID", ""))
    parser.add_argument("--attempt", default=os.environ.get("SOURCE_ATTEMPT", ""))
    parser.add_argument("--run-file", type=Path, default=Path("source-run.json"))
    parser.add_argument("--jobs-file", type=Path, default=Path("source-jobs.json"))
    parser.add_argument("--output", type=Path, default=Path("central-consumer"))
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    if args.command == "modules": print(",".join(MODULES[1:]))
    elif args.command == "version": print(version(args.version))
    elif args.command == "candidate": candidate(args.version, args.directory)
    elif args.command == "tests": audit_tests()
    elif args.command == "central": central(args.version, args.timeout)
    elif args.command == "unpublished": central(args.version, 0, True)
    elif args.command == "consumer": consumer(args.directory, args.version, args.output)
    elif args.command == "provenance": verify_provenance(args.directory, args.version, args.run_id, args.attempt, args.run_file, args.jobs_file, os.environ.get("DRY_RUN") == "true")
    elif args.command == "upload": upload(args.directory, args.version, args.run_id, args.attempt)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
        # Only controlled messages; no environment dump or traceback containing HTTP requests.
        print(f"Release step failed: {error}", file=sys.stderr)
        sys.exit(1)
