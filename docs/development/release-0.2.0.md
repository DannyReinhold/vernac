# Vernac 0.2.0 consolidation and release plan

Status: 2026-10-11. Development version: **0.2.0-SNAPSHOT**.
All consolidation work remains on **feature/redefining_the_language**. Merge to
main only after the acceptance gates below, then release **0.2.0** to Maven Central
and update the IntelliJ plugin. No release, tag or remote merge is performed by
this planning change. The larger language extensions are explicitly post-release.

## Scope and working method

Finish the existing language before widening it. Use the
[feature review checklist](feature-review-checklist.md): DDD usefulness, problem
coverage, language consistency, generated Java quality, shared compiler rules,
Java interoperability, diagnostics, IDE support, contracts, tutorials and FAQs.
Deliver reviewable incremental patches with meaningful automated verification.
Keep the current runtime boundaries explicit; do not silently promise mutation
rollback, deep immutability of entity graphs, safe production migrations or retries.

## 1. Repository and version baseline

- [x] Set the reactor revision to 0.2.0-SNAPSHOT.
- [x] Align the standalone persistence examples' Vernac dependency/plugin versions.
- [x] Remove vernac-example and vernac-example-home-energy from source and reactor.
- [x] Point the root showcase instructions to examples/persistence-demo.
- [ ] Review every remaining example and project/file template, including tests,
      generated output, namespaces, imports, POMs and instructions.
- [ ] Make the complete root build a green gate again; targeted builds are not the
      final release acceptance criterion.

