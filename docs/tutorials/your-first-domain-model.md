# Your first domain model

Start with the maintained namespace/value example in `examples/namespace-values`.
It demonstrates IDs, value objects, optional fields, validations and imports between
files/namespaces, without a database. The former legacy reactor example has been
removed; this page no longer instructs users to build it.

From the repository root, with the current JDK and snapshot:

```text
mvn -pl examples/namespace-values -am clean verify
```

Read that example's README and its handwritten tests alongside the `.vernac` files.
Try changing a validation or removing a cross-namespace import, then rebuild and
observe the resulting diagnostic or test failure. Restore experiments before committing.

For complete application behavior, continue with:

- [The ports and ACL walkthrough](ports-walkthrough.md).
- The standalone `examples/persistence-demo` README for aggregate persistence,
  events and the prepared migration exercise.
- [The current value-object contract](../contracts/value-objects.md).

A fuller introductory tutorial and all project templates are part of the
[0.2.0 documentation and example audit](../development/release-0.2.0.md).
