# Versioned Vernac modules

Status: the ID/VO/enum/value-collection type-module slice is implemented in
0.4.0-SNAPSHOT. Functional-module activation and independent module migrations remain
accepted designs awaiting implementation.
This document defines the agreed observable semantics. Sections explicitly marked
open are implementation prerequisites, not existing features. Examples using IDs, VOs, enums and their collections are supported. Examples of
functional modules describe target scope, not current executable syntax.

## Purpose and boundaries

A module is a versioned Maven artifact containing reusable Vernac-based code and
an explicit public Vernac contract. The primary use is a reusable generic subdomain
such as subscriptions, billing or identity, or a library of shared domain values.
The mechanism MUST also permit composition inside a modular application. A Maven
module does not itself establish a bounded context, process boundary or microservice.

Applications MAY adopt exported values as their own domain vocabulary without a
redundant mapping. Where the application assigns a different meaning, it SHOULD
use its own port and an ACL to translate to the module's public operations. The
module system MUST NOT infer semantic equivalence or enforce automatic conversions.

## Identity and export declaration

The project-level export file is `vernac-module.vmodule`, beside the Maven POM.
It has a dedicated IDE file type and no namespace declaration. One Maven artifact
contains one module definition. This initial producer slice must contain only type
definitions (including internal values), not functional definitions needing activation. The initial implementation exports only IDs, VOs,
enums and their value collections; other kinds remain future scope.

The complete future-facing declaration is:

```text
module example.subscriptions {
    export example.subscriptions.SubscriptionId;
    export example.subscriptions.SubscriptionPlan;
    export example.subscriptions.Entitlement;
    export example.subscriptions.StartSubscription;
    export example.subscriptions.CheckEntitlement;
    export example.subscriptions.SubscriptionNotifications;
}
```

The module name is a stable logical identifier. It is not a namespace, Java package,
SQL schema, type-name prefix or Maven version. One module MAY contain multiple
namespaces. Identifiers use the existing Java-compatible namespace-name rules and must be qualified.

| Identity | Responsibility |
| --- | --- |
| Module identifier | Identifies the reusable logical component |
| Maven group/artifact/version | Identifies its concrete published artifact; Maven selects the version |
| Namespace plus definition name | Identifies an individual Vernac definition |

Version MUST come from the effective Maven project, not a duplicate declaration.
Distinct resolved artifacts claiming the same module identifier MUST be rejected.
Ordinary repeated dependency paths to the same resolved artifact are not duplicates.
Local and dependency definitions with the same qualified identity MUST be rejected;
resolution MUST NOT silently prefer one. Conflicting dependency identities MUST
also be rejected even when their Java representations happen to look identical.

Only explicitly exported definitions are importable by an external Vernac project.
Initially exports enumerate individual definitions; wildcard exports are excluded.
IDs, values, enums, collections, entities, aggregates, usecases, domain services,
events, repositories, ports, adapters and listeners MAY be explicitly exported.
Exportability does not make every category an advisable integration boundary.

Public values and usecases are recommended for encapsulated functional modules.
Ports expose deliberate extension points. Exporting aggregates/repositories is an
explicit choice of tighter coupling. An adapter export does not activate it.

An exported contract MUST be closed over the types exposed in its public fields,
parameters, results and operations. A missing required export is a source diagnostic;
the compiler MUST NOT silently export another authored definition. Collections are
independent authored definitions and require their own exports. Generated companion
contracts required to use an exported definition, such as a usecase Result or an
Access interface, accompany its export without separate technical export entries.
References to types provided by other modules require a resolvable public contract;
transitive availability follows the resolved compile classpath as specified below.
A module MUST NOT re-export definitions owned by another module.

## Packaging and metadata

A module is built from ordinary Vernac sources and optional handwritten Java.
Its JAR MUST contain all implementation classes and resources required at runtime,
including internal generated definitions and behaviors. Export visibility MUST NOT
cause necessary implementation classes to be omitted.

