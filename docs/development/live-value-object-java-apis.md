# Live domain Java APIs

Status: ordinary-VO type selection, short-name insertion and factory completion are
confirmed in the installed IDE. The 0.5.0 expansion adds IDs, enums, entities,
aggregates and their lists/sets. These new kinds need their own installed-IDE acceptance.

## Purpose and scope

Expose complete local IDs, VOs, enums, entities, aggregates and their explicitly
declared lists/sets to embedded Java before the first generation build. The view
includes factories, getters, public behavior signatures, current vocabulary
documentation, validation Read interfaces and mutable-domain Read/Write/Access views. The editor snapshot
includes unsaved documents and uses declaration-level recovery.

This slice does not project services, events, repositories, ports, usecases or their
application access interfaces. Their existing Java
APIs and Maven dependencies remain usable. External module contracts supply type
information, but their implementations are not reprojected or modified.

## One semantic source

`ProjectTypeResolver.resolveForAnalysis` returns the same resolution results and
diagnostics as strict resolution without its final failure. Strict compilation
continues using `resolve`, which throws on errors. `EditorValueObjects` admits
only uniquely indexed declarations with reliable file scope, complete field types and
no owner-local semantic errors. It calls the actual IdGenerator, ValueObjectGenerator,
DomainCollectionGenerator, EntityGenerator, AggregateGenerator, DomainAccessGenerator,
ValueReadGenerator and vocabulary renderer; there is no second field/factory
naming or Optional-signature implementation.

Errors in another declaration do not prevent a valid owner's view. Errors in the
owner's API cause its class, access family and collections to be withheld. The normal compiler and configured Java
checker remain authoritative; a live API is not proof of valid Java bodies.

## IntelliJ integration

A Vernac host file provides a marked resolve scope around its module's ordinary
scope. A project service caches current per-source-root models against PSI and
VFS changes. A scoped PsiElementFinder exposes nonphysical Java classes created
from the generator output. Their parameter/return types retain the same marked
scope, so references to other new VOs resolve consistently. Validation Read
interfaces are supplied alongside their value owner.

Only Vernac-marked Java contexts use these classes. Ordinary handwritten Java
files retain normal resolution. Package discovery advertises the loaded live
model's package names; normal Java package lookups do not trigger model loading.
The service does not write generated sources, modify build configuration or run
Maven/javac. It is not a class-loading or Hot Swap mechanism.

Class/factory navigation targets the original Vernac declaration. Getter and
public behavior navigation uses the existing member locator. Technical Read type
navigation may show the in-memory Java interface when no generated file exists.

## Existing generated output

The finder filters an older physical domain declaration only when its exact source
path belongs to the compiler's validated ownership inventory. Current projected APIs
replace their generated predecessors; invalid supported declarations and removed
model names cannot revive their old APIs. Current declarations outside this slice retain their generated classes. Owned
Read/Write/Access families track the current owner; families of removed owners
are suppressed too. Technical `__Vernac*` behavior classes and current unprojected
access families are preserved. Handwritten sources and dependency JARs are not hidden.

A field/API edit, rename or deletion invalidates the snapshot. An unreadable or
malformed ownership inventory is not permission to hide arbitrary Java sources.

Automatic inventory discovery currently covers
`target/generated-sources/vernac`. A customized output directory is not promised
to receive duplicate suppression in this slice; use the conventional directory
for installed-IDE acceptance. Existing dependency visibility rules still apply.

## Verification

Automated checks cover generated signatures, nullable inputs/Optional results,
new validation interfaces, vocabulary, unsaved models, unrelated errors, invalid
or duplicate APIs, current field/method changes, factory execution, ownership
boundaries, stale-value policy, ordinary Java scope isolation, marker scope
wrapping and extension registration. SDK compilation checks exact platform API
availability. These checks do not substitute for an installed IntelliJ test of
native completion, inferred types, Ctrl-hover/navigation and the first-build
transition. The tutorial provides that acceptance sequence.

## Native Java type-name completion

Java resolution and Java class-name completion use separate IntelliJ extension points.
The live element finder resolves classes and packages. A scoped Java short-name cache
also feeds current supported value-side names into native class-name completion. IntelliJ retains
responsibility for prefix matching, ranking and inserting the selected class.

Only valid VOs visible through the current file's namespace and Vernac imports are
advertised. Technical validation Read interfaces are not class-name suggestions. No
unscoped names are published; ordinary Java files continue to use generated sources.
This does not extend live API coverage to other declaration kinds.

Acceptance check: without running Maven, declare `value EditorTest(int);` and use
Ctrl+Space after `EditorT` and `EdTes` inside both a usecase execute block and an aggregate
behavior. Select `EditorTest`, then check `EditorTest.of(0)` and member completion after
`EditorTest.`. Repeat after renaming or invalidating the VO and after adding a Vernac
import for a VO in another namespace. Package resolution alone is not evidence that
class-name completion works.

