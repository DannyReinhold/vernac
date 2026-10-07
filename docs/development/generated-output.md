# Generated output ownership and cleanup

Status: source-output reconciliation implemented; project build confirmed successful. Compiled-output strategy requires
the acceptance checks
below before a deterministic packaging guarantee is claimed.

## Scope

One complete Vernac compilation owns one manifest in one dedicated generated-source output directory. Ownership is per
Maven module and generation output, not per namespace or input file. Independent executions must use separate output
directories. No compatibility or migration mechanism for older Vernac versions is required.

The UTF-8 manifest has an explicit format version and records exact, relative generated-file paths. Names are preserved
without Unicode normalization or case folding. Paths must remain within the output directory; symbolic links and path
traversal must not allow access outside it. Unknown manifest formats or malformed manifests fail the build rather than
trigger guessed cleanup.

## Successful generation

1. Read the previous manifest.
2. Compile and validate the complete current source tree; stage all generated sources and check filesystem collisions
   before changing existing output.
3. Compute obsolete paths as previous owned paths minus current generated paths.
4. Atomically record the union of previous and current paths before writing sources.
5. Update generated sources and delete obsolete owned sources. Remove only empty directories made redundant by this
   cleanup, never the output root.
6. Atomically reduce the manifest to current paths after successful synchronization.

The manifest is named `.vernac-generated-sources`. It is an ownership inventory, not a record of the last successful
build. During an interrupted run it may include both old and planned paths. This lets the next successful run reconcile
partial output without backups or rollback. Atomic manifest replacement must be supported by the output filesystem;
otherwise generation fails.

Unchanged source contents should not be rewritten unnecessarily. Unrelated files are preserved. An existing destination
not owned by the manifest must not be silently adopted or overwritten; report the conflict. Missing previously owned
files are harmless. Manual edits to owned generated files are not preserved.

An empty source tree, including removal of the configured source directory, is a valid empty generation result and
removes previously owned output. An existing source path that is not a directory is an error. Explicitly skipped
generation performs no writes or cleanup.

## Failure behavior

Vernac syntax, semantic and generation/preflight failures preserve existing generated sources and the manifest.
Filesystem failures during synchronization fail the build and may leave partial output. There is no rollback guarantee.
The recorded union of owned paths lets the next successful run reconcile partial writes and obsolete sources. Arbitrary
metadata corruption or power loss is not covered by a recovery guarantee; `mvn clean` may be required in those cases.

A filesystem lock rejects simultaneous writers to the same output. The lock file remains in the output directory;
its presence does not mean the lock is held. Independent executions still require separate output directories.
The manifest is build state, not version-controlled application source.

## Java sources versus compiled classes

Removing a generated `.java` file does not itself remove compiled `.class` files. Compiled output can also include
nested, anonymous and other auxiliary classes. These can become obsolete even when the enclosing generated source still
exists.

The Maven integration must establish and test an explicit compiled-output policy. It must not assume that javac or Maven
incremental compilation removes all obsolete classes, nor delete arbitrary application classes by an approximate
filename prefix. A later Java compilation failure must fail the build; a successful package must not contain stale
Vernac classes.

The source manifest alone does not establish ownership of compiled classes. Until compiled-output cleanup is implemented
and verified, `clean` remains the reliable way to discard previous compiled output. This is a temporary limitation, not
the intended final incremental-build contract.

IDE-only compilation and packaging performed outside the supported Maven lifecycle are outside the Maven guarantee and
must be documented separately.

## Acceptance checks

Test consecutive builds in the same project without `clean`:

- Delete a definition; its generated source and compiled classes disappear from output and the packaged JAR.
- Rename a type or change its namespace; only the current artifacts remain.
- Move a source file within the same namespace; generated identities remain unchanged.
- Remove the final definition, final source file or entire source directory; owned output is removed.
- Remove a nested generated class while retaining its enclosing source; stale bytecode is removed.
- Introduce a Vernac error; previous source output and manifest remain intact.
- Preserve unrelated files and reject unowned destination conflicts.
- Reject malformed manifests, unsafe paths, symlinks and filesystem spelling collisions.
- Recover from interrupted synchronization, including newly created output files.
- Run the cleanup again unchanged; it is idempotent.

Use actual Maven compilation and JAR inspection for bytecode acceptance checks. Source-writer unit tests cannot prove
packaging correctness.

## Verification status

The source writer was directly checked for renaming, namespace cleanup, empty output, unchanged timestamps,
preflight failure, interrupted-run reconciliation, unrelated-file preservation, concurrent writers, unsafe paths
and symbolic links. The project build passed after applying the source-cleanup changes.

Compiled-class cleanup and the consecutive-build/JAR acceptance checks remain pending. A successful source-cleanup
build does not yet establish the final bytecode guarantee.