The Maven plugin MUST generate versioned module metadata from the analyzed model.
The author MUST NOT manually maintain a second type specification. Metadata MUST
supply enough information to resolve and check public contracts without recompiling
module source, including:

- module identity, effective artifact version and metadata-format version;
- exact public definition identities, categories and generated Java names;
- field/signature types, names, optionality and collection kind/element types;
- generated factories/getters/behavior contracts and usecase Result shapes;
- structural value information required for application persistence flattening;
- dependency requirements and persistence ownership information;
- artifact-relative source locations for editor navigation.

The consumer MUST reference the compiled Java classes. It MUST NOT regenerate them,
textually include module source, overwrite dependency files or infer missing contracts
from Java class-name conventions. Metadata loading MUST NOT execute module code.
Metadata does not need to reproduce arbitrary Java implementation bodies or evaluate
business validations; those execute in the module's compiled factories and behaviors.

Sources SHOULD be supplied in the matching Maven sources artifact. Source attachment
availability MUST NOT affect compilation or type identity. Metadata and source paths
MUST NOT contain absolute paths from the publisher's machine. The initial descriptor is `META-INF/vernac/module.json`, formatVersion 1 and
contractVersion `type-modules-1`. Source attachments use `vernac/<namespace path>/<file>`.
Generated companion Java signatures are recorded with explicit field/method contracts.
No activation or migration instructions are emitted in this first format.

Export visibility is a Vernac compiler contract, not a security boundary or a JPMS
access-control guarantee. Internal Java classes can be public where runtime wiring
requires it; arbitrary handwritten Java may still access them.

## Consumption and immutable definitions

A Maven dependency is sufficient to make its exported contracts available to normal
Vernac imports. No copied source tree or parallel dependency-version list is required.

```text
import example.subscriptions.SubscriptionId;
import example.subscriptions.StartSubscription;
```

The Maven plugin MUST use Maven's effective resolved compile classpath, including
transitive compile-visible artifacts. Test-only and runtime-only artifacts do not
become available for main-source imports.
Unsupported metadata, missing required artifacts, duplicate identities and attempted
imports of internal definitions MUST produce clear diagnostics identifying origin.
No implicit aliasing, namespace relocation or local extension of external definitions
is allowed. Applications MAY wrap values, add their own types and implement declared
ports. Changing a published definition requires a new module version.

## Transitive availability and ownership

If A depends on B and Maven places B's dependency C on A's compile classpath,
A MAY import C's exported definitions without a mandatory duplicate direct Maven
dependency. Declaring C directly when A uses it directly is an application dependency
management choice, not a Vernac language requirement.

Each module exports only its own definitions. B MAY expose a public type from C in
its public signatures only if C exports that type. B records this external contract
requirement; it does not acquire export ownership, rename it or manufacture a copy.
Several modules referring to the same resolved definition are not duplicate providers.

A file importing Invoice from B does not need imports for every type contained in
Invoice. It needs an ordinary import when it explicitly spells a simple external
name such as Money. The compiler still resolves the complete contract graph.
Module identifiers do not participate in import spelling or type identity.

A missing public dependency caused by exclusion or optional dependency handling MUST
produce a diagnostic naming the missing origin and the consuming module/definition.
Selecting artifact versions remains Maven's responsibility; verifying their contracts
is Vernac's responsibility. No second dependency resolver is introduced.

## Public contract compatibility

Consumers record the external definition contracts used when they were compiled,
including the structural dependencies needed to interpret those definitions. A
consumer MUST NOT pin an entire provider API merely because it uses one exported VO.
Stored expected contracts, not only hashes, are required for compatibility diagnostics.

A deterministic fingerprint describes each public contract. Equal fingerprints are
an efficient unchanged-contract check. Different fingerprints MUST trigger a
structured expected-versus-resolved comparison, not an unconditional rejection.
The type-modules-1 fingerprint is SHA-256 over a canonical JSON tuple containing
identity, kind, Java name, ordered fields/constants, collection element/kind, sorted
public behaviors and sorted generated Java-member contracts. Origin/source/version
information is outside that tuple.

