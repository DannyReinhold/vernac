# Vernac 0.4.0 release preparation

Status: **historical 0.4.0 preparation and acceptance record**.
The next development branch uses 0.5.0-SNAPSHOT. Commands and evidence below
retain their 0.4.0 context; distribution evidence belongs in the GitHub Release.
Source branch: feature/0.4.0. Root revision remains 0.4.0-SNAPSHOT; the workflow
selects 0.4.0 without committing a POM-version change. The [release notes](../maintainers/releases/0.4.0.md)
define the scope. The [operations guide](../maintainers/releasing.md) describes recovery.

## Acceptance

Use JDK 25 and Maven 3.9.16. From the repository root:

```text
mvn clean install
python tools/check-doc-links.py
python -m unittest discover -s tools/tests -v -b
```

Configure the disposable PostgreSQL test service and the three VERNAC_PG_TEST_*
variables as described in [examples](https://github.com/DannyReinhold/vernac/blob/main/examples/README.md). Skipped database tests
are not release acceptance. The release workflow provides its own disposable server.

- [ ] Root build and required PostgreSQL suites run without skips/failures.
- [ ] Offline release-tool checks and documentation links pass.
- [ ] Standalone examples pass the applicable checks in the example guide, including
  type-modules, profile bindings, persistent-modules, Dispatch and persistence-demo.
- [ ] Install the packaged plugin: source/embedded-Java navigation, project kind,
  module/application files, module hover, terms and diagram action behave as documented.
- [ ] No tutorial migration experiments, target files, secrets or generated flattened
  POMs are included in the release commit. Existing applied migrations remain intact.
- [ ] Review the release notes and complete Vernac Release dry_run=true, version 0.4.0,
  on the pushed feature/0.4.0 preparation commit.

Only record a check as passed when its actual evidence exists. The workflow requires
module persistence, pagination, vocabulary and diagram suites as well as existing
integration, documentation and packaged-plugin checks. Standalone examples are separate
from the root reactor; they still need the applicable checks above.

## Local preparation evidence (2026-10-14)

- Full `mvn clean install`: 653 reactor tests, zero failures/errors, 16 database-dependent
  skips. This verifies packaging and the available suites, **not** PostgreSQL acceptance.
- All required suite names are present in the reactor reports. The release gate still
  rejects the skipped tests until the workflow executes them against PostgreSQL.
- Documentation links: 532 checked, zero failures. Offline release tooling: 10 tests passed.
- Seven standalone checks passed: basic template, both schema examples, persistence-demo,
  Dispatch, type-modules and persistent-modules. Database-dependent example checks
  remain subject to their opt-in/disposable-server setup; skipped checks are not passes.
- Fixed persistence-demo's external RecordTourNotes implementation to use the declared
  `tourRepository` dependency accessor instead of the obsolete `tours` name.
- The newly packaged IntelliJ plugin passed its distribution check; installed-IDE
  acceptance remains a separate user check.

## Merge and publish

1. After the dry run and acceptance, merge feature/0.4.0 into main and record the SHA.
2. Run **Vernac Release** on **main**, version **0.4.0**, dry_run **false**.
3. Once Central validation succeeds, Publish the existing deployment in the Central
   Portal. Do not start a second Maven deployment for the same coordinates.
4. The Marketplace job waits for Central, builds a fresh consumer and uploads the
   archived candidate ZIP. If it times out, use **Publish IntelliJ Plugin** with the
   original run ID/attempt. Never rerun all release jobs after publication.
5. Check Marketplace approval and installation separately. Preserve the original ZIP,
   release.json and workflow identity as release evidence.
6. Create **v0.4.0** on the exact real-release SHA and create the GitHub Release from
   the notes, attaching the original ZIP and manifest. The workflow does neither.
7. Only afterward create feature/0.5.0 and update root/template/example Vernac-version
   defaults to 0.5.0-SNAPSHOT in a separate development commit.

Do not move a published tag or imply publication before both services confirm their
actual state. CI's migration tests do not replace migration review by application owners.
