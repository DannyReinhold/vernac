---
title: DSL and Java integration lessons
---
# Language semantics, Java assistance and editor state

[Lessons learned](index.md) · [Documentation map](../../documentation-map.md)

## Divide responsibilities without dividing semantics

Vernac combines an ANTLR-based compiler, an LSP server, lightweight native PSI and
Java injection. These are complementary mechanisms. LSP supplies portable language
analysis; native PSI lets IntelliJ associate references and source ranges with IDE
operations. Java PSI supplies Java type resolution and code insight inside injected
fragments. A language server alone is not a Java PSI implementation.

| Layer | Responsibility in this design | Must not invent independently |
| --- | --- | --- |
| Compiler model | Names, visibility, types, signatures and diagnostics | A second editor-only language |
| LSP | DSL diagnostics, semantic tokens and model assistance | Java's overload/type system |
| Native PSI | Host ranges, references and IDE integration | Different import priorities |
| Java projection | Parseable Java context around user-authored code | A different generated public API |
| Project model | Dependencies, JDK and generated source roots | Broad visibility of every module |
| Debug bridge | Verified source/runtime correspondence | A fictional execution model |

The shared compiler tooling is crucial. Vernac's `BehaviorJavaProjection` uses
`FileTypeScope`, `BehaviorImports` and `JavaTypeNames`, rather than reimplementing
import rules in the plugin. An IDE shortcut that accepts a type the compiler rejects
creates a particularly confusing failure.

Adding native PSI can also change platform defaults. Vernac needed an explicit LSP
semantic-token opt-in for its new PSI file type; otherwise DSL highlighting
disappeared. Injected Java must retain Java highlighting, rather than receiving
the host's semantic tokens. Test both sides after changing the parser integration.

## Inject coherent Java, not isolated strings

A Java body needs imports, a containing class, parameters, return types and its
receiver's actual API. A detached statement may highlight while having the wrong
scope. Use language injection to connect editable source fragments to synthetic
Java scaffolding. IntelliJ's `MultiHostInjector` can assemble several host ranges
into one Java file; prefix/suffix text supplies non-editable context.

Vernac projects a coherent behavior context so private helper calls can resolve
across methods. A helper name must itself have a mapped source range if navigation
is to land on that name. Merely inserting the name into synthetic text gives Java
a declaration without an editable DSL destination.

Keep these coordinates distinct:

| Coordinate | Meaning |
| --- | --- |
| DSL document range | What the user can edit |
| Injected Java range | What the Java parser sees |
| Generated Java range | What the build compiles |
| JVM location | What is actually executing |

ANTLR code-point offsets and IntelliJ UTF-16 offsets differ for supplementary
characters. Convert at explicit boundaries. Test non-BMP characters, CRLF, multiline
expressions, empty bodies and positions near fragment edges. Do not repair offsets
by silently deleting or padding user text.

Synthetic scaffolding also affects inspections. An inspection about a generated
wrapper can surface in the host and confuse the user. First check whether it points
to actual user code or generated context. Improve the projection/generator or narrow
the affected integration; do not disable useful Java inspections globally.

