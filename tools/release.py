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
           "vernac-runtime-jdbc", "vernac-jdbc-spring-boot-starter", "vernac-compiler", "vernac-maven-plugin",
           "vernac-saas-identity", "vernac-saas-identity-spring-security", "vernac-saas-identity-jdbc",
           "vernac-saas-mail", "vernac-saas-mail-local", "vernac-saas-mail-brevo")
MODULE_PATHS = {"vernac-saas-identity": "saas/identity",
                "vernac-saas-identity-spring-security": "saas/identity-spring-security",
                "vernac-saas-identity-jdbc": "saas/identity-jdbc",
                "vernac-saas-mail": "saas/mail", "vernac-saas-mail-local": "saas/mail-local",
                "vernac-saas-mail-brevo": "saas/mail-brevo"}

# Optional SaaS artifacts are version-aligned and explicitly included only from 0.6.0.
for _suffix in ("subscriptions", "restrictions", "payments", "payments-http", "payments-local",
                "payments-mollie", "payments-stripe", "payments-jdbc", "invoicing", "invoicing-pdf", "billing-spring-mvc"):
    _artifact = "vernac-saas-" + _suffix
    MODULES += (_artifact,)
    MODULE_PATHS[_artifact] = "saas/" + _suffix


def reactor_modules():
    return tuple(MODULE_PATHS.get(module, module) for module in MODULES[1:])


CENTRAL = "https://repo.maven.apache.org/maven2/org/vernac"
MARKETPLACE = "https://plugins.jetbrains.com/api/updates/upload"
PLUGIN_ID = "org.vernac"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
REQUIRED_TESTS = ("AdapterBindingsTest","PortModuleTest", "PortModuleSymbolsTest", "TypeModuleTest", "TypeModuleExecutionTest", "ApplicationDefinitionTest", "ApplicationDefinitionSymbolsTest", "ModuleDefinitionSymbolsTest", "InboundErrorPolicyExecutionTest", "InboundErrorPolicyContractTest", "InboundErrorPolicySymbolsTest", "EntitySchemaPostgresTest", "EventStoragePostgresTest", "EventWorkerPostgresTest",
                  "RepositoryQueryPostgresTest", "ScalarQueryPostgresTest", "ScalarStoragePostgresTest",
                  "RequiredFieldBackfillTest", "SchemaMojoTest", "PluginDistributionIT",
    "DocumentationExamplesTest", "InboundHttpContractTest", "InboundHttpExecutionTest",
    "InboundHttpRuntimeTest", "InboundHttpSymbolsTest", "PaginationTest", "JdbcCursorCodecTest", "PaginationHttpExecutionTest", "JsonPagesTest", "PaginationResultContractTest")