Fingerprint inputs include type identity/category, field types/names/optionality,
collection kind/element, enum constants, generated and authored public signatures,
factory parameter order, usecase Result shapes and required generated companion
contracts. Published references use qualified type identities and exact Java contracts.

Fingerprints MUST exclude comments, formatting, source positions/paths, documentation,
Maven artifact version, private helpers and their names, implementation bodies and
validation expressions. Reordering independent definitions or public methods MUST NOT
change a fingerprint. Parameter order and other observable generated contract changes
MUST remain significant. Internal changes that alter a generated public interface are
not private noise simply because their source declaration appears internal.

The initial compatibility checker accepts a deliberately bounded set of additive
changes. It is not a general Java binary/source compatibility analyzer.

| Provider change | Initial treatment |
| --- | --- |
| Changed body, comments or private helpers; public contract unchanged | Accept structurally |
| New unrelated exported definition | Does not affect existing definition requirements |
| New uniquely named public method on a generated class | Accept only if the generated contract comparison finds no collision or new required implementation obligation |
| New required port/interface operation | Reject for existing consumers that may provide implementations |
| New overload of an existing public method | Conservatively reject; Java calls may become ambiguous |
| Changed required Read/Write/Access companion interface | Check its obligations explicitly; no blanket additive acceptance |
| Added, removed or changed field in a consumed VO | Reject; rebuild the consumer against the new structural/factory contract |
| Removed or changed required signature/type/optionality | Reject |

Enum expansion and other changes outside these safe initial cases MUST NOT silently
be classified as compatible; exhaustive Java switches are one reason to stay
conservative. For generated interfaces, introducing an abstract method is a new
implementation obligation, even if an existing method is retained. This feature
MUST NOT infer arbitrary Java body behavior or hand-written dependency usage from
signatures alone. The tracked-contract boundary and any explicit manual Java contract
requirements must be fixed in the metadata/build design.

A consumer B compiled against C 1.2.0 MAY use resolved C 1.2.1 if its recorded required
contracts are unchanged or accepted by this structural comparison. Different Maven
versions are not intrinsically incompatible; equal versions are not a substitute for
contract checks. A fingerprint is not a security signature.

On incompatibility, diagnostics MUST identify consumer module, provider coordinates,
built-against and resolved versions, qualified definition, concrete changed member,
and an actionable remedy. Example:

```text
Module example.billing requires a different contract for example.money.Money.
Built against: example-money 1.2.0
Resolved:      example-money 2.0.0
Changed field: currency is now optional.
Use a compatible dependency version or rebuild example.billing against this contract.
```

This is structural assurance, not proof of semantic compatibility. Validation changes,
side effects and business behavior may differ without a fingerprint change. Module
authors and applications remain responsible for versioning and integration tests.

### Update scenarios

1. **Bugfix:** C changes private helpers or method bodies. B's required public
   contracts are unchanged; B need not be rebuilt for structural compatibility.
2. **Independent export or safe additive method:** C's descriptor changes, but B's
   requirements remain satisfied. Accept without demanding an identical whole-module
   fingerprint.
3. **New field in a shared VO:** B's stored factory/structural requirement no longer
   matches. Rebuild B against C; do not synthesize defaults for old factory calls.
   Application-owned persistence then requires its own schema diff/backfill review,
   independently of C's internal migrations.

## Runtime activation

Importability and runtime activation are distinct. Adding a dependency MUST NOT by
itself activate module beans, adapters, workers or database migrations. Value-only use
requires no activation. Functional modules require explicit application activation.

Activation MUST provide an intentional Spring Boot integration that:

- makes selected module functionality available before requests are accepted;
- checks required configuration and required extension-port implementations;
- reports missing or ambiguous wiring with module and contract identity;
- does not invent a no-op implementation of a mandatory port;
- makes optional extension points optional only by an explicit module contract.

