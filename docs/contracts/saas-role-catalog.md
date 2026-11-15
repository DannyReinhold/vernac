---
title: Application-owned Identity role catalog
---
# Application role catalog

D05 was accepted by the owner on 15 November 2026. `RoleCatalog` loads exactly one
explicit classpath JSON resource or caller-supplied bounded stream. It does not scan
classpath catalogs, fetch remote resources or interpret Spring authorities. Pass the
available reviewed generated/application permission enum constants explicitly; arbitrary
`PermissionKey.of` strings are rejected as vocabulary declarations.

Format 1 is the accepted JSON `format`/`roles` format in the D05 decision. The parser
rejects unknown/missing/duplicate properties, trailing JSON, unknown format/permission,
duplicate role/permission keys, invalid qualified names and excessive input. Limits:
65,536 bytes, nesting 8, 128 roles, 128 permissions per role, 2,048 vocabulary constants
and 32 distinct assigned roles per membership. Returned role keys, assignments and
exact permission unions are immutable. An empty role set grants no product capability.
There is no wildcard, negative grant, hierarchy or owner/paid-plan bypass.

The stable SHA-256 catalog fingerprint is derived from sorted role names and exact
permission identities, independent of JSON ordering. Catalog updates are reviewed
application changes and restart; removed/unknown assigned roles fail closed instead
of silently disappearing. Persistent assignment/startup validation and account policy
are implemented in the following slices; this loader alone grants no access.

Three mandatory unit proofs cover immutable exact unions and stable fingerprints,
malformed/duplicate/unknown/oversized input, restricted vocabulary and unknown/duplicate
assignment rejection. This does not close persisted membership or provisioning acceptance.

The composed implementation, exact-source tests and remaining whole-release boundaries
are recorded in [final Identity/Auth acceptance](../development/identity-auth-acceptance-0.6.0.md).