SAAS_IDENTITY_TESTS = (
    "AccountInvitationsPostgresIT",
    "AccountMetadataPostgresIT",
    "AccountPolicyPostgresIT",
    "AdmissionDigestsTest",
    "IdentityAdmissionModelTest",
    "IdentityAdmissionPostgresIT",
    "IdentityAuthenticationPostgresIT",
    "IdentityCapacityPostgresIT",
    "IdentityChallengeRepresentationTest",
    "IdentityChallengeTokenTest",
    "IdentityChallengesPostgresIT",
    "IdentityCredentialsPostgresIT",
    "IdentityDeliveryCryptoTest",
    "IdentityEncryptionAdmissionPostgresIT",
    "IdentityIngressModelTest",
    "IdentityIngressPostgresIT",
    "IdentityJdbcAuthenticationAssemblyPostgresIT",
    "IdentityJdbcModuleIT",
    "IdentityJdbcSessionsPostgresIT",
    "IdentityJourneysPostgresIT",
    "IdentityLoginAddressModelTest",
    "IdentityLoginOwnershipPostgresIT",
    "IdentityMailRequestsPostgresIT",
    "IdentityMailWorkerPostgresIT",
    "IdentityProviderMailSenderTest",
    "IdentityRegistrationsPostgresIT",
    "IdentitySecurityEpochTest",
    "IdentitySessionEpochPostgresIT",
    "IdentityAccountChainTest",
    "IdentityActorResolverTest",
    "IdentityBrowserSecurityTest",
    "IdentityFormFilterTest",
    "IdentityInvitationChainTest",
    "IdentityJourneyChainTest",
    "IdentityLocalReturnTargetTest",
    "IdentityLoginFilterTest",
    "IdentityLoginPageFilterTest",
    "IdentityPrincipalTest",
    "IdentityRequestAdmissionFilterTest",
    "IdentitySessionTrustTest",
    "IdentitySessionValidationFilterTest",
    "BoundedCredentialPasswordsTest",
    "BundledPasswordBlocklistTest",
    "CredentialCorpusDistributionIT",
    "CredentialPasswordsTest",
    "LoginAddressTest",
    "OfflinePasswordBlocklistTest",
    "PasswordInputTest",
    "StoredEncodingPolicyTest",
    "IdentityModuleContractIT",
    "RoleAssignmentsTest",
    "RoleCatalogTest",
    "BrevoMailTransportTest",
    "MailModuleConsumerTest",
    "MailProviderSelectionTest",
    "LocalMailInboxTest",
    "MailContractsTest",
    "IdentityBrowserPostgresIT",
    "IdentityOperationsPostgresIT",
)
REQUIRED_TESTS += SAAS_IDENTITY_TESTS

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
                   "AdapterSourceMapTest", "AdapterDebugExecutionTest", "VernacViewInspectorTest", "VernacDebuggerApiTest", "VernacTypeReferencesTest", "VernacTypeReferenceTest", "VernacSpringBootTemplateTest", "SpringBootStarterExecutionTest", "InboundMvcContractTest", "InboundMvcExecutionTest", "InboundMvcSymbolsTest", "ImplementationContractsExecutionTest")


# Explicit ordinary-value representation and unsaved LSP diagnostics are required in 0.6.0.
REQUIRED_TESTS += ("ValueStringRepresentationTest", "VernacProjectDiagnosticsTest",
                   "UniqueAggregateFieldsTest", "UniqueFieldModuleTest", "UniqueFieldSymbolsTest",
                   "UniquePersistenceSchemaTest", "UniquePersistencePostgresTest", "UniqueModulePostgresTest",
                   "GlobalIdReferenceTest", "GlobalIdReferencePostgresTest")

# Live editor APIs are a 0.5.0 release feature, including native Java PSI lookup.
REQUIRED_TESTS += ("EditorValueObjectsTest", "BehaviorJavaProjectionTest",
                   "VernacLivePackageTest", "VernacLiveUnionScopeTest",
                   "VernacLiveShortNamesCacheTest", "VernacLiveValueTest")


REQUIRED_TESTS += ("SubscriptionDomainTest", "SubscriptionPostgresIT", "RestrictionDomainTest", "RestrictionPostgresIT",
                   "PaymentContractsTest", "MolliePaymentsTest", "StripePaymentsTest", "LocalPaymentsPostgresIT",
                   "PaymentFlowPostgresIT", "DomesticTaxTest", "ZugferdDocumentsTest", "InvoiceFlowPostgresIT",
                   "BillingPagesTest", "VernacSaasTemplateTest", "JdbcRootQueryTest",
                   "RepositoryBindingTest", "TenantGeneratedPostgresTest", "SaaSScenarios", "UpgradeBaseline")
ACCEPTANCE_IDS = tuple(f"A{i:02}" for i in range(1, 24)) + tuple(f"J{i:02}" for i in range(1, 10))
DECISION_IDS = tuple(f"D{i:02}" for i in range(1, 15))


def validate_saas_scenarios(suite):
    if suite.get("name", "").split(".")[-1] != "SaaSScenarios":
        return
    cases = suite.findall("testcase")
    ids = [re.match(r"E\d{2}(?= )", case.get("name", "")) for case in cases]
    require(len(cases) == 29 and int(suite.get("tests", "0")) == 29
            and all(ids) and {m.group() for m in ids} == {f"E{i:02}" for i in range(1, 30)},
            "Incomplete or duplicate SaaS scenarios.")
    require({case for row in cases for case in re.findall(r"\bS\d{2}\b", row.get("name", ""))}
            == {f"S{i:02}" for i in range(1, 21)}, "Missing SaaS usecase coverage.")