The default for an activated persistent module is the application's DataSource and
transaction manager. Its usecases keep REQUIRED semantics, joining an outer
application usecase transaction. Separate databases and distributed transactions are
not implied. Module/application repositories retain their transaction requirements.

### Activation dependencies

Compile visibility MUST NOT imply runtime activation. A module MUST explicitly record
which other modules supply required runtime functionality. Activating B activates the
closure of these required functional modules, not every transitive Maven dependency.
The application must be able to inspect that plan before execution. Missing required
modules/configuration or cycles MUST stop startup with useful diagnostics.

Using C's VO in B's own persisted aggregate requires neither C beans nor C internal
tables. B owns those flattened columns and migrations. Calling C's usecase requires
C's functionality to be activated. Only activated persistent modules participate in
the migration/validation plan; dependency modules precede their dependants. A port
requiring an application implementation does not by itself imply another module.

The activation configuration, Spring bean naming/selection, optional-port declaration,
component scanning and initialization mechanism remain open. A monolithic dependency
MUST NOT trigger accidental broad component scanning of its internals merely because
the application imports a value. An implementation may require a separate activation
artifact if necessary; artifact topology is not settled by this contract.

## Persistence ownership and migration histories

Existing namespace-based quoted SQL schemas and qualified table names remain in use.
Ownership MUST additionally be recorded and checked: the same SQL object cannot be
owned independently by an application and a module, or by two different modules.
Namespace naming alone is not a sufficient ownership check.

A persistent module owns its internal tables and ships its own reviewed, immutable
published migration history. Application generation MUST NOT include imported module
aggregates as locally owned tables or rewrite module migrations.

Each application/module migration stream MUST have both isolated resource locations
and a distinct Flyway history identity. Separate directories alone do not separate
version histories. An independent V1 in two streams is therefore permitted. Histories
share the default application datasource but are configured and coordinated separately.
Their exact resource paths and stable SQL history identifiers remain open and MUST
respect PostgreSQL identifier limits without collisions or truncation surprises.

One explicit coordinator MUST order migration/validation before dependent runtime
components start. Required modules precede dependants; applications follow module
migrations. Dependency cycles MUST be diagnosed. No independent auto-configurations
may race to execute migrations. Cross-owner migration dependencies and foreign-key
ordering beyond that dependency graph need an explicit policy before implementation.

Supported intended modes are explicit migrate-on-start for local use and separate
deployment migration followed by validate-only startup. Neither mode silently repairs,
baselines, cleans or downgrades an existing database. Failed migration or incompatible
schema stops startup. Switching history configuration MUST NOT reset installed state.

There is no promise of a single transaction across all histories. Earlier successful
migration steps may remain applied if a later step fails. Usecase REQUIRED transactions
are separate from deployment migrations. Retry starts from the recorded installed
state; published migrations and Flyway checksums MUST remain intact.

The module's required schema state MUST be checked independently of its Maven version.
Starting older module code against a newer incompatible schema MUST NOT silently pass.
Initially the installed owned schema MUST match the schema expected by the activated
artifact, with migration histories validated. A newer schema is not implicitly
compatible with older code. Backward/rolling-update compatibility remains a separate
future contract, not an inference from matching public API fingerprints.

Review, testing and approval before applying any migration remain the application's
operator/developer responsibility. A published module does not provide a guarantee
for existing production data, locking, execution time or deployment compatibility.

## Exported values persisted by the application

Applications MAY store exported IDs, enums, VOs and permitted value collections in
locally owned aggregates. Existing domain-containment rules remain authoritative;
a module import does not allow aggregate containment otherwise forbidden by Vernac.

External VO structure participates in the application's normal flattening and schema
fingerprint. A dependency update MUST therefore participate in local schema diffing.
Private module table models MUST NOT enter that diff.

Example lifecycle: an exported Address receives a new field.

