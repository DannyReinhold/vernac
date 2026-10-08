# Entity behavior, access views, and possible change contexts

Status: **access views implemented; candidate states deliberately deferred**.
Decision date: 2026-10-08.

This document records both the current implementation and the design explored before it.
It is a basis for subsequent language work, not a promise that every idea below is implemented.

## 1. Goals and the current scope decision

Vernac should support useful DDD models, remove boilerplate, produce understandable Java,
and interoperate naturally with handwritten Java. These goals apply to the compiler and
runtime as well as the generated application. A sophisticated mechanism is not automatically
better if its cost outweighs the problem it solves.

The original problem is validation **after** mutation. An operation can throw the correct
exception while leaving its receiver partially changed or invalid. The usual application
response is to abort the use case and discard that in-memory aggregate. Database transaction
rollback does not restore Java objects, but discarded objects may make that limitation acceptable.

Decision for the present implementation:

- Keep direct mutation of the original objects.
- Explicitly accept retained changes after a behavior or validation failure.
- Introduce read/write access contracts now, independently of candidate-state machinery.
- Validation now uses separate delegates and explicit read-view `self`, consistently
  for value objects, entities, and aggregates. See [validation delegates](validation-delegates.md).
- Do not implement ownership tracking, rollback, deep copies, or snapshot-based entities now.

After a failed modify operation, callers should abort and discard the affected object graph.
Do not assume that catching an exception restores a valid receiver. The framework does not
force disposal; it documents the absence of rollback rather than hiding it.

## 2. Implemented behavior syntax

Entity and aggregate behavior has explicit operation modes. Public visibility is implied:

```vernac
namespace example;

id IntervalId;
value Label(String);

entity Interval[IntervalId](Label, mut int start, mut int end)
validates {
    require(self.start() <= self.end(), "Start must not exceed end");
}
behavior {
    java imports { java.util.Locale; }

    read int length() {
        return self.end() - self.start();
    }

    read String displayLabel() {
        return self.label().string().toUpperCase(Locale.ROOT) + suffix();
    }

    modify void move(int start, int end) {
        self.start(start);
        self.end(end);
    }

    modify void adjust(int start, int end)
        implemented by example.application.IntervalBehavior;

    private String suffix() {
        return "!";
    }
};
```

The primitive fields keep this example focused on behavior. The complete permissible entity
field-type policy is a separate language-review item; this patch does not claim to finish it.

`public read`, `public modify`, and unclassified `public` entity behavior are not alternative
spellings. Use `read` or `modify`. Private helpers remain ordinary private static Java helpers
in the separate generated behavior companion, with no implicit `self` argument.

Value-object, enum, and collection behavior keeps its established `public`/`private` syntax
in this step. They are not mutable entity receivers. Changing their syntax is not necessary
to introduce entity access views.

Java imports are scoped to the behavior companion. The existing import collision rules,
known repository/adapter restrictions, nullable input rules, Optional output rules, and
runtime checks for non-null public reference results continue to apply. An external method
receives the same interface type as its inline counterpart; external implementations are
ordinary Java code, not Spring beans.

## 3. Generated contracts and forwarding classes

For `Interval`, the compiler emits `IntervalRead`, `IntervalWrite`, and `IntervalAccess`
in `<namespace>.domain`:

```java
public interface IntervalRead {
    IntervalId id();
    Label label();
    int start();
    int end();
    int length();
    String displayLabel();
}

public interface IntervalWrite {
    void start(int start);
    void end(int end);
    void move(int start, int end);
    void adjust(int start, int end);
}

public interface IntervalAccess extends IntervalRead, IntervalWrite {
}
```

The generated interfaces and owner use `@NullMarked`. Optional field getters return
`Optional<T>`; optional setter and method inputs use `@Nullable T`. The contracts are public
so an external implementation in a different package can use them.

The entity itself implements **none** of these three interfaces. Its existing runtime
identity interface is independent of them. It has ordinary public getters and public
facades for read/modify behavior, but no public generic field setters.

Private inner forwarding classes implement the access contracts:

- `__ReadView` implements only `IntervalRead`.
- `__AccessView` extends the read forwarding implementation and implements `IntervalAccess`.
- A read operation receives a distinct read-only facade, not a write-capable object merely
  assigned to a narrower variable.
- A modify operation receives an access facade. Setters assign directly to the outer entity.

The behavior companion remains a separate top-level class, preventing direct private-field
access. Both inline and external behavior pass through that companion. Entity instances are
not replaced, copied, or exposed through a generated `unwrap` method.

A simple invalid program such as `read void bad() { self.start(3); }` fails Java compilation:
`IntervalRead` has no setter. The same applies to calls to modify methods through `self`.
These are Java diagnostics, not a new Vernac Java-body analyzer. IntelliJ's injected Java
uses the corresponding generated interface for completion and analysis after generation.