def validate_upgrade_baseline(suite, root):
    if suite.get("name", "").split(".")[-1] != "UpgradeBaseline":
        return
    registry = json.loads((root / "tests/upgrades/registry.json").read_text())
    versions = registry.get("baselines", [])
    require(registry.get("minimum_supported") == "0.6.0" and versions
            and "0.6.0" in versions and len(set(versions)) == len(versions), "Invalid upgrade registry.")
    cases = suite.findall("testcase")
    names = [" ".join(case.get("name", "").split()[:2]) for case in cases]
    require(len(names) == 2*len(versions) and int(suite.get("tests", "0")) == len(names)
            and set(names) == {v+" "+u for v in versions for u in ("U01", "U02")},
            "Incomplete or duplicate UpgradeBaseline scenarios.")
    properties = {p.get("name"): p.get("value") for p in suite.findall("properties/property")}
    for version in versions:
        require(isinstance(version, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version), "Unsafe baseline version.")
        directory = root / "tests/upgrades" / version
        manifest_file = directory / "manifest.json"
        manifest = json.loads(manifest_file.read_text())
        require(manifest.get("version") == version and manifest.get("state") == "FROZEN"
                and manifest.get("synthetic_only") is True
                and properties.get("baseline."+version) == digest(manifest_file), "Missing frozen baseline evidence.")
        require(set(manifest.get("files", {})) == {"database.sql", "expectations.json", "test-runtime.json"}, "Incomplete baseline files.")
        for name, expected in manifest["files"].items():
            path = directory / name
            require(not path.is_symlink() and path.is_file() and digest(path) == expected, "Baseline file changed.")
        require(manifest.get("migration_files"), "Missing baseline migration inventory.")
        for name, expected in manifest["migration_files"].items():
            path = root / name
            require(not path.is_symlink() and path.resolve().is_relative_to(root) and path.is_file()
                    and digest(path) == expected, "Historical baseline migration changed.")


