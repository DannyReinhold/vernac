---
title: Vernac 0.5.0 release preparation
---
# Vernac 0.5.0 release preparation

Status: **release candidate preparation, not a publication record**.
Source branch: feature/0.5.0. The source revision remains 0.5.0-SNAPSHOT; the release
workflow selects 0.5.0. [Release notes](../maintainers/releases/0.5.0.md) define the
scope; [release operations](../maintainers/releasing.md) define publication/recovery.

## Scope freeze

Finish verification and documentation. Defer rename/refactoring, HotSwap, broader
adapter families, SaaS capabilities and additional modeling features. No new
language contract is introduced by this preparation patch.

The documentation baseline is 0.5.0. Historical release records retain their old
versions. The extra wizard experiment under examples/test-getting-started duplicated
the maintained minimal template and has been removed, together with debugger-local
IDE metadata. The official examples and templates remain the supported routes.

## Acceptance

Use JDK 25 and Maven 3.9.16. Configure the disposable PostgreSQL server and
VERNAC_PG_TEST_URL/USER/PASSWORD as described in [the example guide](https://github.com/DannyReinhold/vernac/blob/main/examples/README.md).
Never point test tools at a production database.

```text
mvn clean install
python tools/check-doc-links.py
python -m unittest discover -s tools/tests -v -b
```

- [ ] Full reactor build passes with required PostgreSQL suites executed, not skipped.
- [ ] Release gate sees debugger/JDI, source mapping, MVC/starter, implementation
  contract, documentation and live-API/native-PSI suites. Missing reports fail the gate.
- [ ] Standalone examples pass their applicable checks, especially debugger, basic
  template, Dispatch, persistence-demo and persistent-modules; see each README.
- [ ] A newly installed candidate completes the compact IDE check below.
- [ ] The website has usable desktop/mobile navigation, working internal links and
  readable DDD chapters; beginner model blocks generate successfully.
- [ ] Candidate contents contain no migration experiments, secrets, target files or
  IDE-local project metadata. Existing applied migrations remain intact.
- [ ] Vernac Release dry_run=true, version=0.5.0 passes on the pushed candidate SHA.

Only mark a check passed from actual evidence. Compiler/native PSI tests do not
prove every installed-IDE popup or gesture. A passing local build with skipped
PostgreSQL tests is not release acceptance.

## Compact installed-IDE check

1. Create a fresh Spring Boot Web Application. With Docker running, open its main
   class and Run without selecting a profile. Add, complete and reopen a task.
2. Add an unsaved VO and list in a valid namespace. In a Java behavior, select the
   type by prefix and complete its factory without first generating Java. Check a
   repository dependency and an inbound mapping's DTO factory as representative
   application/integration paths. No technical package prefix should be required
   where the type is already visible by the model's normal rules.
3. Introduce a local declaration error. Check that independent valid types remain
   usable; correct it, build and repeat lookup without duplicate/stale suggestions.
4. In examples/debugger, stop in a behavior, inspect locals, evaluate an expression,
   and step into a helper. Check source navigation and the contributor Java view.

Report remaining limits plainly. Do not silently broaden the promised scope to
unmarked handwritten Java files, remote deferred call stacks or automatic HotSwap.

## Publish after acceptance

1. Merge the tested feature/0.5.0 commit into main and record the release SHA.
2. Run Vernac Release on main: version=0.5.0, dry_run=false.
3. Publish the existing validated Central deployment. Do not redeploy coordinates
   after a partial publication. Follow the recovery guide if Marketplace upload waits.
4. Confirm Marketplace approval and install the exact packaged plugin candidate.
5. Tag the release SHA as v0.5.0 and create the GitHub Release from the reviewed notes.
6. Choose and prepare the next development SNAPSHOT in a separate commit/branch.

No upload, tag, merge or publication is performed by this patch.

## Preparation evidence (2026-10-18)

- Eight DocumentationExamplesTest cases passed with zero failures/errors/skips.
  Both DDD beginner models generated Java that compiled for release 21. The existing
  entry-point story, AI reference and tutorial models were checked in the same suite.
- Twelve offline release-tool/navigation tests passed. The documentation checker
  validated 791 local/repository-main links with zero failures.
- Four desktop pages (home, DDD introduction, documentation map and FAQ) and mobile
  navigation were checked in a Chromium preview. Active destination, keyboard menu,
  skip-link focus, expandable contents and horizontal overflow checks passed. The
  preview used the actual Liquid layout/CSS with a local Markdown renderer; it was
  not the authoritative GitHub Pages Jekyll build. Inspect the deployed pages too.
- A full reactor/PostgreSQL run, standalone example runs, freshly packaged plugin
  installation and the release workflow dry run remain required. None is claimed
  by these documentation-focused preparation checks.
