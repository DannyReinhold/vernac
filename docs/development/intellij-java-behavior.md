# Embedded Java in IntelliJ: behavior pilot

## Scope

The IntelliJ plugin supplies a lightweight Vernac PSI host and a Java
`MultiHostInjector`. Each inline `behavior` block becomes one virtual Java class.
Its method names and bodies remain mapped to ranges in the original `.vernac`
document. Imports, the containing class, method signatures and the implicit
`self` receiver are synthetic Java context. Nothing is written to disk.

This enables IntelliJ's Java highlighting, completion and reference navigation
inside inline method bodies, including calls between private helper methods in
the same block. The existing language server continues to handle Vernac syntax,
model diagnostics, type completion and namespace navigation.

The LSP descriptor explicitly enables semantic tokens for Vernac PSI files.
IntelliJ's default semantic-token policy only enables plain text and TextMate;
adding a custom parser without this override would remove Vernac highlighting.
Injected Java files are excluded from this opt-in and retain Java highlighting.

The first pilot covers ordinary and enum value objects and explicit collection
behavior. It does not inject validation expressions or legacy entity/aggregate
Java blocks. `implemented by` has no inline body to inject; Java navigation on
that Vernac target declaration is a separate feature.

## Prerequisites and editing

- Use an imported Maven project with a configured JDK and Java dependencies.
- Keep models under `src/main/vernac`, matching their namespace directories.
- Run Maven generation/compilation once so that the generated domain types are
  indexed. `self`, domain getters and other model APIs resolve against those
  generated types. After changing the domain API, regenerate it.
- Changes inside inline bodies and private helper declarations use the live
  document. Saving or rebuilding is not required for those edits.
- Declare additional Java imports explicitly in `java imports`. Automatic import
  insertion into that DSL block is not part of this pilot.

Java can navigate from a helper call to its original inline method name because
that name is an injected source fragment, not merely synthetic scaffolding.
Receiver and parameter declarations are currently synthetic; they do not yet
provide source navigation into Vernac signatures. Model type references now use
the separate [Java-to-Vernac type navigation](java-to-vernac-navigation.md)
integration when the referenced Java source is owned by the compiler. Domain
getters and public behavior references also navigate to their Vernac declarations;
private helper calls retain their local injected-Java targets.

IntelliJ Java inspections are not NullAway. The configured Maven checker remains
the authoritative nullness build gate. No live NullAway analysis is promised.

## Shared semantics

`BehaviorJavaProjection` lives in the compiler and reuses `FileTypeScope`,
`BehaviorImports` and `JavaTypeNames`. The IDE does not invent a parallel set of
namespace/import priorities or built-in mappings. Body-only model references
receive the same visible imports as generated behavior. Imported Java types
cannot replace reserved or visible Vernac types.

The plugin loads an editor snapshot of the Vernac source root, including unsaved
files. Independently parseable files remain available when another file has a
syntax error. Invalid imports or unresolved signature types suppress the affected
Java projection rather than binding to a guessed type. An incomplete Vernac
structure can temporarily suspend injection until it parses again; ordinary
incomplete Java expressions inside a structurally intact body remain editable.

Projection offsets are UTF-16, converted from ANTLR code-point offsets. No string
escape transformation is performed. This also applies to supplementary Unicode
characters before a method or within its body.

## Packaging and lifecycle

The compiler and its runtime dependencies are bundled in `plugin/lib` for local
projection. The executable LSP JAR stays separately in `plugin/server`; it is not
loaded into the IDE's plugin classloader. IntelliJ libraries retain provided scope.
Shared naming classes come from the compiler JAR, with no second plugin copy.

Projection results are cached per PSI host and invalidated by PSI modifications
and VFS structure changes. This initial implementation rebuilds a source-root
snapshot after invalidation. Incremental project indexing and finer-grained
invalidation are future performance work for large projects.

## Verification

Automated projection tests cover shared helper context, real Java compilation,
scoped imports, cross-namespace types, optional parameters/results, Unicode source
ranges, empty bodies, enums and collections. Distribution checks verify the
registered injector and the packaged compiler/parser dependencies.

The final integration check requires a running IntelliJ instance:

1. Build/import the example and open its inline behavior.
2. Invoke completion after `self.string().` and navigate to `toUpperCase`.
3. Navigate from `suffix()` to the private inline helper.
4. Edit a body and verify Java assistance still works without a Maven rebuild.
5. Check ordinary Vernac completion, diagnostics and cross-namespace navigation
   remain available outside injected Java fragments.

Reference: [IntelliJ language injection](https://plugins.jetbrains.com/docs/intellij/language-injection.html).