The basic project template now uses namespace directories and modify behavior.
The project wizard relocates both Vernac and Java sources and injects the bundled
compiler version without matching a hard-coded old version. Tests cover custom
coordinates, Unicode namespaces, compilation and pre-existing destination files.
The first-domain-model tutorial follows this template. The example source/documentation
audit now provides a [learning guide](https://github.com/DannyReinhold/vernac/blob/feature/redefining_the_language/examples/README.md), removes the isolated
operations event experiment and covers entity/service behavior in Java tests. Both
schema examples generate successfully; file templates are compiled in their test.
The demo's database/concurrency/event checks still require an explicit PostgreSQL
run before release. Do not treat their opt-in skips as successful verification.

The standalone examples retain their own application versions. Only their
vernac.version denotes the compiler/runtime version. The parent revision remains
the central reactor version; release builds use -Drevision=0.2.0. Example release
smoke tests use the same selected version through -Dvernac.version=0.2.0 where
applicable. Add an automated alignment check when finalizing the release workflow.

## 2. Close consistency and implementation gaps

- [ ] Compare grammar, semantic analysis, generators and contracts for every
      supported declaration; identify and remove or explicitly delimit old constructs.
- [ ] Revisit dependency/self and implemented-by consistency across domain behavior,
      usecases, services, listeners and adapters. Record any intentional differences.
- [ ] Decide whether service read/modify classification needs adjustment before 0.2.0.
- [ ] Review validation access depth and document remaining shallow-view boundaries.
      Do not reopen candidate-state rollback as a prerequisite for this release.
- [ ] Check IntelliJ/LSP support feature-by-feature, including Java projection,
      diagnostics, completion and navigation. Keep the requested Ctrl-hover visual
      feedback visible in the backlog; decide its release priority explicitly.
- [ ] Investigate regressions in reviewed features immediately; avoid expanding the
      release into speculative refactors without a concrete user benefit.

Acceptance: no known contradiction between the documented public contract and the
implemented behavior. Intentional limits are discoverable and covered where useful.

## 3. Review the entire documentation and example collection

This includes README, all of docs (contracts, specification, development,
maintainers, tutorials, FAQs and website entry points), templates and examples.

- [x] Inventory pages and classify each as current contract, tutorial, maintainer
      guide, active design note, historical decision or obsolete content.
- [x] Update useful pages. Consolidate duplicates and remove obsolete or irrelevant
      material from the working tree; Git history preserves old versions.
- [ ] Keep necessary active design decisions, but distinguish plans from guarantees.
- [ ] Repair links, navigation, stale commands, class names, old syntax and version
      claims. Never publish 0.2.0-SNAPSHOT as if it were available on Central.
- [ ] Check every example's purpose; prefer a few useful, executable examples over
      many overlapping demonstrations. Keep examples under examples.
- [ ] Compile important documentation snippets and run referenced examples where
      claims cross a runtime boundary. Verify terminal vs IntelliJ Maven instructions.
- [ ] Ensure tutorials/FAQs explain nullability, validation failure state, naming,
      migrations, event evolution, retries and external communication responsibilities.

## 4. Build a compelling complete application

**Vernac Dispatch** is implemented under `examples/dispatch` as an independent
application. Its domain and carrier tests pass. The database acceptance profile and
manual browser walkthrough remain release gates until run against PostgreSQL.

The user should be able to create tours and stops, plan shipments, move a stop
between tours through a domain service, request a carrier quote through a port/ACL,
dispatch a tour and observe subsequent event processing. A clear browser UI should
show plans, actionable validation/conflict messages and delivery status. Persisted
data must survive restart. Include deterministic demo data and a repeatable reset
for only the disposable demonstration database.

Use the language where it earns its place: values, invariants, aggregates/entities,
collections, usecases, services, repositories/queries, events and outbound adapters.
Use ordinary Java/Spring for controllers and UI glue. Do not implement the deferred
inbound/API language merely to build this example. A local carrier fixture should
make the default startup independent of external accounts; document replacement
with a real implementation.

Location: examples/dispatch, standalone and not a reactor child.
Keep persistence-demo as a focused technical walkthrough if both retain a distinct
purpose. Define a short end-to-end demo script and automated acceptance test before
adding visual polish. Avoid route optimization, real payments, authentication
platforms or other unrelated product subsystems in this release example.

- [x] Define screens, workflow and acceptance scenarios.
- [x] Implement the domain/application and persistence with current Vernac features.
- [x] Add the browser UI and local carrier setup with a one-command startup.
- [ ] Demonstrate invariants, optimistic conflicts, ACL errors and event processing.
- [x] Add a tutorial and repeatable automated database acceptance test.
- [ ] Execute the database acceptance profile and complete the manual UI walkthrough.

## 5. Release automation, including Marketplace

Recommended shape: shared version/commit and verification, with separately
repeatable publication jobs or workflows. The final implementation decision follows
inspection of Marketplace's supported upload API and the existing release process.

- [ ] Extend the current release workflow so every published runtime artifact,
      including JSON/HTTP, is explicitly covered by the release inventory.
- [ ] Build and retain the plugin ZIP from the reviewed release source and version;
      validate its descriptor, bundled LSP/compiler and generated-project template.
- [ ] Automate Marketplace upload using a dedicated repository/environment secret;
      never place tokens in source, generated artifacts, logs or documentation.
- [ ] Make dry runs build/verify/archive without uploading to Central or Marketplace.
- [ ] Separate upload/publication recovery: a failed Marketplace step must not require
      redeploying an immutable Maven Central version, and vice versa.
- [ ] Gate plugin publication on matching required Maven artifacts being available,
      so newly generated projects can actually resolve their dependencies.
- [ ] Record version, commit, artifact checksums and destinations in the workflow
      summary. Update the maintainer guide to match the actual process.
- [ ] Document any external review delay; upload is not the same as availability.
- [ ] Reconcile tag/GitHub Release conventions with the existing Maven/plugin tags.
      Do not move previously published tags.

The existing workflow's dry-run success is not sufficient evidence if relevant
PostgreSQL tests were skipped. Configure disposable databases and make required
acceptance tests explicit gates. Keep credentials limited to publication steps.

## 6. Acceptance, merge and release

- [ ] Complete reactor build with JDK 25 and verified module target versions.
- [ ] PostgreSQL mapping/query/concurrency/event tests execute, rather than skip.
- [ ] Migration replay/schema-check and backfill acceptance pass.
- [ ] Standalone examples and the complete application's acceptance run pass.
- [ ] Plugin distribution checks and new-project end-to-end build pass.
- [ ] Documentation audit and release notes are complete; all blockers resolved.
- [ ] Run the release workflow in dry-run mode.
- [ ] Merge the reviewed branch to main; identify the exact final source commit.
- [ ] Build/release 0.2.0 using the established signing and publication process.
- [ ] Verify dependencies resolve from Central in a clean consumer environment.
- [ ] Publish the matching plugin and verify Marketplace availability/installation.
- [ ] Smoke-test a project generated by that installed plugin against Central.
- [ ] Record release tags, release notes and the next development version.

Release notes must clearly state that this is a language redesign with no promise
of source compatibility with the early 0.1.x syntax. No real deployment or remote
publication is part of the consolidation patches themselves.

## After 0.2.0

Keep these visible, but do not fold them into consolidation:

- Inbound ports/adapters and REST endpoint/controller declarations.
- MCP tools backed by controlled usecases.
- Kafka/RabbitMQ and other standard messaging adapters, including replication needs.
- AI-assisted authoring: current specifications, examples, prompts/tooling and
  validation feedback; evaluate retrieval support against real authoring workflows.
- Further integrations such as MapStruct convenience, asynchronous ports, explicit
  retry/idempotency policy, alternative query sort modes and other deferred features.

Persistence/migration correctness and architectural consistency remain ongoing
quality work, not one-time boxes checked by this release.

## Documentation consolidation evidence

README and site entry points share an executable delivery story; the reference is
rewritten for 0.2.0 and linked to one normative specification index. FAQs and a
contributor index separate audiences. Historical progress notes were consolidated;
old public URLs remain navigation pages. DocumentationExamplesTest verifies the
entry models and generated Java. The local link checker covers targets/anchors.
Existing mapping and REST tutorial execution tests remain in the verification set.
Website rendering, external URLs and full PostgreSQL/installed-IDE acceptance remain
separate release checks. Repository links in published docs currently target
feature/redefining_the_language; retarget them to the released source when merging.
The release workflow must include runtime-http/runtime-json in its publication
scope; Marketplace automation is still a planned release task.

## Adapter configuration consolidation

Outbound adapters now support typed required properties with explicit keys and
startup conversion. Inline/external behavior shares configuration access through
self. REST bases can use properties without URI beans. Dispatch and the HTTP
tutorials demonstrate binding, including environment overrides. Tests cover
Spring construction, redacted failures, all supported scalar types, external behavior
and REST base completion/navigation. Configuration does not add hot reload or
credential rotation; client-specific tuning remains explicit Java configuration.
