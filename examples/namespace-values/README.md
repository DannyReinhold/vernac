# Namespaces and value objects

This focused example exercises the refactored language through the Maven plugin.
It contains three Vernac files in two namespaces, followed by a handwritten Java
test using the generated classes. It needs neither Spring nor a database.

Run from the repository root:

```text
mvn -pl examples/namespace-values -am clean verify
```

Maven first builds the compiler/plugin and then generates, compiles, and tests this
example. No prior local installation of the plugin is required.

- `identity.vernac` defines `TaskId` and `Title`.
- `task.vernac` uses both without an import because they share its namespace.
- `owner.vernac` defines `OwnerName` in another namespace; `task.vernac` imports it.
- `TaskSummaryTest` demonstrates full and required-only factories, optional getters,
  equality, and validation failures.

Each namespace matches the directory below `src/main/vernac`. Generated Java is
written below `target/generated-sources/vernac`, with `.domain` appended to each
namespace. The Maven plugin registers that directory automatically.

Try changing the title invariant, removing the external import, or moving a
Vernac file to a directory that does not match its namespace. Run the same Maven
command to see the resulting runtime test failure or source-aware compiler error.

This example covers IDs and VOs only. Project generation for other declaration
categories is still being migrated. Existing broader examples remain separate.
Generated-file cleanup after deleted or renamed definitions is not yet implemented;
use `clean` while working through this refactoring.