### Limits of read access

This is an access contract for the **receiver**, not a proof of functional purity or a deep
read-only object graph. A getter currently returning a child entity, or a collection of
entities, still returns that existing type. Java code may also receive another mutable entity
as an explicit parameter or perform external side effects.

Consequently a read method can still reach a child's public modify facade through such a
reference. The compiler must not advertise aggregate-wide read isolation yet. Deep read
views, collection element views, and ownership enforcement remain design work. Reflection,
deliberate casts, and hostile code are not a security boundary for this feature.

## 4. Direct mutation and validation timing

Creating and reconstituting an entity or aggregate validates the completed initial state.
`create(...)` generates its own ID via the ID factory. Reconstitution accepts the existing ID.
A required-fields-only create overload delegates to the full create overload when optional
fields exist. No new automatic `fromExternal` factory is generated by the reviewed pipeline.

For behavior, a small synchronous validation scope groups nested modify invocations:

1. The outer modify facade opens a scope and registers its receiver's validation callback.
2. Setters write directly and perform immediate required-value checks; they do not execute
   the complete state invariants after each assignment.
3. Nested modify calls join that scope and register their receivers once, by object identity.
4. On successful outer return, registered callbacks run in reverse registration order, so
   normally nested child calls are checked before the enclosing receiver.
5. The scope is removed in a `finally` block, whether behavior, a result contract, or validation fails.

This registration order is not a computed ownership tree. It does not discover parents of
an independently modified child. Nor does it promise consistency across independently loaded
copies of an aggregate.

For an interval `[10,20]`, `move(30,40)` succeeds even though the first assignment temporarily
creates `[30,20]`. `move(70,60)` throws at the final check and leaves `[70,60]` in memory.
A body that assigns and then throws also retains its assignments. Validation is not invoked
as a substitute for the original exception when the outer body fails.

A caught inner exception does not roll back its writes. If the outer body continues and
returns successfully, final validation sees all retained writes and can reject them. No
implicit rollback-only transaction semantics are claimed.

Access-facade writes are limited to their synchronous scope. Retaining a write facade and
using it after completion, from another thread, during validation, or in an unrelated later
operation throws `IllegalStateException`. Read getters remain live forwarding getters; they
are not frozen snapshots. This guard is a maintenance aid, not an authorization mechanism.

The scope is thread-local. It does not propagate through asynchronous tasks, provide locking,
or make an aggregate thread-safe. In-memory validation and a database transaction remain
separate responsibilities. No external I/O or event delivery can be rolled back by this scope.

## 5. Collections and stable references today

List/set structure remains immutable. `plus`, `minusId`, and similar operations produce a
new collection which a mutable field can receive. Entity elements remain the same instances:

```java
var stop = tour.stops().by(stopId).orElseThrow();
tour.changeDelivery(stopId, newUnits);
// After success, stop.units() observes the new units.
```

An older collection reference keeps its old membership. An older entity reference observes
that entity's current state. Removing an entity from a collection does not destroy existing
Java references. There is no automatic owner assignment or detachment protocol in this patch.

If a tour modify method invokes a stop modify method, both participate in the same validation
scope. A caller invoking `stop.changeUnits(...)` independently only triggers the stop's
validation, not the tour's. Protecting that boundary is part of the deferred ownership work.

## 6. Candidate-state design explored, but NOT implemented

The following is retained for future evaluation. It is not the current runtime contract.

### Intended benefit

A rejected domain operation would leave all previously committed in-memory state unchanged,
while allowing natural multi-field edits and temporary intermediate inconsistency inside
one operation. The benefit is strongest when an application catches a validation failure,
tries alternatives, or continues using the receiver. It may be modest where every failure
aborts the use case and the loaded graph is immediately discarded.

### Stable entity identity with effective state

The preferred direction was **not** to replace entity instances with copies. Entity methods
would route state access through the active change context:

- Outside an operation, getters read committed state.
- Inside an operation, getters read proposed state when present.
- Setters write only proposed state.
- Nested operations and validation observe the same proposals.
- Successful final validation transfers state into the original entity instances.
- Failure discards proposals.

A previously held `Stop` reference would observe updated values after success and original
values after failure. Candidate state is an implementation detail beneath stable entities.
Immutable value objects may be shared; mutable child state needs its own proposals. A shallow
copy of the root alone cannot achieve isolation.

### Outermost boundary and lifetime

Nested modify methods must join the outer operation. They must not independently validate
and commit temporary states. Final validation includes affected child invariants and the
relevant aggregate invariants, including parents whose own fields were not directly assigned.

A candidate access handle would expire when the operation ends. Attempts to use it afterwards
should fail immediately. Returning or storing such handles, returning child entities, and
retaining collections require explicit rules. Ordinary returned IDs and immutable values
are easier to support.

