# Vernac 0.6.0 completeness audit

Recommendation: **NO-GO**. This is a separate assessment of the stacked implementation,
not a claim that creating its PR finishes the agreed scope. No merge, release,
Marketplace upload, external payment or external email is authorized by this record.

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
| F01 | Whole-SaaS journeys still need complete final-source execution, including refusal/pending/recovery and operator/refund paths. | Passing, unskipped browser/DB evidence for J01–J09 with relevant negative cases; inspect actual UI. |
| F02 | Real Mollie, Stripe and Brevo sandbox attestations are absent. Local simulations and loopback protocol tests do not prove provider-account configuration. | Explicit sandbox credentials/configuration and retained verified test results; no invented external delivery or charge. |
| F03 | Merchant refund policy/operator integration and account/identity closure versus ongoing billing need explicit lifecycle review. | Merchant-only authority and recovery acceptance; prove closure cannot silently leave charges unmanaged. Customer membership ownership is not merchant refund authority. |
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
subscriptions, restrictions, payments, invoicing, billing pages, SaaS Wizard and
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
