---
title: Module authorization contracts
---
# Module authorization contracts

Status: implemented in **0.6.0-SNAPSHOT**, metadata contract **module-contracts-11**.
This chapter supplements [modules](modules.md), [usecase requirements](usecase-authorization.md)
and the [actor/policy runtime](usecase-authorization-runtime.md). MUST, MUST NOT and
SHOULD are normative. The [walkthrough](../tutorials/module-authorization.md) shows the workflow.

## Publication and visibility

A module MAY export a `permission` declaration. Its Java representation is the generated
`<namespace>.authorization.<Name>` enum implementing `PermissionKey`; each stable key is
`<Vernac qualified name>.<member>`. Import the Vernac name, not the Java package name.
An export grants no permission to any actor.

An exported usecase MUST retain its access requirement. Every permission type used by
that requirement MUST be explicitly exported by its owning module. Normal public
signature closure still applies, including resource input types. The publisher MUST NOT
silently export a referenced local definition. Foreign reexports remain forbidden;
ordinary transitive Maven resolution supplies the original owner's exported definitions.

An internal usecase MAY reference private permission/input types. Its access header is
recorded for compatibility inspection, but this MUST NOT make those types or operations
importable by consumer Vernac code. Internal identities can be visible in metadata;
module visibility is an API boundary, not source secrecy or a Java sandbox.

## Data representation

The format envelope remains version 1; its contractVersion is module-contracts-11.
Each exported Definition contains an authorization object. Non-usecase definitions use
OMITTED. A usecase preserves one of:

| Mode | Retained meaning |
| --- | --- |
| OMITTED | No requires clause; existing unprotected operation contract |
| PUBLIC | Explicit `requires public` |
| AUTHENTICATED | Explicit verified-actor requirement |
| PERMISSION | Qualified permission identity, member, optional input name and nominal input type |
| ALL / ANY | Ordered, nonempty child trees; ordinary short-circuit rules |

PUBLIC and OMITTED MUST remain distinguishable for explicit-access inspection. Group
order MUST remain significant: policy evaluation is ordered. An `on` reference MUST
bind to an existing required nominal ID/VO/enum input. Input name, optionality, page
shape and nominal type are checked; matching underlying UUIDs do not substitute for types.

The descriptor's usecaseAccess inventory records every local public/internal usecase's
identity, typed input signature and requirement tree. It MUST agree with the component
inventory and exported usecase contracts. Missing inventories, malformed trees,
invalid public permission members, resource mismatches and unsupported contract
versions MUST fail consumption. Private implementation bodies and policy decisions
are not metadata. Metadata contains no request values, actor credentials or grants.
Private declarations are validated by the producer compiler; the consumer validates
available exported contracts and private identity ownership. Loading a module still
requires trusting its Java implementation; metadata is not code verification.

## Dependency compatibility

Definition fingerprints include the canonical authorization tree. They exclude comments,
formatting, aliases after name resolution, documentation, implementation bodies and
private Java helpers. Permission member declaration order is canonicalized; enum value
order elsewhere retains its existing contract. Adding a permission member is structurally
compatible. Removing or renaming one requires review of stored grants.

An existing usecase requirement change MUST trigger a security-review diagnostic,
including changes to mode, permission member, group order, input binding or nominal
resource type. The checker deliberately does not attempt to prove arbitrary policy
changes logically weaker or stronger. A new public behavior or unrelated exported type
continues to follow ordinary additive compatibility rules.

A dependent module that uses a provider's functional API snapshots all that provider's
usecase access headers, including internal operations. A changed or removed existing
header MUST fail against the resolved provider and identify the dependent module,
operation and built/resolved versions. New usecases are additive. Internal usecase
renames/removal are conservatively reviewable, unlike private Java helper renames.
This inventories potential operations; it does not predict executed branches or check
all inner permissions at the outer entry.

A type/permission-only dependency keeps the referenced structural contracts and closure;
it does not snapshot unrelated functional operations or activate their implementation.
Snapshots are contracts, not exact artifact-version pins. Review a changed requirement,
rebuild/test its dependents, and publish them against the intended version. Do not
hand-edit dependency fingerprints or inventories to silence a mismatch.

## Activation and execution

Importing a permission or nominal value MUST NOT activate its module. Functional calls
use the existing explicit `.vapplication` activation mechanism. Activation registers
producer classes from the JAR; consumers MUST NOT regenerate those classes or strip
their guard code. Activated protected components require an AuthorizationGuard bean;
missing infrastructure fails startup. Bind verified actor resolution and policy explicitly.
Permission-only modules require no runtime activation or policy installation.

Activation MUST NOT evaluate user permissions. Every reached protected operation checks
at invocation, before validation/body/result, with its own nominal input binding. Outer
and inner calls retain the verified actor/tenant and Spring REQUIRED transaction.
Skipped branches perform no checks. Existing public-entry and nested-context semantics
apply unchanged. An application filter chain still owns login, endpoint authentication
and CSRF. Importing a module installs neither an allow-all policy nor a filter chain.

## Upgrade, tooling and evidence

Producers and dependent consumers MUST be rebuilt with matching tools after upgrading
from earlier development metadata. Do not rewrite frozen SQL, Flyway history or schema
snapshots merely for module-contracts-11: the persistence bundle format remains version 1
and this security metadata change alone creates no database column.
Retaining a module migration history after its last current table is removed MUST
retain vocabulary and public/internal access inventories too. Model/storage changes
still require the ordinary reviewed migration workflow.

Read-only contract views reconstruct permission declarations and usecase requirements.
Vocabulary hover and imported declaration navigation work from metadata without source
attachments; available source attachments locate exported permission declarations.
Permission-member/resource completion and native member references are available;
installed-IDE acceptance remains a separate release check.

Compiler tests check explicit closure, round trips, transitive references, compatibility
and malformed metadata. LSP tests check imported vocabulary, read-only navigation and
unsaved member diagnostics. ModuleAuthorizationPostgresTest packages and loads a real
producer JAR, activates it through generated bindings and checks actor/tenant/REQUIRED
behavior using PostgreSQL. CI requires the named cases to execute without skips.
These proofs do not complete identity/membership/role policies, the full SaaS assembly,
quotas or the full 0.6.0 release acceptance.
