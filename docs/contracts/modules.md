# Versioned Vernac modules

Status: 0.4.0-SNAPSHOT supports value, entity/aggregate, collection, repository,
port/adapter and service/usecase export contracts. Persistent producers package a
matching schema/migration bundle, and consumers verify it before importing types.
Persistent and nonpersistent functions support explicit runtime activation with
coordinated histories. Module events, recorded payloads and synchronous/deferred receivers are supported.
Internal inbound HTTP adapters support explicit module activation.

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
contains one module definition. The producer supports IDs, VOs, enums, entities,
aggregates, their collections, repositories, ports, outbound adapters, services and
usecases and events. Internal synchronous/deferred listeners participate in activation.
Inbound HTTP adapters remain internal entrypoints; activate their module to expose routes.
Persistent activation is explicit and coordinates isolated module histories before application migrations.


An export declaration is:

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
contractVersion `module-contracts-9`. Source attachments use `vernac/<namespace path>/<file>`.
Generated companion Java signatures are recorded with explicit field/method contracts.
Descriptors include an independent runtime component/dependency plan. They contain no module migration instructions yet.

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
The module-contracts-9 fingerprint is SHA-256 over a canonical JSON tuple containing
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

Application composition is declared in `vernac-application.vapplication`. Generated
registration uses qualified Vernac bean identities and explicit port bindings,
including profile-dependent property selection. Dependency components are not broadly
scanned. Persistent initialization is coordinated through Spring Boot's Flyway lifecycle.
No separate activation artifact is required. Optional-port declarations are not part
of this release; mandatory dependencies must be satisfied explicitly.

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
The preparation contract below fixes resource paths and bounded history identifiers.
Their coordinated runtime application remains to be implemented.

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
index. After a dependency change the consumer MUST be rebuilt to refresh its resolved
index. The LSP MUST NOT independently choose artifact versions. Standalone clients use
the same build-written `target/vernac-module-index.json` and source-attachment index.
Watch/editor events reload these inputs. Completion/navigation requests also detect
index replacement or modification and invalidate cached dependency symbols.

Completion MUST offer public dependency definitions. Go To Declaration SHOULD open the
matching Vernac source in a read-only dependency view. Without sources it MUST offer
a read-only contract view derived from metadata, visibly marked as such, rather than
pretending an implementation body exists. Origin information includes module identity,
Maven coordinates and version. Source positions must support the existing Unicode and
UTF-16 editor conventions. This does not imply cross-artifact refactoring of published
code or breakpoint support in dependency source.

## Supported scope and acceptance

The 0.4.0 module scope includes publication, transitive dependency visibility, explicit
exports, structural compatibility checks, compiled implementation reuse, value flattening,
functional activation, selected outbound providers, independent persistence histories,
recorded/deferred events, internal listeners and inbound HTTP entrypoints. The module
completion checklist records regression coverage and operational boundaries.

The following are separate future features, not implicit module guarantees:

- SaaS authorization, subscription/usage policies and prebuilt provider capabilities.
- Cross-artifact refactoring, dependency-source breakpoints and remote processes.
- Distributed transactions, multiple application DataSources and tenant-specific routing.
- Automatically safe data migration, arbitrary handwritten-Java compatibility analysis,
  or automatic conversion between different bounded-context vocabularies.

See [capabilities planning](../development/capabilities-and-usecase-authorization.md)
for later SaaS features. Passing tests does not transfer migration review, deployment
approval or recovery responsibility from the application developer/operator.

## Current implementation details

The Maven compile goal validates exports before writing the module descriptor and
registers its generated resource directory for ordinary JAR packaging. No export file
means no published module contract. Generated dependency classes are never emitted
into the consuming project's Java output. Ordinary applications may consume modules
without declaring a module of their own.

The compiler reconstructs immutable structural AST nodes from typed metadata for
the existing resolver and schema builder; it does not parse implementation source
or run producer behaviors during compilation. Public behavior signatures and
generated Java contracts are retained; bodies and private helpers are not imported.
Required contracts are tracked through model/signature references and explicit VO
Java imports. Arbitrary references hidden in Java bodies are outside that analysis;
structural acceptance is not a claim of complete binary compatibility.