def acceptance(bundle_file, release, source_sha, root=Path(".")):
    """Validate an external, source-bound review bundle. Missing evidence is always NO-GO."""
    version(release)
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha or ""), "Acceptance requires an immutable source SHA.")
    root = root.resolve()
    inventory = json.loads((root / "docs/development/saas-release-inventory-0.6.0.json").read_text())
    require(set(inventory.get("requirements", {})) == set(ACCEPTANCE_IDS)
            and set(inventory.get("decisions", {})) == set(DECISION_IDS), "Source inventory lost a requirement or decision.")
    require(set(inventory.get("module_inventory", [])) == set(published_modules(release)) | {"vernac-intellij"}
            and set(inventory.get("required_suites", [])) == set(REQUIRED_TESTS), "Source release inventory is incomplete.")
    require(inventory.get("recommendation") == "GO" and inventory.get("blocking_findings") == []
            and all(row.get("status") == "VERIFIED" for row in inventory["requirements"].values())
            and all(value == "ACCEPTED" for value in inventory["decisions"].values()), "Source inventory retains unresolved release blockers.")
    bundle = json.loads(bundle_file.read_text(encoding="utf-8"))
    require(bundle.get("schema_version") == 1 and bundle.get("version") == release, "Wrong acceptance schema/version.")
    require(bundle.get("source_sha") == source_sha, "Stale acceptance source SHA.")
    require(set(bundle.get("requirements", {})) == set(ACCEPTANCE_IDS), "Missing or unknown acceptance requirement.")
    require(set(bundle.get("decisions", {})) == set(DECISION_IDS), "Missing or unknown design decision.")
    require(all(v == "ACCEPTED" for v in bundle["decisions"].values()), "Unresolved design decision.")
    require(bundle.get("blocking_findings") == [], "Unresolved release-blocking findings.")
    records = bundle.get("evidence", {})
    require(isinstance(records, dict) and records, "Missing acceptance evidence records.")
    for key, record in records.items():
        require(re.fullmatch(r"[A-Za-z0-9._-]{1,100}", key), "Invalid acceptance evidence key.")
        require(record.get("source_sha") == source_sha and record.get("status") == "PASSED", "Unknown, failed or stale evidence.")
        require(isinstance(record.get("kind"), str) and record["kind"] in {"junit", "browser", "sandbox", "review", "ide-smoke"}, "Unknown evidence kind.")
        path = root / record.get("file", "")
        require(not path.is_symlink() and path.resolve().is_relative_to(root) and path.is_file(), "Missing or unsafe evidence file.")
        require(re.fullmatch(r"[0-9a-f]{64}", record.get("sha256", "")) and digest(path) == record["sha256"], "Evidence digest changed.")
        if record["kind"] == "junit":
            suite = ET.parse(path).getroot()
            validate_saas_scenarios(suite)
            validate_upgrade_baseline(suite, root)
            require(int(suite.get("tests", "0")) > 0 and all(int(suite.get(k,"0")) == 0 for k in ("failures","errors","skipped")), "Required acceptance report failed or skipped.")
            require(all(not any(c.find(k) is not None for k in ("failure","error","skipped")) for c in suite.findall("testcase")), "Required acceptance testcase failed or skipped.")
        else:
            require(isinstance(record.get("reviewed_by"), str) and bool(record["reviewed_by"].strip()), "Evidence needs an identified reviewer.")
    suites = {ET.parse(root / record["file"]).getroot().get("name", "").split(".")[-1]
              for record in records.values() if record["kind"] == "junit"}
    require(set(REQUIRED_TESTS) <= suites, "Missing required source-bound acceptance suites.")
    for key, row in bundle["requirements"].items():
        require(row.get("status") == "VERIFIED" and row.get("evidence"), "Unverified acceptance requirement: " + key)
        require(all(ref in records for ref in row["evidence"]), "Unknown requirement evidence reference.")
    for key, providers in (("A09", {"mollie", "stripe"}), ("A11", {"brevo"})):
        verified = {records[ref].get("provider") for ref in bundle["requirements"][key]["evidence"] if records[ref]["kind"] == "sandbox"}
        require(providers <= verified, "Real provider attestation missing: " + key)
    require(any(records[ref]["kind"] == "ide-smoke" for ref in bundle["requirements"]["A16"]["evidence"]), "Installed IDE smoke evidence missing.")
    expected = set(published_modules(release)) | {"vernac-intellij"}
    artifacts = bundle.get("artifacts", {})
    require(set(artifacts) == expected, "Missing or unknown release artifact.")
    for name, record in artifacts.items():
        path = root / record.get("file", "")
        require(not path.is_symlink() and path.resolve().is_relative_to(root) and path.is_file(), "Missing or unsafe release artifact.")
        require(record.get("sha256") == digest(path), "Artifact digest changed: " + name)
        expected_name = (f"vernac-intellij-{release}-plugin.zip" if name == "vernac-intellij" else
                         ".flattened-pom.xml" if name == "vernac" else f"{name}-{release}.jar")
        require(path.name == expected_name, "Wrong release artifact filename.")
    classpaths = bundle.get("dependency_reports", {})
    require(set(classpaths) == expected - {"vernac"}, "Missing resolved dependency report.")
    resolved_dependencies = set()
    for module, report in classpaths.items():
        path = root / report.get("file", "")
        require(not path.is_symlink() and path.resolve().is_relative_to(root) and path.is_file(), "Missing or unsafe dependency report.")
        require(report.get("sha256") == digest(path), "Resolved dependency report changed.")
        paths = path.read_text().strip().split(os.pathsep)
        require(bool(paths) and all(p.endswith(".jar") for p in paths), "Invalid resolved dependency classpath.")
        resolved_dependencies.update(paths)
    dependencies = bundle.get("dependencies", {})
    require(isinstance(dependencies, dict) and set(dependencies) == resolved_dependencies, "Incomplete dependency digest inventory.")
    # A full build exports its resolved Maven dependencies into this retained inventory.
    for location, expected_hash in dependencies.items():
        path = Path(location)
        require(path.is_file() and not path.is_symlink() and re.fullmatch(r"[0-9a-f]{64}", expected_hash), "Missing dependency artifact.")
        require(digest(path) == expected_hash, "Dependency digest changed.")
    approval = bundle.get("approval", {})
    require(approval.get("decision") == "GO" and approval.get("source_sha") == source_sha
            and approval.get("plugin_sha256") == artifacts["vernac-intellij"]["sha256"]
            and isinstance(approval.get("approved_by"), str) and bool(approval["approved_by"].strip()), "Missing exact-candidate maintainer GO.")
    return bundle


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
    evidence_file = Path("release-evidence/acceptance.json")
    if tuple(map(int, release.split("."))) >= (0, 6, 0):
        acceptance(evidence_file, release, os.environ.get("GITHUB_SHA", ""))
    output.mkdir(parents=True, exist_ok=False)
    target = output / source.name
    shutil.copyfile(source, target)
    manifest = {"version": release, "plugin_id": PLUGIN_ID, "file": target.name,
                "sha256": digest(target), "commit": os.environ["GITHUB_SHA"],
                "repository": os.environ["GITHUB_REPOSITORY"], "run_id": os.environ["GITHUB_RUN_ID"],
                "attempt": os.environ["GITHUB_RUN_ATTEMPT"], "dry_run": os.environ["DRY_RUN"] == "true"}
    if tuple(map(int, release.split("."))) >= (0, 6, 0):
        manifest["acceptance_sha256"] = digest(evidence_file)
        shutil.copyfile(evidence_file, output / "acceptance.json")
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
    if tuple(map(int, release.split("."))) >= (0, 6, 0):
        evidence_file = directory / "acceptance.json"
        require(evidence_file.is_file() and digest(evidence_file) == manifest.get("acceptance_sha256"), "Missing or changed archived acceptance bundle.")
        evidence = json.loads(evidence_file.read_text())
        require(evidence.get("source_sha") == manifest["commit"] and evidence.get("approval", {}).get("plugin_sha256") == manifest["sha256"], "Archived acceptance candidate mismatch.")
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


