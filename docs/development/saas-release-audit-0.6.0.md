# Vernac 0.6.0 completeness audit

Recommendation: **NO-GO**. This is a separate assessment of the stacked implementation,
not a claim that creating its PR finishes the agreed scope. No merge, release,
Marketplace upload, external payment or external email is authorized by this record.

## Maintainer scope update — 16 November 2026

The maintainer requested all feasible implementation work and illustrated business
flows. The subsequent request explicitly authorizes completing the automated tests.
The independent suite now contains 29 isolated scenarios covering S01–S20, including
controlled time boundaries, financial failures/restarts and concurrent closure.
Real provider acceptance remains deferred; it does not count as successful verification.
See the [test matrix](../saas/testabdeckung.md) for precise variants and limits.

Application source `943bac624358602744684ecacd1ed7bf5169c7d2` now includes independent merchant refund authority,
optional operator capability/CLI, transaction-coordinated closure/new-charge guards,
retained invoice ownership after closure and isolated bounded maintenance stages.
The [SaaS guide](../saas/index.md) documents 20 use cases, customer journeys and
explicit developer/operator decisions with 18 editable/rendered static diagrams.

Earlier scoped checks on the predecessor worktree passed: 12 payment PostgreSQL
cases without skips; Identity/security and billing unit suites; 13 template checks;
and the packaged HTTPS/Chromium application with real restart, operator-only
idempotent partial/full refunds, correction issuance, paid-closure refusal, eventual
closure and byte-identical retained PDF/XML. No real payment or email was sent.
These scoped local checks are not comprehensive acceptance or whole-release GO.

F03's missing implementation has been addressed and its targeted scenarios passed.
Later comprehensive operator/lifecycle review belongs to F01/F05; actual provider,
fiscal/archive and distribution approval remain pending. Earlier findings below
are read with this dated scope update.

## Source and evidence inspected

Identity/Auth is integrated through #81. Subscriptions #82, restrictions #83,
payments #84, invoicing #85 and canonical repository extensions #86 are prerequisite
PRs. The independent consumer and explicit source-shared SaaS Wizard are in #87.
All remain subject to their actual final-source checks. Historical evidence does
not automatically attest a changed successor commit.

The original interrupted consumer worktree was recovered. A fresh local reactor
unit build passed; database suites skipped in that unit run were excluded from
claims. Eleven payment processing tests executed on PostgreSQL 17.11 without
skips, including unresolved-payment lifecycle fencing. The actual consumer now
starts with HTTPS and a restricted database role. Thirteen focused template checks
passed, including two SaaS tests and all eleven existing basic/Spring template
checks. The packaged plugin/Wizard workflow also passed on `ae6093c`.