Maven writes `target/vernac-module-index.json` and source-attachment hints as the
editor's resolved input. Build the consuming project after dependency changes. The
LSP reloads the index on file-watch/editor events and detects changed build indexes
on completion/navigation requests; it does not independently download or choose versions. A matching locally available sources JAR provides
original type navigation; otherwise a marked read-only dependency contract view is
materialized under target. Public behavior implementation bodies are not invented
in fallback views. Cross-artifact refactoring remains out of scope.

The producer registers `target/generated-module-sources` as a sources root so that
an explicitly configured maven-source-plugin execution can attach original Vernac
sources. See the separate [type-module example](../../examples/type-modules/README.md).

## Learning the lifecycle

The [module tutorial](../tutorials/type-modules.md) walks through type publication,
consumption, initial application migrations and an external-value upgrade. It also
explains executable functional activation and the persistent-module migration
lifecycle, including explicit backfills for existing application/module rows. This contract remains the normative source.

## Application activation and adapter selection

The agreed target uses one `vernac-application.vapplication` beside the application
POM. Its logical application name does not change model namespaces or their generated
domain/usecase/adapter packages. Its technical bootstrap configuration is placed
in `<application name>.configuration`.
The following syntax supports nonpersistent functional modules:

```vernac-application
application example.shop {
    activate example.billing;
    bind example.billing.PaymentProvider
        from configuration "billing.payment-provider";
}
```

`activate` names a logical module. `bind` names an exported port. Its configured
value names an exported Vernac adapter implementing that port, not an arbitrary
Java class. A fixed selection uses `bind example.billing.PaymentProvider to
example.billing.mollie.MolliePaymentProvider;` instead.

Spring profiles select implementations through standard configuration:

```yaml
# application-dev.yaml
billing:
  payment-provider: example.billing.NoopPaymentProvider
```

```yaml
# application-prod.yaml
billing:
  payment-provider: example.billing.mollie.MolliePaymentProvider
```

Explicit activations and selected bindings are the roots of the runtime activation
plan. Required functional dependencies are activated transitively. Maven dependencies
alone do not imply activation. Implementing a port does not automatically activate
the module declaring that port; importing its interface/values is a compile dependency.
Billing remains explicitly activated independently of the selected payment provider.

Only selected adapters are instantiated and checked for adapter-specific configuration.
Unselected Mollie/Stripe implementations MUST NOT require credentials or start workers.
A Noop provider MUST be explicitly selected and MUST NOT be an automatic fallback.
Bindings initially apply application-wide to the port; tenant-specific routing is a
separate implementation responsibility. Missing selection, wrong adapter type,
unavailable exports, ambiguous wiring and required dependency cycles stop startup.

Internal `uses` may reference the module's internal definitions. Application `uses`
may reference only exported contracts. Generated bean identities use qualified Vernac
identities; Java reflection or arbitrary hand-written Spring code is outside this
language visibility boundary. Exact Spring registration and migration coordination
remain implementation work, not behavior delivered by the project wizard.

### Project creation

The IntelliJ wizard offers **Application** and **Module / Type Library**. Applications
receive an empty application definition. Type libraries receive a module definition,
explicit sample value exports and source attachment packaging, with no application
definition. The initial empty application file declares no runtime activation.

Application parsing, identity diagnostics, binding completion/navigation, activation
completion, file type and New action are available. Fixed bindings are checked against
the adapter's resolved port. Activation diagnostics and runtime wiring use the same
data-only graph. Type-only dependency modules require no activation. Imported service
and usecase declarations support existing source/contract navigation. Dedicated
navigation on a logical module name in an `activate` entry remains follow-up work.

## Exported port contracts (current)

A contract-only module MAY export a port and MUST explicitly export its locally
referenced public parameter/return types. Consumers import the port by its original
Vernac identity and implement it through an ordinary adapter declaration. The provider
interface is used from the dependency JAR, never regenerated by the consumer. Such
contract-only publication does not activate beans, adapters or migrations.

Adding an abstract port operation is not treated like adding a uniquely named class
behavior: existing implementors must be rebuilt/adapted. Public signature changes,
including optionality, are structural changes. The current contract metadata version
is `module-contracts-9`; earlier development descriptors must be rebuilt.

