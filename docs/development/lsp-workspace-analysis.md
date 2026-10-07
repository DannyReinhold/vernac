# LSP workspace analysis

Status: project-wide diagnostics, type completion and type navigation implemented. This is an implementation progress document, not a claim that
all language features are supported by the editor.

## Shared compiler analysis

The language server now uses `VernacProjectLoader` and `ProjectTypeResolver`, just
like Maven. The separate single-document `VernacSemanticValidator` has been removed.
The loader accepts open-document snapshots as an overlay over the source tree.
No Java files are generated or written during editor analysis.

Analysis covers namespace layout, declaration identities, imports and the reviewed
ID/VO type rules. Deferred language categories have the same incomplete analysis
coverage as the compiler's `analyzeProject` entry point. They must not be considered
fully checked merely because the editor displays no diagnostics.

## Source roots and isolation

For each open file, the server finds its enclosing `src/main/vernac` directory.
All `.vernac` files below that source root participate, including unopened files.
Workspace folders constrain which open documents are eligible; `rootUri` and the
legacy `rootPath` are accepted when workspace folders are absent. If the client
supplies no workspace information, the conventional source root is inferred from
the open file's path.

Different Maven module source roots are separate analysis units. This step does
not introduce cross-module dependency resolution. Nonstandard Maven
`vernac.sourceDirectory` values are not discovered yet and require a later shared
configuration mechanism. Files outside the supported layout receive an explanatory
diagnostic. Non-file documents must be saved before project analysis is available.

## Editor lifecycle

- Opening a document adds its current text to the project snapshot.
- Full-text change notifications replace that text and revalidate dependent files.
- Older or repeated document versions are ignored.
- New unsaved files are included in the source tree while open.
- Closing a document removes its overlay: disk content applies again, or an unsaved
  new file disappears from analysis.
- Save notifications and file-watch notifications refresh disk content without
  replacing unsaved editor text.
- Removed and repaired diagnostics are explicitly cleared, including diagnostics
  previously published for unopened files.
- Workspace-folder changes re-evaluate which projects can be analyzed.

The server advertises full synchronization, open/close, save, and workspace-folder
support. It requests `**/*.vernac` file-watch notifications when the client advertises
dynamic registration. Clients without that capability still refresh on editor
open/change/save/close events; automatic external-file refresh is not guaranteed.

Diagnostics retain compiler messages and warning/error severity. Source columns
are converted from ANTLR code points to LSP UTF-16 positions. Open-document
publications carry the analyzed document version.

The initial implementation serializes analysis and rebuilds snapshots for active
source roots on each event. It prioritizes deterministic correctness; debouncing,
cancellation and large-project performance work remain pending. Tooling symbol snapshots
are cached between notifications and invalidated on each editor/workspace event. There is
no persistent disk index or recursive import traversal. A syntax/index failure
prevents later semantic stages for that root, as in the compiler, avoiding cascading
errors based on an incomplete project.

## Verification

From the repository root:

```text
mvn -pl vernac-lsp -am test
```

The LSP tests exercise the actual language-server service methods with a recording
client and temporary multi-file source trees. They cover unsaved edits, dependent
diagnostics, close/reload, create/delete/watch/save, stale versions, duplicate-import
warnings, namespace mismatches, compiler parity, Unicode positions, module isolation,
workspace removal, and watcher registration. They do not simulate IntelliJ or the
JSON-RPC transport.

## Completion and navigation

Completion and Go to Definition use a tolerant parse of the current source tree,
including open-document overlays. Declarations come from parser contexts, not
whole-document regular expressions. The compiler's `ProjectSymbolIndex`,
`FileTypeScope` and `BuiltinTypes` determine identities, visibility and built-ins.
The editor does not recursively load imports or guess Java classes.

- Simple type names follow the same local/explicit/wildcard precedence as the compiler.
- Ambiguous wildcard references have no completion candidate or navigation target.
- Invalid imports or index conflicts suppress simple-name resolution until repaired.
  Exact, valid fully qualified identities remain navigable.
- Completion supports unfinished type references and import declarations. An
  unfinished import is omitted from the tooling scope; compiler diagnostics remain
  authoritative and still report invalid source.
- Import completion offers qualified types and direct namespace wildcards. It does
  not offer imports from the current namespace or automatically insert imports.
- Qualified completion replaces the entire reference, including the suffix after
  the caret. It never inserts a duplicate namespace prefix.
- VO fields offer the reviewed ID/VO/enum categories and approved built-ins. Collection
  and entity field rules will be revisited with those language features.
- Go to Definition targets the declaration name in its actual source file, with
  UTF-16 ranges. Explicit imports are navigable; namespace/wildcard imports have no
  single declaration target.
- Comments and string contents do not create declarations or type references.
  Embedded Java bodies have no Java completion/navigation in this step.

Other declarations are indexed to preserve identity conflicts and import visibility.
This does not complete their semantic review. Repository/listener shorthand
completion remains the earlier implementation, and enum-constant/member navigation,
Java interoperability, hover details and auto-import edits are deferred.

Tests cover incomplete input, file-local imports, local precedence, ambiguous and
conflicting declarations, fully qualified edits, UTF-16 declaration ranges, false
references in comments/strings, unsaved rename/close, disk deletion and module
isolation. Plugin distribution tests check internal template registration, the
namespace-aware template handler and absence of old bundled generic templates.

## Manual editor check

Under `src/main/vernac`, create:

`demo/shared/title.vernac`:

```vernac
namespace demo.shared;
value Title(String value);
```

`demo/tasks/task.vernac`:

```vernac
namespace demo.tasks;
import demo.shared.Title;
id TaskId;
value TaskSummary(TaskId id, Title title);
```

1. Replace `Title` in the field with `Ti` and invoke Basic Completion.
2. Accept `Title`, then use Go to Declaration on the reference and on the import.
3. Rename the declaration to `Heading` without saving: `Title` must no longer
   resolve. Undo the rename and check that resolution returns.
4. Remove the import and use `demo.shared.Ti` in the field. Completion must replace
   it with `demo.shared.Title`, and navigation must reach the same declaration.
5. Check New: the namespace and file actions should appear together near the top.
   The old generic template dialogs should no longer be offered. Existing user
   templates are preserved; only the two owned Vernac template names are hidden
   from generic template creation.

## Next

Unicode identifier support is implemented in the next slice; see
[Unicode implementation](unicode-support.md) for its pending full verification.
After that, track generator-owned output files and remove obsolete generated classes
only after successful generation. Stale-file cleanup is not implemented yet.

## Confirmed navigation and deferred Ctrl-hover feedback

Manual verification confirmed project-wide Go to Declaration through the context
menu, Ctrl+B and Ctrl+left-click, including cross-namespace references.

Deferred plugin enhancement: while Ctrl is held and the pointer is over a resolvable
Vernac reference, show link styling (blue/underlined, respecting the IDE theme) and
a declaration/type-information preview comparable to Java navigation. Navigation
already works; this item concerns visual feedback before clicking. It is separate
from the Unicode contract in `../contracts/names-and-unicode.md`.
