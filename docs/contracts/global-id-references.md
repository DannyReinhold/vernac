---
title: Explicit global aggregate ID references
---
# Global ID references

The field suffix `references Target` declares an immutable nominal ID reference
between two explicitly global, repository-backed aggregates in the same compilation
artifact. It is needed for global access metadata such as an AccountMember pointing
to Account and Identity, independently of product tenant graphs.

```vernac
namespace example.access;
id AccountId;
id MemberId;
global aggregate Account[AccountId]();
repository Accounts for Account {}
global aggregate Member[MemberId](AccountId account references Account);
repository Members for Member {}
```

Normal omitted field naming also applies, for example `AccountId references Account`.
The target resolves through the ordinary type scope; its exact nominal ID type must
match the field. Required/optional nominal IDs are supported. Mutable, non-ID,
transient, tenant, cross-artifact and non-root uses fail at shared analysis. This is
not aggregate containment, cascading deletion or tenant selection. The target remains
a separately loaded aggregate. The field's generated Java API is the ordinary ID API.

Generated PostgreSQL schema adds an immediate, non-cascading foreign key to target id.
Migration candidates, snapshots, module packaging, startup/schema-check inspection and
drift detection all use that same expected constraint. Adding a reference can fail on
existing orphans and lock/scan tables; review deployment data and ordering explicitly.
Existing snapshots/migration bytes are unchanged. No automatic foreign key is inferred
from all fields that happen to use an ID type.

The coordinated unpublished 0.6.0 metadata baseline is module-contracts-13: reference
identity participates in field contracts, fingerprints, compatibility checks and
read-only dependency views. Exporting a referenced aggregate field requires exporting
its target aggregate and nominal types. Producer and consumer artifacts must be rebuilt;
published 0.5.x is unchanged. Imported contracts preserve references without claiming
ownership of the producer's tables.

Three compiler/module tests prove shape rejection, tracked schema changes, nominal
resolution, publication/consumer round-trip and exported-target requirements. One real
restricted-role PostgreSQL test proves orphan/deletion rejection, migration replay and
schema-check detection after a foreign key is dropped. Native installed IDE acceptance
is separate from shared compiler/parser diagnostics.