Adapter publication, configured selection, service/usecase activation and coordinated
persistent histories are supported. Exporting PaymentProvider does not
make its producer an activated billing implementation.

## Adapter publication and application binding (current)

A producer MAY export outbound adapters. Their Java identity is
`<namespace>.adapter.outbound.<Name>`; their implemented port and public operation
contracts are included in `module-contracts-9` metadata. Required configuration keys
are contract metadata, never property values. Local public types/ports require explicit
exports; foreign contracts are recorded as dependency requirements, not re-exported.

Generated adapter classes in a published module have no Component stereotype. Binding
selection registers exactly the selected implementation under its qualified Vernac
identity. Application-local adapters for explicitly bound ports are likewise excluded
from automatic component registration. Unbound local adapters retain their existing
behavior. Mandatory constructor dependencies are supplied by the application context;
missing dependencies/configuration stop startup. There is no automatic no-op fallback.

An application definition may contain fixed or configuration-driven bindings without
`activate`. Bindings implicitly instantiate the selected adapter from available exports.
Contract-only modules need no activation. Explicit activation registers nonpersistent services/usecases. Persistent domain/repository contracts can be published and imported; their runtime activation uses the startup migration coordinator.

The Maven compile goal generates a Spring Configuration and an owned Boot
AutoConfiguration.imports resource. Spring Boot discovers it automatically; a plain
Spring context must explicitly import the generated configuration. Existing source
AutoConfiguration.imports in the same application artifact currently cause a clear
build conflict rather than silent overwriting. Binding metadata belongs to the consuming
application and MUST NOT be emitted as application bindings in a published module.

Selection is application-wide per port. Missing/unknown/wrong-type selection or
competing bean registrations stop startup with the port identity and available adapter
identities; configuration values are not echoed. Only selected adapters undergo
constructor/configuration checks. Source attachment navigation also supports adapters.

## Functional modules (current)

A module MAY export domain services and usecases. A public method's local parameter
and return types, including inline result components and paginated collection types,
MUST be explicitly exported. Constructor dependencies and private helper services
are implementation details and need not be exported. They remain ordinary compiled
classes inside the producer JAR. A consumer MUST NOT import those internal Vernac
identities. A producer MUST NOT combine publication with application activation or
bindings.

Published service/usecase classes have no Spring Service/Component stereotype.
An application's generated configuration registers the active module's services
and usecases, including internal ones, under their qualified Vernac identities.
It does not broadly scan dependency packages. Ordinary application-local components
retain their existing registration behavior.

`activate <logical-module>;` activates all functional components of that module.
Their declared functional `uses` dependencies activate required modules transitively.
A selected adapter's functional dependencies also contribute roots. Implementing a
port alone MUST NOT activate the port owner's module. Importing values or adding a
Maven dependency alone activates nothing. Functional module dependency cycles stop
compilation for fixed plans or startup for configuration-dependent plans.

A local usecase/service that declares a foreign function through `uses` requires its
module to be active. Fixed-plan omissions are compile diagnostics. When selection
comes from configuration, the final required-function check occurs at startup.
Dependencies referenced only through arbitrary handwritten Java are not inferred;
applications must explicitly activate modules needed by that code.

The generated configuration enables Spring transaction management. Executing an
activated usecase requires an application-supplied PlatformTransactionManager. Its
REQUIRED transaction joins an existing usecase transaction. There is no independent
module transaction manager or DataSource. Persistent histories and event infrastructure
use the shared application runtime described below.

The descriptor's `components` list is a separate wiring plan, not part of a public
definition fingerprint. Private helper changes and technical DI constructor changes
therefore do not change the public API fingerprint. Public operation signatures and
inline result fields remain compatibility inputs. Rebuild module artifacts to emit
`module-contracts-9` metadata before consuming them with this compiler.

## Module persistence bundle preparation (current)

`vernac:module-persistence` prepares data-only resources for the persistent
activation coordinator. It MUST NOT execute SQL, mark candidates approved, enable
persistent exports or start module components. Persistent contract publication now invokes equivalent preparation/verification
automatically. The standalone goal remains useful for inspection. Runtime activation
coordinates those histories; plain event contracts and synchronous receivers are supported.