def published_modules(release):
    version(release)
    # Plugin-only recovery for pre-0.6 releases must not wait for later SaaS artifacts.
    return MODULES if tuple(map(int, release.split("."))) >= (0, 6, 0) else tuple(m for m in MODULES if m not in MODULE_PATHS)


def central_paths(release):
    for module in published_modules(release):
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
            summary(f"All {len(published_modules(release))} Vernac coordinates at {release} are available on Maven Central.")
            return
        require(time.monotonic() < deadline,
                "Central is not ready. Publish the validated deployment in the Portal, then retry only the plugin workflow.")
        print(f"Waiting for {len(paths)} Central files; review/publish the existing Portal deployment.", flush=True)
        time.sleep(min(30, max(0, deadline - time.monotonic())))


def audit_tests():
    reports = list(Path(".").glob("*/target/surefire-reports/TEST-*.xml"))
    reports += list(Path(".").glob("*/target/failsafe-reports/TEST-*.xml"))
    reports += list(Path(".").glob("saas/*/target/surefire-reports/TEST-*.xml"))
    reports += list(Path(".").glob("saas/*/target/failsafe-reports/TEST-*.xml"))
    reports += list(Path(".").glob("examples/saas-document-workspace/target/acceptance-reports/TEST-*.xml"))
    reports += list(Path(".").glob("examples/saas-document-workspace/target/upgrade-reports/TEST-*.xml"))
    seen = set()
    for path in reports:
        suite = ET.parse(path).getroot()
        validate_saas_scenarios(suite)
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
    parser.add_argument("command", choices=("version", "modules", "candidate", "provenance", "central", "unpublished", "tests", "consumer", "upload", "acceptance"))
    parser.add_argument("--version", default=os.environ.get("RELEASE_VERSION", ""))
    parser.add_argument("--directory", type=Path, default=Path("release-candidate"))
    parser.add_argument("--run-id", default=os.environ.get("SOURCE_RUN_ID", ""))
    parser.add_argument("--attempt", default=os.environ.get("SOURCE_ATTEMPT", ""))
    parser.add_argument("--run-file", type=Path, default=Path("source-run.json"))
    parser.add_argument("--jobs-file", type=Path, default=Path("source-jobs.json"))
    parser.add_argument("--output", type=Path, default=Path("central-consumer"))
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--evidence", type=Path, default=Path("release-evidence/acceptance.json"))
    args = parser.parse_args()
    if args.command == "modules": print(",".join(reactor_modules()))
    elif args.command == "version": print(version(args.version))
    elif args.command == "candidate": candidate(args.version, args.directory)
    elif args.command == "tests": audit_tests()
    elif args.command == "acceptance":
        acceptance(args.evidence, args.version, os.environ.get("GITHUB_SHA", ""))
        summary("Source-bound SaaS acceptance bundle passed; this command publishes nothing.")
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
