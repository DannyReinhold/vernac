# Domain diagram export

Status: implemented as a bounded tooling feature in 0.4.0-SNAPSHOT. Diagram output is
a read-only projection of the resolved model, not another source of language semantics.

## Scope and identity

Export operates on a valid resolved project and a local namespace. Nodes are keyed by
fully qualified Vernac identity; same simple names in different namespaces remain distinct.
Every local executable definition/explicit collection in the selected namespace is shown.
Referenced definitions in other namespaces are boundary nodes and MUST NOT be recursively
expanded. Metadata-derived module definitions use the same resolver and retain dependency
origin. Technical generated Java classes, scalars and standalone terms are not graph nodes.

Edges represent declared domain fields, identities, collection elements, `uses` dependencies,
repository targets, implemented ports, event payloads and listener subscriptions. They MUST
NOT invent runtime calls, event emission, sequence, foreign keys or exclusive ownership.
Arbitrary Java bodies and infrastructure implementation details are outside this feature.

Descriptions come from exact-name associated vocabulary. Public model members appear in
the accompanying Markdown catalog. Missing terms are valid; documentation is not inferred.
Graph construction, serialization and rendering MUST share one resolved model across Maven
and IntelliJ. Data has formatVersion 1, sorted nodes/edges and stable renderer aliases.
Published graph data MUST NOT contain absolute build-machine paths.

## Files and rendering

`vernac:diagram` writes `.md`, `.mmd`, `.puml` and `.json` per namespace, and an index for
an all-namespace run. Optional `vernac.diagramNamespace` selects exactly one local namespace;
an unknown namespace is an error. Output defaults to target/vernac-diagrams and is configurable
through `vernac.diagramOutput`. Single-namespace export does not rewrite other namespaces or
the global index. Older generated namespace files are removed by the normal clean lifecycle.

Mermaid and PlantUML content is escaped and uses technical aliases rather than user names
as renderer identifiers. PlantUML output specifies Smetana. Rendering and SVG/PNG export
belong to the chosen renderer; no external process, server or renderer library is required
for producing the source files. The IDE action opens Markdown through normal editor support;
the Markdown/Mermaid extension is an explicit presentation prerequisite, not a bundled dependency.

## IDE and build behavior

The action applies to a source file or namespace directory below a Maven project's
src/main/vernac. It uses that project's existing dependency index and current unsaved Vernac
source snapshots. It MUST NOT save documents implicitly. An invalid project is reported;
it MUST NOT silently draw an old or guessed model. Dependency changes require a Maven build.

Analysis/export runs in a background task; opening the generated page runs on the UI thread.
There is no live subscription, graph editing, custom renderer, source-click navigation or
cross-language refactoring in this increment. Cancellation can leave already written generated
files; output has no transactional publication guarantee. Export changes no Java, schema,
migration history, application state or runtime activation.

See [the tutorial](../tutorials/domain-diagrams.md) for usage and interpretation.