## Installed-IDE completion failure and diagnostics

The installed-IDE report isolated a scope-propagation defect: valid VO APIs were
published and resolvable from the host, but injected Java had a `UnionScope` that
the original marker lookup did not traverse. It therefore saw only generated types.
`ResolveScopeManager.withFile()` can combine the host scope with the injected file;
that union is not a `DelegatingGlobalSearchScope`.

Marker discovery now traverses delegating scopes and union branches. It retains a
single host/snapshot context, rejects combinations of different contexts, and does
not widen an ordinary Java scope. Component regressions use the target SDK's real
`uniteWith()`/`UnionScope` behavior, including reverse/nested composition. This
addresses the reported failure boundary; a new installed-IDE retest is still required
before declaring pre-generation class-name completion accepted.

The report also queried the short-name extension point in the application area even
though it is project-scoped, causing `IllegalArgumentException` in the report itself.
Diagnostics v2 uses the project area and displays nested scope branches. That reporting
error was separate from the live-type lookup defect.

To collect evidence, put the caret **inside the affected Java body**, then choose
**Tools → Vernac → Diagnose Embedded Java Context**. A read-only, in-memory text
report opens. Copy its contents for analysis; it is not uploaded automatically.
The action does not save source, generate Java, change roots or launch Maven.
It does instantiate the same live model/classes used by code insight, so mention
if ordinary completion starts working after running it.

The report separates host PSI, Java injection, scope wrapper classes, extension
registration, declared/published value-side identities, visibility, direct live-class
creation, Java-facade lookup and short-name lookup. It contains type names, not
source bodies, environment variables or exception messages. At most 40 value-side
APIs receive lookup probes. A plain PSI-copy probe is labelled as such and must not
be mistaken for an actual Java completion invocation.

If `snapshot.visibleCandidates` is empty, inspect publication and namespace/import
visibility first. If candidates exist but `injected.liveScope` is false, investigate
context propagation. If a direct class exists but native lookup fails, inspect
finder/index filtering and the observed scope. `ERROR` entries identify the failing
runtime class; cancellation is propagated rather than hidden as an empty result.

## Package lookup, imports and static factories

The union-scope retest confirmed that new VO class names are offered. It also exposed
fully qualified insertion and missing `of` completion. A direct facade/class-index
lookup is still not equivalent to resolving imports and package-qualified Java references.
In SDK 262, `PsiPackageImpl` can cache short-name classes in `everythingScope`, losing
the file's live context. Native class insertion also runs with alternative resolve enabled.

`VernacLivePackage` retains the caller's live context for package membership. It reads
the current class directly, merges ordinary candidates and applies the existing ownership
filter. It does not recursively call the facade in alternative resolve. Outside a live
scope (or during indexing), the ordinary package behavior remains in place. No global
editor-only classes or additional user imports are published.

The live Java file also carries `PsiFileFactory.ORIGINAL_FILE` provenance pointing
at its Vernac source. Native class-equivalence checks can then recognize the same
qualified type across snapshot recreation during insertion. This does not call
`setOriginalFile`: the file's Java PSI/evaluation context remains Java. Qualified names
and distinct source origins continue to distinguish different declarations.

The projection already imports visible domain values into usecase/aggregate Java.
Thus `EditorTest` is the intended body spelling even though its generated Java package
differs from the usecase package. External Java imports remain explicit `java imports`;
this change adds no automatic DSL import editing.

A forked CoreApplicationEnvironment test uses actual generated VO Java, live Java PSI,
the production finder/package bridge and the SDK's Java reference/member processors.
Its core file-index policy treats memory files as non-source files, unlike the core
mock default. It reproduces missing package membership and unstable view identity with
the previous setup, then checks identity across view recreation, smart/alternative lookup, projected explicit imports, short and qualified
factory calls, native public-static member discovery and isolation from ordinary Java.
The harness supplies its caller scope explicitly and preloads the in-memory Java class;
it does not run injection, generated-root discovery or the actual popup/insert handler.
Its provided Kotlin dependency supports compilation and the SDK syntax parser without being packaged.

Installed acceptance confirms class selection inserts the short name `EditorTest`.
The subsequent copied-host/package correction has also been accepted: factory-member
completion works reliably in the installed IDE. The earlier report showed a live
scope on injected Java but no marker on its PSI copy, plus a cached plain package.
Retest `EditorTest.o`, a manually qualified reference and an aggregate behavior.
Diagnostics v4 can inspect the actual package implementation and smart/alternative
membership as well as current imports, the caret's reference/qualifier and `of` methods.

## Completion copies and early package caches