1. The module author generates/reviews migrations for Address uses in module tables.
2. The application updates the module dependency; its compiler reads the new structure.
3. The application's migration generator detects Address changes in its own tables.
4. The application developer supplies required backfills and reviews/tests candidates.
5. Deployment executes module and application migration streams, then starts the code.

The same structural change can require two independently authored migrations. Module
SQL MUST NOT be copied or applied to application tables. Required new fields with
existing rows need explicit backfill decisions; module defaults MUST NOT be guessed
for application data. Migration generation remains a development/build operation,
not runtime generation of unknown SQL.

## IDE and LSP

Build and editor MUST consume the same effective module versions and public contract
index. Maven reload MUST invalidate stale module metadata. The LSP MUST NOT independently
choose artifact versions. Standalone LSP initialization also needs an explicit way to
receive a resolved module index; that mechanism remains open.

Completion MUST offer public dependency definitions. Go To Declaration SHOULD open the
matching Vernac source in a read-only dependency view. Without sources it MUST offer
a read-only contract view derived from metadata, visibly marked as such, rather than
pretending an implementation body exists. Origin information includes module identity,
Maven coordinates and version. Source positions must support the existing Unicode and
UTF-16 editor conventions. This does not imply cross-artifact refactoring of published
code or breakpoint support in dependency source.

## Implementation readiness and acceptance

The type-module slice covers publication, dependency imports, compatibility, external
value flattening and editor module-file support. Persistence ownership and functional
activation rules are agreed but their runtime implementation remains planned. The entire
functional-module feature is NOT implementation-ready until the open items above are
settled. In particular, activation/wiring and migration ordering are part of usable
module behavior, not optional polish.

Implementation should proceed in bounded slices:

1. Fix export-file layout, canonical descriptor format and the tracked-contract
   boundary for handwritten Java; implement the agreed visibility and compatibility checks. Build/consume a separate value-module JAR without regeneration.
2. Integrate external values with semantic resolution, persistence flattening, schema
   diff and the shared editor index, including attached/fallback source navigation.
3. Fix activation, bean wiring and migration coordinator contracts; expose functional
   entrypoints and required extension ports.
4. Test two persistent modules plus an application, fresh install, restart, upgrades,
   partial migration failure, incompatible startup and external VO backfills.

Do not advertise all slices as supported when only metadata imports work. Tutorials,
AI reference and plugin release notes must distinguish implemented and planned scope.

See [capabilities planning](../development/capabilities-and-usecase-authorization.md)
for subsequent SaaS authorization/subscription features, which are not prerequisites
for implementing the module mechanism.

## Current implementation details

The Maven compile goal validates exports before writing the module descriptor and
registers its generated resource directory for ordinary JAR packaging. No export file
means no published module contract. Generated dependency classes are never emitted
into the consuming project's Java output. Ordinary applications may consume modules
without declaring a module of their own.

The first slice reconstructs immutable structural AST nodes from typed metadata for
the existing resolver and schema builder; it does not parse implementation source
or run producer behaviors during compilation. Public behavior signatures and
generated Java contracts are retained; bodies and private helpers are not imported.
Required contracts are tracked through model/signature references and explicit VO
Java imports. Arbitrary references hidden in Java bodies are outside that analysis;
structural acceptance is not a claim of complete binary compatibility.

Maven writes `target/vernac-module-index.json` and source-attachment hints as the
editor's resolved input. Build the consuming project after dependency changes. The
LSP reloads the index on file-watch/editor events; it does not independently download
or choose dependency versions. A matching locally available sources JAR provides
original type navigation; otherwise a marked read-only dependency contract view is
materialized under target. Public behavior implementation bodies are not invented
in fallback views. Cross-artifact refactoring remains out of scope.

The producer registers `target/generated-module-sources` as a sources root so that
an explicitly configured maven-source-plugin execution can attach original Vernac
sources. See the separate [type-module example](../../examples/type-modules/README.md).