See [embedded Java implementation](../intellij-java-behavior.md) and JetBrains'
[language injection documentation](https://plugins.jetbrains.com/docs/intellij/language-injection.html).

## Resolution is not completion

This distinction caused several real failures. Existing generated Java files must
belong to the correct module's Java source roots. A directory on disk is not enough.
Missing root registration can cause unresolved factory calls, misleading inferred
local types and suggestions for nearby application packages but no domain package.
Compare the generated Java editor with the injected Java editor before changing
the compiler or suppressing a warning.

Vernac registers an existing conventional `target/generated-sources/vernac` root
with ownership checks. It does not start Maven on an editor callback. A clean build
can remove/recreate the root, so startup registration alone is insufficient.

For assistance **before generation**, Vernac has a separate read-only Java API view
for valid ordinary VOs. It reuses actual generators, observes unsaved documents and
withholds invalid APIs. `PsiElementFinder` supplies qualified classes and packages.
A `FileResolveScopeProvider` carries a narrowly scoped live context; dependent live
classes need that context too. Ordinary handwritten Java retains its normal scope.

But a finder is not a class-name index. Native Java class-name completion also asks
`PsiShortNamesCache` for names and then classes. Qualified lookup can work while
`EditorT` + Ctrl+Space offers nothing. Vernac's follow-up connects that cache only
within live Vernac scopes, leaving prefix matching and insertion to IntelliJ.
Class-name candidates and short-name insertion are confirmed in the installed IDE;
ordinary-VO static-member completion is confirmed too. New type kinds need their own acceptance. See the collection's
[status](index.md#evidence-and-current-boundaries).

Test the following separately:

- Fully spelled `EditorTest.of(0)` resolves with the expected result type.
- `EditorTest.` offers members.
- `EditorT` and `EdTes` offer the class name before generation.
- Accepting a suggestion changes only permitted user text.
- Renaming, deleting or invalidating the VO removes stale suggestions.
- A file in another namespace needs the appropriate Vernac import.
- Ordinary Java, other Maven modules and dependency classes keep normal resolution.

A concrete retest exposed `UnionScope`: the host had the live marker and valid API,
but injected Java saw a union of the host and injected-file scopes. Searching only
`DelegatingGlobalSearchScope` wrappers lost the marker. The fix traverses union
branches and rejects conflicting host/snapshot contexts. The regression must construct
that real SDK union, not only a delegating wrapper. Also query project-scoped extension
points in the project area; an application-area query can itself throw during diagnostics.

Another boundary is native package lookup: a direct class lookup can work while
`PsiPackageImpl` caches package membership in a broad scope that cannot see the live
model. Class insertion uses alternative resolve as well. A scoped package bridge must
retain membership in that mode without recursively re-entering facade/package resolution.
Native PSI tests reproduce the membership loss and exercise real imports, factory
references and member processors; they still do not run the popup or insertion handler.
Class insertion also compares PSI before/after its edit: recreated memory files need
stable source provenance for class equivalence. Vernac uses `PsiFileFactory.ORIGINAL_FILE`
metadata while keeping the Java file's actual original-file/context API Java-based.
In a core harness, explicitly model memory files as non-source files: the core mock's
all-files-are-source default can otherwise hide this equivalence failure.

Resolution can travel through wrapped scopes and completion-copy files. Preserve
context through those paths deliberately; do not solve a lost scope marker by
publishing every synthetic type globally. Also test duplicate physical/synthetic
candidates after generation. A class name or familiar package is not proof that a
physical file belongs to the generator: Vernac uses a validated ownership manifest.

This is editor assistance, not compilation or Hot Swap. See
[live VO APIs](../live-value-object-java-apis.md) and
[generated output ownership](../generated-output.md).

## Navigation needs references, not only actions

A custom Go to Declaration handler can make Ctrl+B succeed without supplying the
reference ranges required for Ctrl-hover link feedback. Native `PsiReference`
objects tie a usage range to a target. Quick documentation is another integration
point; useful hover text is not proof that reference resolution works.

A related trap is handling too much. Vernac's host references must not swallow Java
references inside injected bodies. Return/parameter types in DSL signatures and
`implemented by` class names need separate Java-aware handling because they are
outside those bodies. Test JDK classes, private helpers and handwritten targets,
not only DSL type references.

Resolve overloads by qualified identity and ordered parameter types. Never guess a
method from its simple name. Redirect only verified generated/source-backed targets;
keep technical interfaces navigable as their actual Java declarations. Dependency
contracts are read-only navigation targets, not editable local definitions.

Working references are a foundation for usages/rename, not a complete refactoring
implementation. Rename also needs correct write targets, generated-file exclusion,
conflict checks and a policy for implicit names and schema evolution. Leave it
unsupported until those operations have their own tests.

See [Java navigation](../java-to-vernac-navigation.md) and JetBrains'
[references and resolve](https://plugins.jetbrains.com/docs/intellij/references-and-resolve.html).

## Editing is not a successful build

A file is frequently invalid while someone types. An all-or-nothing project parse
makes unrelated Java assistance disappear. The editor needs conservative recovery:
retain independently complete declarations with trustworthy ranges and headers;
withhold ambiguous declarations. Missing imports or parameter types must not be
silently invented. Unclosed Java blocks are especially dangerous recovery boundaries.

Use current unsaved documents, not just disk contents. A last-good snapshot can
produce ghost classes after a rename/removal. Vernac instead recovers the current
snapshot and retains diagnostics. Builds still use strict parsing and cannot
accidentally generate the recovered subset.

Test one damaged declaration beside a valid one, errors in another file, invalid
namespace/import headers, unsaved edits and a type that becomes valid again. See
[editor declaration recovery](../editor-declaration-recovery.md).

## Respect the platform lifecycle

Use project-scoped services and disposables for listeners/caches. Invalidate on the
state actually used: document/PSI edits, file changes, roots and dependencies. Never
retain disposed project PSI in a static cache. Broad invalidation is a safe first
step, but rebuilding the whole source root on each edit is not a scalability claim.
Measure large projects before adding more projections or indexes.

PSI/index reads require the appropriate read context; project-model changes require
platform-sanctioned write handling. Do not block the EDT with Maven, network access
or full compilation. Honor cancellation, and use safe fallbacks during indexing
rather than catching cancellation as an ordinary parse failure. Index-dependent
features need a deliberate dumb-mode policy.

Vernac coalesces generated-root repairs and applies them on the EDT in a write
action. This is a local implementation choice, not a recommendation to run every
read operation on the EDT. Consult the target SDK's
[threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
and verify actual API availability/deprecation against that SDK.

## Follow the original host when completion copies PSI

A working resolve scope on the original injected file does not prove that its copy
retains it. Native completion creates temporary PSI. Its context can point at a
copied DSL host whose virtual file no longer belongs to a physical source root.
Resolve project/model provenance through the original host PSI, while retaining the
working copy for editing. Test an actual host `copy()`; supplying a marked scope to
Java references by hand misses this failure.

Also test package lookup before loading any editor model. The Java facade caches
package objects without a per-caller scope key. If a plain physical package wins
early, a later scoped finder may resolve the class directly while package membership
still misses it. Vernac eagerly wraps existing domain packages; the wrapper delegates
ordinary scopes to native lookup. Do not invalidate every Java cache on each keystroke
or publish all live classes globally to conceal this distinction.

## Extend live APIs through production generators

Once the host scope, package bridge and native class-name path work, add declaration
kinds to the shared generator-backed editor model rather than writing separate
completion lists for each factory. Vernac's IDs, enums and value-side lists/sets now
reuse the same finders, class identity and copied-host resolution as ordinary VOs.
Test enum fields as well as methods, multiple list/set declarations and collection
behavior signatures. Keep diagnostics independent: a bad collection must not hide
its valid element and sibling collection. Conversely, a collection of an invalid
element must not advertise a trustworthy API.

Stale suppression needs the current semantic symbol set plus exact generated-file
ownership. Do not recognize compiler ownership merely from a familiar method,
superinterface or package. Preserve technical delegate classes and current model
kinds whose live API has not yet been implemented. No editor projection should
write build output or imply that the runtime has acquired the new type.

## Mutable-domain APIs need their access family

Publishing an entity class alone is insufficient when inline methods receive a
Read or Access interface as `self`. Generate the access interfaces from the same
production generator and test inherited members through native Java PSI. Check
that Read excludes setter overloads, modifying behaviors and event emission, while
Access inherits both read and write contracts. Keep the actual entity separate
from these views; a helpful editor must not invent public setters or interface
implementations that do not exist in generated Java.

Reconcile stale technical views by their owner as well as their own qualified name.
An invalid or removed owner must not retain an old owned Write/Access contract.
Conversely, current access families of declaration kinds not yet projected must
remain available from generated Java. Verify both sides using the exact manifest;
never hide an arbitrary handwritten interface merely because its name ends in Access.

Read views preserve the existing language semantics. An entity returned by a getter
is still that entity; do not claim recursively readonly object graphs or stronger
purity enforcement as a side effect of completion support.

### Application APIs and nested Java types

Extend live projection with actual public generator output, including service/usecase
access views. Do not publish package-private implementation companions. A usecase
Result record belongs to its enclosing Java class: resolve nested names through
that class rather than inventing a second top-level declaration. Eager package
wrappers must also cover existing usecase packages, because the Java facade may
cache them before the first editor snapshot. Keep stale-output ownership checks
restricted to the known generated packages and the compiler's output inventory.

### Separate repository contracts from storage planning

A repository's byId/save/delete and declarative search signatures are domain API,
not SQL layout. Extract one shared signature renderer for the production JDBC
generator and editor. Do not invoke global schema generation to resolve a new
repository in an unsaved document: unrelated persistence errors must not remove
otherwise valid editor contracts. Keep adapter DTOs nested in their actual holder
and restrict package bridges to the reviewed generated package families.
