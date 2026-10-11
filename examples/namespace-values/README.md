# Namespaces, values and behavior

This small executable example introduces source organization, immutable values,
custom behavior and the first mutable entity graph. It is a reactor module so that
its tests continuously exercise the current compiler. No database or running Spring
application is needed. Build with JDK 25 from the repository root:

```text
mvn -pl examples/namespace-values -am clean verify
```

In the IntelliJ Maven command window, omit `mvn`. No prior local installation of
the compiler/plugin is needed for this reactor build.

## Reading order

All paths below are relative to `src/main/vernac`.

1. `org/example/tasks/identity.vernac`: an ID and a validated Title. The explicit
   field name `value` demonstrates overriding the normal inferred name `string`.
2. `org/example/people/owner.vernac` and `org/example/tasks/task.vernac`: same-namespace
   types need no import; cross-namespace types use a Vernac import. TaskSummaryTest
   checks optional getters, both factories, equality and rejected titles.
3. `org/example/tasks/product-name.vernac`: inline Java, a private helper and an
   external implementation. ProductNameBehaviorTest checks both public methods.
4. `org/example/tasks/behavior.vernac`: optional behavior results and a named list
   with custom behavior. PersonNameBehavior.java implements one method externally.
5. `org/example/delivery/tour.vernac`: an aggregate changes a contained entity through
   a modify method. TourBehaviorTest checks identity, shared references and validation.

Namespace declarations match directories below the source root. Java is generated
under `target/generated-sources/vernac`; domain types use `<namespace>.domain`.
Generated sources are compiler-owned and must not be edited. The compiler removes
orphaned generated Java files using its ownership manifest; `clean` also removes
old compiled classes, which source cleanup alone does not manage.

## Exercises

Remove the OwnerName import and rebuild to inspect the compiler diagnostic. Restore
it, then add an optional value to TaskSummary and adjust the Java test. Try a blank
PersonName to see domain validation rather than a technical Java exception.

The delivery model intentionally puts its non-negative-units rule on Stop to show
entity validation. A production model that forbids negative Units everywhere would
put that rule on Units itself. Failed modify operations currently do not roll back
in-memory changes: discard the failed graph rather than assume it was restored.

Continue with [the persistence walkthrough](../persistence-demo/README.md) for
transactions and database storage.