An injected Java file's context can be a copied Vernac host. The host's view-provider
virtual file is then nonphysical. Resolving the model root from that copy silently
falls back to an unmarked module scope. `VernacPsiFile` now derives the source root
and module context from its original host PSI. The copied text is still the editor's
working copy; this is provenance for resolution, not a replacement with saved text.

The facade may also cache a physical `.domain` or `.domain.access` package before
any live model exists. The finder wraps these existing native packages eagerly;
only lookup with a Vernac live marker supplies live classes. Missing packages are
not invented, ordinary Java keeps ordinary membership, and global package lookup
does not trigger model loading. Other packages are left to the standard finders
unless they are already part of a loaded live model.

Native regressions now clone an actual physical `VernacPsiFile`, verify its original
host and retained scope, and check cold physical-package wrapping without loading
snapshots. The core test JVM exports the SDK's NIO bridge package for local-file
loading. These tests still do not run the injected completion popup. The diagnostic
report includes the copied Java context's PSI kind, scope and original-host locality
without collecting source bodies or paths.

## Value-side expansion

The existing `EditorValueObjects` helper now publishes the complete value side. Its
`valueNames` set initially tracked IDs, ordinary VOs, enums and each collection name;
the mutable-domain expansion also tracks entity and aggregate declarations,
including invalid declarations that must suppress obsolete owned Java APIs.

| Declaration | Live API from the production generator |
| --- | --- |
| `id TaskId;` | `create`, `of`, `fromString`, `value`, `asString`, equality methods |
| `value State = OPEN | DONE;` | Enum constants, public behaviors and Java's enum methods |
| `value Label(String) list Labels, set SelectedLabels;` | Each separate collection API, factories, typed element methods and its own public behaviors |

No collection is inferred from an element type. Lists retain index, order,
`distinct` and `duplicates` operations; sets expose their different API. Multiple
named/default collections use the compiler's existing naming and visibility rules.
A collection error withholds that collection without hiding its valid element or
siblings. An invalid element withholds its collections too. The mutable-domain expansion below also supplies entity/aggregate collections.

Owned model files can be suppressed when renamed/removed or invalid, including IDs,
enums and collections. This is confined to exact manifest-owned domain declarations;
handwritten files, external artifacts and unprojected current model types remain
available. Technical behavior/access classes are not mistaken for public declarations.
Enum-constant navigation maps to the actual constant token in the current model.

Test new IDs (`TaskId.cre`), enum constants (`State.O`) and list/set factories
(`Labels.em`, `SelectedLabels.em`) before generating Java; then inspect instance
members, navigation, an unsaved edit and removal. Repeat with a generated predecessor.
Automated checks compare projected source with strict generator output, compile the
value-side APIs together, exercise immutable collection behavior and run native Java
reference/member processors. They do not establish installed-popup acceptance for
every new type kind.

## Entity and aggregate expansion

The same editor model now generates entity/aggregate classes and all three access
interfaces with the production generators. The legacy helper name
`EditorValueObjects` does not limit the published declaration kinds. Technical views
resolve when referenced by the Java projection; class-name suggestions remain
restricted to public model declarations visible to the Vernac source.

| Context | Java API available through `self` |
| --- | --- |
| `read` behavior | Owner Read interface: id, field getters and public read behaviors |
| `modify` behavior | Owner Access interface: inherited Read and Write operations |
| `validates` | Owner Read interface, without setters, modifying behaviors or `emit` |

Write includes setters for `mut` fields, public modifying behaviors and `emit`.
The owner itself implements none of these access interfaces and gains no public
field setters. Aggregate Read also exposes the existing `createdAt` and `updatedAt`
methods. Factory overloads, nullable inputs, Optional getters and public behavior
signatures match generated Java exactly. This does not strengthen the language's
read/purity guarantees or change runtime modification/validation semantics.

Entity/aggregate lists and sets have their existing typed id helpers, including
`by(id)`, `contains(id)` and removal methods, with the current element and id types.
Each collection keeps its independent API and behaviors. A collection-specific error
does not hide its valid element, access views or sibling collections.

An invalid owner withholds its complete family. Owned stale Read/Write/Access
classes cannot survive as an alternate contract after signature changes or removal.
Current services and other unprojected access families remain usable from generated
Java; external module classes are never reprojected.

The native fixture supplies the SDK base external-annotation manager with no roots,
and registers the standard expression-nullability extension point; it does not replace
Java type inference or member resolution. Native PSI checks exercise inherited access
methods, readonly exclusions, factories,
aggregate timestamps and id collection helpers. Compiler checks compare all API files
with strict generation, verify the injection roles (including validation), and compile
and execute live classes with their actual generated implementation companions.
Installed completion acceptance is still required for each new type kind.
