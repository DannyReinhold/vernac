"""Offline release tests: archives, provenance, gates and secret-safe upload behavior."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
import urllib.error
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.cwd)
        self.environment = patch.dict(os.environ, {
            "GITHUB_SHA": "a" * 40, "GITHUB_REPOSITORY": "owner/vernac",
            "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "DRY_RUN": "false",
            "JETBRAINS_MARKETPLACE_TOKEN": "TOP_SECRET_TEST_TOKEN"}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.directory = self.root / "candidate"

    def make_candidate(self, plugin_id="org.vernac", dependency_version="0.2.0"):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w") as jar:
            jar.writestr("META-INF/plugin.xml", f"<idea-plugin><id>{plugin_id}</id><version>0.2.0</version></idea-plugin>")
            jar.writestr("vernac-version.txt", dependency_version)
            jar.writestr("projectTemplates/vernac-basic/pom.xml", '''<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><vernac.version>0.2.0-SNAPSHOT</vernac.version></properties><dependencies/></project>''')
            jar.writestr("projectTemplates/vernac-basic/src/test/java/TaskTest.java", "// fixture")
        target = self.root / "vernac-intellij/target/vernac-intellij-0.2.0-plugin.zip"
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("vernac-intellij/lib/vernac-intellij-0.2.0.jar", inner.getvalue())
            archive.writestr("vernac-intellij/server/vernac-lsp.jar", "fixture")
        release.candidate("0.2.0", self.directory)
        return self.directory / target.name

    def provenance(self, event="workflow_dispatch", commit="a" * 40, verified=True):
        run = self.root / "run.json"
        jobs = self.root / "jobs.json"
        run.write_text(json.dumps({"id": 123, "run_attempt": 1, "head_sha": commit,
            "repository": {"full_name": "owner/vernac"}, "event": event, "path": ".github/workflows/release.yml"}))
        jobs.write_text(json.dumps({"jobs": [{"name": "Build and release", "conclusion": "failure",
            "steps": [{"name": name, "conclusion": "success" if verified else "failure"}
                      for name in ("Verify complete project", "Require release test execution", "Archive plugin candidate")]}]}))
        return run, jobs

    def test_version_rejects_snapshots_and_shell_input(self):
        for value in ("0.2.0-SNAPSHOT", "0.2.0;echo bad", "01.2.0", "0.2", "0.2.0\n", "$(id)"):
            with self.assertRaises(ValueError): release.version(value)
        self.assertEqual("0.2.0", release.version("0.2.0"))

    def test_zip_and_injected_dependency_version_must_match(self):
        with self.assertRaisesRegex(ValueError, "Marketplace plugin ID"):
            self.make_candidate(plugin_id="org.vernac.intellij")
        with self.assertRaisesRegex(ValueError, "dependency version"):
            self.make_candidate(dependency_version="0.1.0")

    def test_checksum_and_run_identity_are_verified(self):
        path = self.make_candidate()
        release.verify_candidate(self.directory, "0.2.0", "123", "1")
        with self.assertRaisesRegex(ValueError, "run/attempt"):
            release.verify_candidate(self.directory, "0.2.0", "124", "1")
        with path.open("ab") as stream: stream.write(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            release.verify_candidate(self.directory, "0.2.0", "123", "1")

    def test_failed_central_job_can_recover_but_unverified_or_pr_artifacts_cannot(self):
        self.make_candidate()
        run, jobs = self.provenance()
        release.verify_provenance(self.directory, "0.2.0", "123", "1", run, jobs, False)
        for arguments in ({"event": "pull_request"}, {"commit": "b" * 40}, {"verified": False}):
            run, jobs = self.provenance(**arguments)
            with self.assertRaises(ValueError):
                release.verify_provenance(self.directory, "0.2.0", "123", "1", run, jobs, False)

    def test_dry_run_artifact_can_be_checked_but_never_uploaded(self):
        os.environ["DRY_RUN"] = "true"
        self.make_candidate()
        run, jobs = self.provenance()
        release.verify_provenance(self.directory, "0.2.0", "123", "1", run, jobs, True)
        with self.assertRaisesRegex(ValueError, "dry-run candidate"):
            release.verify_provenance(self.directory, "0.2.0", "123", "1", run, jobs, False)
        with patch.object(release.urllib.request, "build_opener") as opener:
            with self.assertRaises(ValueError): release.upload(self.directory, "0.2.0", "123", "1")
            opener.assert_not_called()

    def test_central_requires_parent_pom_and_every_module_pom_and_jar(self):
        paths = list(release.central_paths("0.2.0"))
        self.assertEqual(15, len(paths))
        self.assertTrue(any("runtime-http" in p for p in paths))
        self.assertTrue(any("runtime-json" in p for p in paths))
        with patch.object(release, "available", side_effect=lambda p: "runtime-http" not in p):
            with self.assertRaisesRegex(ValueError, "Central is not ready"):
                release.central("0.2.0", 0)
        with patch.object(release, "available", return_value=True):
            release.central("0.2.0", 0)
            with self.assertRaisesRegex(ValueError, "already exists"):
                release.central("0.2.0", 0, unpublished=True)

    def test_consumer_uses_packaged_template_and_matches_all_runtime_versions(self):
        self.make_candidate()
        output = self.root / "consumer"
        release.consumer(self.directory, "0.2.0", output)
        root = ET.parse(output / "pom.xml").getroot()
        self.assertEqual("0.2.0", root.findtext("m:properties/m:vernac.version", namespaces=release.NS))
        self.assertEqual(["0.2.0", "0.2.0"], [d.findtext("m:version", namespaces=release.NS)
            for d in root.findall("m:dependencies/m:dependency", release.NS)])
        self.assertTrue((output / "src/test/java/TaskTest.java").exists())

    def test_required_test_suites_cannot_skip_or_disappear(self):
        folder = self.root / "module/target/surefire-reports"
        folder.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "Missing required"):
            release.audit_tests()
        for name in release.REQUIRED_TESTS:
            (folder / f"TEST-{name}.xml").write_text(f'<testsuite name="org.vernac.{name}" tests="1" failures="0" errors="0" skipped="0"/>')
        release.audit_tests()
        (folder / "TEST-skipped.xml").write_text('<testsuite name="Extra" tests="1" skipped="1"/>')
        with self.assertRaisesRegex(ValueError, "skipped"):
            release.audit_tests()

    def test_upload_is_one_multipart_post_and_never_prints_token_or_response(self):
        path = self.make_candidate()
        response = Mock(status=201)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = response
        output = io.StringIO()
        with patch.object(release.urllib.request, "build_opener", return_value=opener), contextlib.redirect_stdout(output):
            release.upload(self.directory, "0.2.0", "123", "1")
        request = opener.open.call_args.args[0]
        self.assertEqual("POST", request.method)
        self.assertEqual(release.MARKETPLACE, request.full_url)
        self.assertEqual("Bearer TOP_SECRET_TEST_TOKEN", request.get_header("Authorization"))
        self.assertIn(b'name="xmlId"\r\n\r\norg.vernac', request.data)
        self.assertIn(path.read_bytes(), request.data)
        self.assertNotIn(b'name="channel"', request.data)
        self.assertNotIn("TOP_SECRET", output.getvalue())
        opener.open.assert_called_once()
        response.read.assert_not_called()

    def test_upload_errors_and_timeouts_do_not_retry_or_expose_body(self):
        self.make_candidate()
        for error in (urllib.error.HTTPError(release.MARKETPLACE, 401, "SECRET_response", {}, io.BytesIO(b"TOP_SECRET")), TimeoutError("TOP_SECRET")):
            opener = Mock()
            opener.open.side_effect = error
            with patch.object(release.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(RuntimeError) as caught:
                    release.upload(self.directory, "0.2.0", "123", "1")
            self.assertNotIn("SECRET", str(caught.exception))
            opener.open.assert_called_once()


if __name__ == "__main__": unittest.main()