### Commit considerations

Commit should only transfer already checked state, without executing user callbacks, behavior,
or new validations. Preserving Java object references is desirable. However, transferring
several entity states is not automatically atomic to concurrent observers. The first scope
would remain synchronous and non-concurrent; stronger guarantees need separate design.

External calls, already published events, and arbitrary Java side effects cannot be undone.
Event buffering, result handling, fatal runtime failures during commit, and concurrency all
need explicit decisions before any rollback guarantee is advertised.

## 7. DDD ownership and lifecycle: proposed direction

An aggregate is the consistency boundary. Domain repositories operate through roots; contained
entities should not have independent mutation/persistence paths bypassing the root. This does
not imply one SQL statement or rewriting every row. Read projections may query data without
materializing a mutable aggregate.

A common use case should look like:

```java
Tour tour = tours.byId(tourId);
StopId stopId = tour.addStop(address, units);
tours.save(tour);
```

The root behavior may call `Stop.create(...)` and then insert the new stop. A briefly unbound
entity during construction is not inherently wrong. Creating an entity in a context does not
by itself mean it was inserted into that aggregate.

A possible internal ownership model distinguishes:

- Persistent in-memory membership in an aggregate instance.
- One short-lived modification context.
- One proposed state per participating Java entity instance.

A neutral internal owner handle could avoid coupling an entity type to a concrete aggregate
class. During reconstitution, infrastructure could create the handle, assemble the graph,
bind members, validate, and only then publish the completed aggregate. Passing a root into
every public factory is not a settled requirement.

Unresolved questions include:

- When exactly does insertion establish ownership, and how is failed insertion undone?
- Are duplicate occurrences of the same entity instance allowed in a list? How do removal
  and multiple containment paths affect membership?
- What happens to removed entities and existing references?
- Can an entity move between aggregates, and through which explicit domain operation?
- How are cycles, shared children, and equal IDs in separate Java instances handled?
- Should callers receive child entities, live read views, or another representation?
- How can a child mutation reliably locate the aggregate whose invariants must be checked?

These are reasons to defer the full candidate mechanism rather than ship accidental answers.

## 8. Reconstruction, external data, and persistence evolution

Reconstitution checks current state invariants, but must not replay creation actions or
creation-time business eligibility rules. Existing historical data may no longer satisfy a
changed invariant. Application developers must make a conscious decision about that mismatch.

Vernac must not require destructive rewrites of historical records. Future options can include
schema/data migration, versioned representations, and explicit translation when loading.
Some deployments can migrate eagerly; others require online, resumable, staged, or separate
migration jobs. Deployment timeouts must not dictate the only supported migration strategy.

Future tooling may compare a recorded model baseline, identify affected persisted shapes,
and require a documented migration/compatibility decision. It cannot prove arbitrary Java
validation code backward-compatible. Schema diffs, IDE help, Maven checks, and migration
execution responsibilities will be designed in the persistence review.

External input is a different concern. ACL translates external semantics into the local
model and validates the result. The preferred default is an internal identity plus an
explicit external reference or mapping, when the application actually needs integration.
No external-reference fields should be generated merely because a type is an entity.
Repeated imports need stable identity mapping rather than repeated creation. Reconstitution
must not become a convenient validation bypass for external input. An automatic `fromExternal`
API and any integration keyword remain deferred.

## 9. Tooling, tests, and remaining work

The current patch includes parsing and mode representation, project type resolution for the
entity/aggregate behavior slice, generated interfaces, runtime delegation, keyword highlighting,
Java injection with correctly typed `self`, and Java-to-Vernac navigation for these types and
public members. The IDE requires generated Java interfaces on its indexed source path, just
like existing injected behavior required generated value-object classes.

Tests compile the generated Java and exercise both successful and failing modifications,
nested calls, private helpers, external delegates, expired write access, nullable fields,
collections and child identity. Negative Java compilation tests demonstrate read access
restrictions. Existing tests remain; deferred-feature tests now use an unreviewed event
instead of an entity, and the standalone compiler example uses the new behavior syntax.

Legacy low-level generator entry points still serve tests and unreviewed infrastructure code;
the reviewed project/compiler path uses access-based generation. Existing SQL schema constants,
aggregate timestamps/version APIs, and event plumbing have not been redesigned here. Their
presence does not constitute a completed DB mapping or aggregate lifecycle contract.

Next steps:

1. Review generated Java for readability and try a small read/modify example.
2. Validation delegates are implemented. Any future shorthand requires explicit
   name-resolution rules; do not rewrite arbitrary Java tokens.
3. Continue the entity/aggregate contract: field types, child access, ownership, lifecycle,
   events, and operation-boundary semantics.
4. Review persistence, schema evolution, and migrations together.
5. Revisit candidate-state isolation only if its practical value justifies its complexity.