The first consumer browser run found implicit URL-parameter binding in the
separately compiled MVC artifact; explicit `@PathVariable` names fixed that.
The next reached concurrent Free capacity, paid activation and the protected PDF,
then found XML incorrectly passed through a Base64 decoder. The retained invoice
XML is UTF-8 text; its download adapter was corrected in `51f98d5`. The final application source `51f98d5a2d656900a48e0d9017b46d5ef3f4e221`
passed [the complete restricted PostgreSQL/HTTPS/Chromium consumer run](https://github.com/DannyReinhold/vernac/actions/runs/43839846054).
It verifies registration, concurrent Free capacity, operation/quota limits, paid
activation, idempotent checkout, owned PDF/XML, foreign access denial, member role
separation/revocation, cancel/resume and actual restart with immutable byte hashes.
[Packaged plugin/Wizard checks](https://github.com/DannyReinhold/vernac/actions/runs/43839846020),
subscriptions and payment-provider workflows passed at the same source. Neither
previous failed run is counted as a passing journey.
An unrelated tenant job hit Maven Central HTTP 429 before running its tests;
only that failed job was restarted. Its independent Identity browser job passed.

## Findings that block GO

| ID | Finding | Required closure |
| --- | --- | --- |
| F01 | Independent automated whole-SaaS scenarios are implemented; exact release-candidate evidence and final review remain pending. | Passing, unskipped browser/DB evidence for J01–J09 with relevant negative cases; inspect actual UI. |
| F02 | Real Mollie, Stripe and Brevo sandbox attestations are absent. Local simulations and loopback protocol tests do not prove provider-account configuration. | Explicit sandbox credentials/configuration and retained verified test results; no invented external delivery or charge. |
| F03 | IMPLEMENTED; targeted checks PASS. Explicit merchant authority, safe closure/new-charge coordination and retained invoices are present. No automatic refund policy is invented. | Automated operator/lifecycle scenarios now exist; exact-candidate final review remains part of F01/F05. |
| F04 | Final supported invoice scope, seller eligibility, archival/fiscal history and operational acceptance are not approved. | Review retained fiscal snapshots, correction history and intended deployment/archive policy within the supported domestic EUR scope. |
| F05 | Release distribution and exact-candidate acceptance are incomplete. | Fresh installed-IDE smoke, final plugin ZIP and module dependency inventory, all required reports, matching source/artifact digests and maintainer GO. Verify reproducibility or promote the actual approved artifacts; do not substitute a rebuilt candidate. |

The machine-readable [inventory](saas-release-inventory-0.6.0.json) deliberately
retains PARTIAL/OPEN requirements. Every A01–A23 and J01–J09 must have immutable
source-bound evidence before VERIFIED. D01–D14 need reconciled accepted policies;
older planning phrases must not be treated as blanket acceptance.

## Implemented release protection

`tools/release.py` includes every aligned SaaS artifact, including nested reactor
paths, in the 0.6.0 publishing/availability inventory. Pre-0.6 plugin recovery
retains its original coordinate inventory. Required test execution now includes
subscriptions, restrictions, payments, invoicing, billing pages, all 29 named
SaaSScenarios (with complete S01–S20 tags), SaaS Wizard and
canonical repository/query proofs.

`python3 tools/release.py acceptance --version 0.6.0 --evidence release-evidence/acceptance.json`
checks an externally prepared evidence bundle. Candidate creation automatically
requires this gate from 0.6.0 onwards. Missing evidence blocks both dry-run
candidate approval and publication; it never becomes simulated success.
The release workflow can download an explicitly selected
`vernac-acceptance-VERSION` artifact by `acceptance_run_id`. It cannot manufacture
reviewer approval or provider attestations.

The repository inventory itself must also say GO with no open requirements,
decisions or blockers; an external bundle cannot override an unresolved source
finding. The bundle schema is exercised by `tools/tests/test_release_acceptance.py`:

- `schema_version: 1`, release `version`, exact `source_sha` matching `GITHUB_SHA`.
- Exactly A01–A23/J01–J09 under `requirements`, each VERIFIED with evidence keys;
  exactly D01–D14 under `decisions`, each ACCEPTED; no blocking findings.
- `evidence` maps stable keys to PASSED source-bound records (`kind`, `file`,
  SHA-256). JUnit reports must be nonempty with no failed/error/skipped cases;
  every required release suite must be present. Browser/review/sandbox/IDE records
  name their reviewer. A09 requires Mollie and Stripe sandbox records; A11 requires
  Brevo; A16 requires installed-IDE smoke evidence.
- `artifacts` maps all published module names plus `vernac-intellij` to existing
  files/digests. Module JAR names carry the exact release version; the root POM and
  actual plugin ZIP are included.
- `dependency_reports` maps every JAR/plugin module to its retained Maven resolved
  classpath report and digest. Generate these during the source build with
  `mvn -Drevision=VERSION dependency:build-classpath -Dmdep.outputFile=target/release-classpath.txt`.
  `dependencies` must exactly cover the union of resolved JAR paths with their
  SHA-256 digests. Actual dependency bytes are rechecked.
- `approval` names the maintainer, GO, exact source SHA and plugin ZIP digest.
  This is retained evidence of approval, not an instruction to approve it.

Evidence/report/artifact paths stay inside the reviewed build workspace. Absolute
Maven dependency paths must point to the resolved JARs present in that build.
Archive the bundle with reports and the actual approved artifacts; raw runtime
credentials, account secrets and provider keys never belong in it. The archived
plugin candidate retains its acceptance bundle digest and source/candidate binding
for delayed Marketplace publication.

The gate tests remove every required capability, reports and dependency entries;
change source/artifact/dependency hashes; inject skipped tests/unknown decisions;
replace a real provider with a simulator; omit approval; and attempt path escape.
All must fail. A fixture-only passing gate test does not approve a real candidate.

## Automated test continuation — 16 November 2026

Local validation of the application tree matching `943bac624358602744684ecacd1ed7bf5169c7d2`:
all 29 independent scenarios passed, no retries/skips, in 361 seconds; both evidence-gate
unit tests and both source-shared SaaS Wizard tests passed. All 28 Python tooling
tests (including scenario-evidence rejection cases) pass. Local documentation links pass.
Fresh GitHub checks and exact release-candidate evidence remain separate.
The application CI replaces the dependent browser scripts with one fresh restricted
PostgreSQL database and HTTPS application per Playwright scenario, no retries, and
sanitized JSON/JUnit evidence. A test-only launcher controls time and provider-edge
faults; it cannot enter the production executable JAR. PostgreSQL permission failures
prove that unrelated worker phases continue. Concurrent checkout/closure must both
wait on an actual database lock before release.

The release gate now requires SaaSScenarios and checks its exact E01–E29 identities,
case count and all S01–S20 tags. Duplicate/missing cases and false aggregate-green
reports are rejected. Local upgrade evidence remains limited to a restart/Flyway
replay of this build against populated data; it is not historical-version upgrade
acceptance. Under the subsequent maintainer decision, historical pre-0.6.0 upgrades
are outside supported scope; see the [mandatory upgrade policy](upgrade-policy.md). Real Mollie/Stripe/Brevo, fiscal/archive review, installed IDE and final
source/artifact approval remain separate release requirements.


## Upgrade support decision — 16 November 2026

0.6.0 is the first supported baseline. A missing 0.5.x upgrade test is no longer a
release blocker. The candidate snapshot, automatic restore and post-restore business
checks are the initial baseline work; final release still requires freezing a fixture
from the approved build and retaining its source/artifact evidence. `UpgradeBaseline`
is a required suite. Later versions must test direct migration from every supported
frozen baseline, with explicit versioned data steps where needed. See the binding
[upgrade policy](upgrade-policy.md); same-version restoration is not historical proof.