Source layout and generated resource identities are normative:

| Item | Path or identity |
| --- | --- |
| Module definition | `vernac-module.vmodule` beside the POM |
| Schema snapshot chain | `src/main/vernac-schema/history` |
| Module SQL source | `src/main/vernac-schema/migration` |
| Application SQL source | `src/main/resources/db/migration` (unchanged) |
| Module descriptor | `META-INF/vernac/modules/<logical-module>/persistence.json` |
| Packaged module SQL | `META-INF/vernac/modules/<logical-module>/migration` |
| Packaged source snapshots | `META-INF/vernac/modules/<logical-module>/history` |
| Future Flyway location | `classpath:META-INF/vernac/modules/<logical-module>/migration` |
| Module Flyway history schema | `vernac_module_` plus the first 32 lowercase hex digits of SHA-256 of the UTF-8 logical module name |
| Future Flyway history table | `flyway_schema_history` |

The history identity is independent of Maven version and coordinates. Its SQL schema
identifier is 46 ASCII characters, within PostgreSQL's identifier limit. It is a
technical history schema, not the namespace schema containing domain tables.
Independent modules may use the same migration timestamp because their histories
and resource locations are distinct. Duplicate versions within one module are errors.

All Maven schema/migration/check/compile goals use the module SQL source default when
`vernac-module.vmodule` is present. `vernac.migrations` remains an explicit override.
For persistence packaging, module SQL MUST reside outside ordinary Maven resource
roots; otherwise the same scripts could leak into the application's Flyway scan.

The descriptor format is version 1, separate from `module-contracts-9`. It includes
canonical identities, ordered filenames, SHA-256 hashes of the exact SQL and snapshot
bytes, the complete expected schema and its semantic fingerprint. Packaging MUST
require that the reviewed source snapshot chain reaches the current compiler schema.
Every SQL migration MUST have one matching snapshot. Repeatable migrations, nested
migration layouts and unpaired SQL are not supported in this initial bundle format.
Original SQL bytes, including existing responsibility comments, are copied unchanged.

Artifact verification MUST check descriptor identities, every declared resource and
checksum, snapshot-chain continuity, final schema fingerprint, and unexpected files
within that module's persistence resource tree. Physical table ownership checks MUST
reject overlap between participating modules and the application. These are structural
checks; they do not execute SQL or prove that it creates the declared schema.

Generation tracks its own resources in a target inventory and removes only previously
tracked stale files. It does not delete unowned files. Validation failures occur before
writing a replacement bundle. An I/O failure during output writing has no guaranteed
rollback; rebuild before distributing an artifact from a failed execution.

You alone are responsible for reviewing, testing and approving each migration before
applying it to any database. Checksums, model fingerprints and successful schema-check
runs do not guarantee correctness, safe operation ordering, historical-data meaning,
locking behavior, execution time, deployment compatibility or recoverability.

## Persistent domain and repository export contracts (current)

A module MAY export entities, aggregates, their explicitly requested collections and
repositories. Their Java classes/interfaces come from the dependency artifact. A
consumer MUST NOT regenerate foreign classes, JDBC implementations or owned tables.
Creating a local repository for a foreign aggregate is a compile error; import the
producer's exported repository instead.

A public entity/aggregate contract records its ID type, ordered fields, optionality,
mutability and public read/modify operations. Every locally declared public field,
ID, return and parameter type MUST be exported explicitly. A collection requires its
explicitly exported element. Private Java helpers and behavior bodies are not public
fingerprint inputs. Changing a field's mutability or an operation's mode is a contract
change even if its erased Java method signature remains unchanged.

A repository contract identifies its aggregate and exposes the standard byId/save/
delete operations plus declared query signatures. Query parameter/result types and
normal/offset/counted-offset/cursor query modes are tracked. Predicate and ordering
implementations remain in the producer's compiled JDBC class and are not part of the
public fingerprint. Adding/changing abstract repository operations requires provider
implementation rebuilds, just as adding abstract port operations does.

The metadata's `element` field identifies a collection element, entity/aggregate ID,
repository aggregate or implemented port according to definition kind. Field records
include `mutable`; method records include `mode`. The producer emits
`module-contracts-9`; earlier development module artifacts must be rebuilt.

