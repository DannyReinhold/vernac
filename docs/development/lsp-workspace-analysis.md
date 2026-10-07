# LSP workspace analysis

Status: project-wide diagnostics implemented; project-aware completion and navigation
are the next step. This is an implementation progress document, not a claim that
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
caching, cancellation and large-project performance work remain pending. There is
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

## Next

Connect type completion and Go to Definition to shared project symbols and file
scopes, including useful behavior while a document is temporarily incomplete.
Then package the updated server with the IntelliJ plugin and test the complete
editor workflow. New Namespace and namespace-aware New Vernac File actions are
implemented separately; their IntelliJ UI workflow still needs the same manual
plugin verification.
