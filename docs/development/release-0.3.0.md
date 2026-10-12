# Vernac 0.3.0 release preparation

Status: **prepared for acceptance; not a publication record**. Work is on
feature/0.3.0. The root revision and source-checkout examples remain 0.3.0-SNAPSHOT;
the release workflow selects 0.3.0 centrally. Do not advance to 0.4.0-SNAPSHOT until
this release has been recorded and the next development line is agreed.

## Release scope

- Consolidated project resolution/generation with obsolete experimental paths removed.
- HTTP inbound adapters around usecases: explicit request bindings, shared ACL
  mappings, explicit Result sources, scoped safe error handling and Spring MVC.
- Dispatch demonstrates generated create/release endpoints alongside handwritten Java.
- Consolidated English entry pages, tutorials, FAQs, contracts and the AI reference.
- Existing 0.2.0 domain, persistence, migrations, events and outbound integration
  contracts continue as the foundation; migration snapshots are not rewritten.

This is not a promise of authentication, modular generic subdomains, async ports,
OpenAPI, MCP or messaging adapters. [Capabilities/authorization](capabilities-and-usecase-authorization.md)
and [deep IDE integration](intellij-deep-integration.md) remain explicit future work.

## Acceptance before merge

Use JDK 25 and Maven 3.9.16 for release checks. From the repository root:

```text
mvn clean install
python tools/check-doc-links.py
python -m unittest discover -s tools/tests -v -b
```

For root PostgreSQL tests, start the disposable service described in the persistence
example and set VERNAC_PG_TEST_URL, VERNAC_PG_TEST_USER and VERNAC_PG_TEST_PASSWORD.
A local build that skips those tests is useful but **not release acceptance**.

- [ ] Root tests including required PostgreSQL suites execute without skips/errors.
- [ ] Documentation models and all AI reference examples generate/compile.
- [ ] Documentation links and offline release-tool tests pass.
- [ ] Run all database-free checks in the [example guide](https://github.com/DannyReinhold/vernac/blob/main/examples/README.md).
- [ ] Enable persistence-demo database tests and Dispatch's database-tests profile
  against their separate disposable test databases; inspect the results.
- [ ] Run the Dispatch walkthrough, including generated create/release endpoints.
- [ ] Install the exact candidate plugin and check editing/navigation/project creation.
- [ ] Confirm no tutorial experiments, private material, target output or flattened
  POMs appear in the intended Git commit. Applied migrations retain their histories.
- [ ] Review the [release-note draft](../maintainers/releases/0.3.0.md).
- [ ] Push and complete Vernac Release dry_run=true with version 0.3.0.

Checks remain unchecked here until their actual release evidence is recorded. Local
unit tests cannot establish Marketplace approval or remote publication.

## Merge, publish and retain evidence

After acceptance, merge to main and select its exact commit for the real workflow.
Follow [release operations](../maintainers/releasing.md): Central validation, manual
Portal Publish, fresh Central consumer, original plugin upload, JetBrains review,
and final installation verification are distinct steps.

Record the main SHA, real workflow run/attempt, Central deployment and Published
status, Marketplace status, ZIP checksum, tags and GitHub releases. Keep the original
ZIP/release.json as release assets. Retry a plugin-only failure via its separate
workflow; do not redeploy published Maven coordinates.

After publication, create maven-v0.3.0 and plugin-v0.3.0 on that exact source commit.
Only then prepare the next development version in a separate branch/commit.
The [0.2.0 plan](release-0.2.0.md) remains a labelled historical record.