Modules with repositories or a recorded migration history carry `persistent=true`.
Publishing them MUST package and verify the current schema against their source
migration/snapshot chain. Dependency ingestion MUST verify the complete bundle and
its owner identity. Removing the final repository does not silently discard a
module's already recorded migration history: the new empty schema still needs its
explicit migration before packaging.

Generated JDBC module implementations have no Repository stereotype. Explicit activation
MUST migrate and validate required persistent modules before registering callable dependent
components. Required histories run before dependants, then the application history.
Type-only imports cause no SQL execution or component startup. Application authors
MUST NOT bypass activation by scanning dependency packages.

Read-only dependency views show actual public structure and query signatures. Query
predicates/order are deliberately omitted and marked as compiled implementation
information; the consumer does not validate/regenerate those bodies. Attached original
Vernac sources remain preferable when available. Inbound HTTP entrypoints participate in explicit module activation.

## Persistent startup coordination

The JDBC starter supplies Flyway and PostgreSQL support. Persistent activation requires
one shared application DataSource, a PlatformTransactionManager and Spring Boot's
FlywayMigrationInitializer. Separate Flyway DataSources and competing custom
FlywayMigrationStrategy beans are unsupported and MUST fail initialization.

Before executing SQL, the coordinator verifies all activated bundles, checksums,
history chains and cross-owner table identities. Each module uses its own stable
history schema. Required modules migrate and undergo structural schema validation
before dependants; application migrations and validation follow. Dependent functional
beans MUST wait for initialization. Merely importing module values requires none of
this infrastructure.

Runtime validation checks owned tables, columns, types, nullability, primary keys,
constraint identities and foreign-key structure. It does not prove equivalent CHECK
expressions or inspect every trigger/index definition. Run schema-check before release.
Completed earlier histories are not rolled back if a later history fails. Retry resumes
from Flyway's recorded state; never repair or modify published migrations to hide drift.
The application developer/operator remains responsible for migration review, testing,
approval, deployment and recovery.

## Event contracts and receivers (current)

A producer MAY export an Event, recorded or plain. Its public contract MUST describe payload
field names/order/types/optionality and the generated Java API. Every local public
payload type MUST be explicitly exported. Imported events MUST retain EVENT category
and original Java identity; consumers MUST NOT regenerate their implementations.

Listeners MUST be published as internal LISTENER/DEFERRED_LISTENER runtime components, not
exported callable types. Their qualified runtime identity MUST be unique. Explicit
activation MUST register all receivers of the selected module, including
a module containing only receivers. Component stereotypes MUST be removed at publication;
no dependency package scanning is required or permitted as an activation substitute.
Imports of event types MUST NOT imply runtime activation. Declared functional injections
MUST participate in the same activation graph as other module components.

Synchronous receivers execute within the caller's event context and transaction. A
receiver failure MUST propagate and prevent transaction commit. Several active receivers
MAY handle one event; order MUST NOT be relied upon. SQL rollback does not roll back
Java state, log output or external effects. EventId and occurredAt retain the ordinary
event contract; this support makes no persistence or retry guarantee.

Deferred module receivers MUST require the producer's EVENT_STORAGE component.
Producer payload tables MUST be isolated from application-owned shared delivery tables.
The application worker MUST start only after all required migrations and validation.
Metadata contract version is module-contracts-9; prior development modules MUST be rebuilt.

## Durable module event storage and delivery (current)

The module producer MUST prepare payload tables and compiled storage adapters for every
local event, so a later consumer MAY declare deferred handling without regenerating
foreign storage. EVENT_STORAGE components MUST carry stable @storage:<event identity>
identities, correct compiled JDBC class names and module ownership. Public metadata
MUST preserve the recorded flag and storage capability. Persistent publication MUST
require a reviewed matching SQL/snapshot chain; payload preparation does not approve it.

Emission MUST persist a payload only when the Event is recorded or has an active deferred
recipient. Non-recorded events without deferred recipients MUST NOT be persisted.
Each deferred recipient MUST receive its own job in the application-owned
`"vernac"."@delivery"` table. Modules MUST NOT publish that shared table in their histories.
Applications MUST generate/review their migration when selected module components
require delivery. Table collisions remain startup errors, never implicit ownership transfer.

A module deferred receiver MUST depend on producer storage and thereby activate/migrate
the required producer before it becomes callable. An application-local deferred receiver
for a dependency event MUST require producer activation. An import alone MUST NOT start
components or install tables. All registered module storage/receivers MUST depend on
completed initialization. One Boot worker MUST serve the application across modules,
starting after module histories, application history and installed-schema validation.

The existing event/outbox contract governs claim leases, fencing, independent recipient
confirmation, retries, blocked jobs and at-least-once external effects. A processing
failure MUST roll back the receiver's SQL before recording its retry in a separate
transaction. Completed earlier deployment histories are not globally rolled back.
Recorded payload evolution, pending jobs and exported VO evolution MUST be reviewed
explicitly; versioned event types SHOULD be preferred when old payload meaning must
remain unchanged. This integration does not provide arbitrary payload conversion.

Producer JDBC/Boot implementation dependencies SHOULD be optional Maven dependencies
when the artifact also serves type-only consumers. Declare the core runtime normally;
activating applications supply the JDBC starter explicitly. This avoids accidentally
introducing Boot DataSource auto-configuration into a value-only consumer merely through
the library's transitive implementation dependencies. Imports still do not activate SQL.


## Module-owned inbound HTTP entrypoints

A producer MAY define inbound HTTP adapters using the ordinary inbound adapter syntax.
The adapter, its DTOs, mappings and error policy belong to that producer. An inbound
adapter MUST NOT be exported as a callable Vernac type. It is an internal entrypoint;
its dependencies on exported usecases of other modules are ordinary functional
activation dependencies. Its private DTOs and mappings need no exports.

Adding a Maven dependency or importing a value MUST NOT expose the module's routes.
Activating the module registers every internal HTTP controller and its paired,
controller-scoped exception advice. Activation also resolves required usecase modules,
selected outbound providers and persistence prerequisites through the shared graph.
The application MUST supply `vernac-runtime-http` and Spring MVC, normally through
`spring-boot-starter-web`. Producers SHOULD make transport infrastructure dependencies
optional so type-only consumers need not start a web application.

Generated dependency controllers retain MVC annotations but carry
`@ExplicitModuleComponent`, whose scan condition rejects component scanning. The
application's explicit registration creates active controller/advice bean definitions;
MVC then discovers the registered handlers normally. This is registration discipline,
not protection against deliberate manual bean construction. The producer JAR's compiled
controller, DTO, mapping and advice classes are used without consumer regeneration.

Routes retain their producer-declared paths; activation does not add a module prefix.
Conflicting mappings are rejected by Spring MVC during startup, rather than resolved
by priority. Activating an API is not authentication or authorization. Applications
remain responsible for security, deployment exposure and transport configuration.

HTTP modules without persistence own no migration history. When their usecases require
persistent modules, those modules' migrations finish before the entrypoint beans are
created. A non-web application may activate a module containing entrypoints if its
classpath provides the transport dependencies; routes are available only in a servlet
web application. Controller/advice component contracts require metadata version
`module-contracts-9`; rebuild earlier development artifacts.


## Dependency navigation and refresh

Go To Declaration on an `activate` identity MUST open a read-only module overview
containing its logical name, Maven coordinates/version, public exports, internal runtime
component identities and declared functional dependencies. This applies to modules with
no exports, such as listener-only or HTTP-entrypoint modules. Internal component names in
that overview are descriptive; they do not become importable types.

Type navigation prefers an available source attachment, including event declarations.
Fallback contract views preserve public functional signatures and multiple list/set
exports. They are explicitly non-executable views; placeholder method bodies are not
producer implementations. Views under `target/vernac-module-contracts` MUST NOT be
interpreted as local producer definitions or compiled as application sources.

A changed dependency index invalidates symbol/navigation caches. Invalid or incompatible
metadata MUST produce an actionable diagnostic on open application/module/model files,
not silently retain old symbols. Rebuild the consumer after changing Maven dependencies;
watch notifications alone cannot resolve dependencies or invent a new index. Restoring
compatible inputs and refreshing the editor clears the corresponding diagnostics.
