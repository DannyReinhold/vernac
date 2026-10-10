# Domain service contract

A `service` declares a stateless domain capability whose responsibility does not
naturally belong to an entity or value object. It exposes named operations, even
when there is only one. There is no special execute method, implicit return
inference, custom package or generated Result DTO.

```vernac
service TourPlanning
behavior {
    public void moveStop(Tour source, Tour target, StopId)
    validates {
        require(!self.source().id().equals(self.target().id()),
                "Source and target tour must differ");
    } {
        var stop = source.allStops().by(stopId).orElseThrow();
        source.removeStop(stopId);
        target.addStop(stop);
    }
}
```

## Public API and dependencies

A Spring `@Service` bean is generated in `<namespace>.domain`. It has no mutable
per-call state. Required dependencies declared by `uses Type name, OtherType`
are constructor-injected into private final fields. Omitted names use the shared
Vernac naming convention. Conflicts require explicit names.

Dependencies may be domain services, domain ports or repository interfaces.
Usecases and concrete adapters are not service dependencies. Repository access is
possible, but application orchestration and transaction ownership normally remain
in the usecase. Ports still require their separately planned pipeline review;
a service dependency does not activate a deferred port generator.

Only public operations appear on the bean. Dependency getters are exposed on
`<namespace>.domain.access.<Service>Access`, not on the bean. A private inner
class implements that interface; the service itself does not implement it.

The Access interface exposes dependency getters and public service operations.
Calls through `self.otherOperation(...)` return through the bean wrapper and run
that operation's null checks, preconditions and result checks again. Recursive
calls behave like ordinary Java recursion; the compiler does not prove termination.

Services do not introduce transactions. A usecase provides the transaction when
needed; pure calculations can run without one. Repository MANDATORY behavior is
unchanged. Services are responsible for using aggregate behavior rather than
bypassing aggregate invariants. No automatic object rollback is provided.

## Implementation

`behavior` contains optional `java imports { qualified.Type; }`, followed by
public operations and private inline helpers. Each operation declares its return
type and parameters explicitly; parameter names may be inferred. `read` and
`modify` are not service modifiers, and `mut` parameters are not supported.
IDs, values, enums, collections, entities and aggregates are supported in signatures,
as are approved built-in Java types. Signature types use namespace resolution.
Java imports support implementation bodies; they do not bypass Vernac type checks.

Inline code lives in `__VernacBehavior_<Service>`, a separate implementation class.
Public implementations receive `self` of the Access type, followed by their
parameters. They cannot access the bean's private fields. Private helpers are
static, have only their declared parameters, and receive any needed dependency
explicitly, e.g. `helper(self.policy(), input)`.

An external implementation is selected per public operation:

```vernac
public Title choose(Title title) implemented by com.example.TitleSelection;
```

It provides `public static Title choose(ServiceAccess self, Title title)`.
The implementation class name must be fully qualified, as for domain behavior.
It is not required to be a Spring bean. Delegation still passes through the
normal generated behavior companion. Constructors/injection in the external
class are not part of this protocol. Private helpers cannot use implemented by.

All generated types are NullMarked. Public optional input parameters are
`@Nullable T`; implementation and private-helper optional parameters are
`Optional<T>`. Optional results are `Optional<T>` throughout. Required reference
inputs use DomainChecks; null required/Optional public results use
BehaviorContractException. Empty Optional is valid. Primitives are not null-checked;
optional primitive types require a boxed type or domain wrapper.

## Per-operation preconditions

A public operation may place `validates { require(...); }` between its signature
and its inline body or implemented-by clause. Preconditions run after input null
checks and before delegation, for both implementation forms.

In validations, self is an input view, NOT the service Access view. It exposes
only parameter getters; optional parameters return Optional. It has no dependency
getters or service operations. Bare parameter references receive a Vernac diagnostic.
The generated input interfaces are nested technical types within ServiceAccess;
their numbered names are not an application API and can change when methods move.

**Protection is shallow.** Entity/aggregate parameter getters return the actual
objects, and collections can contain mutable objects. Calling a getter does not
make the reachable graph immutable. Developers must keep preconditions free of
mutation and external effects. Existing Read interfaces also return ordinary child
objects, so recursively read-only wrappers were deliberately deferred to avoid a
large refactoring. This is a design aid, not a Java purity/security proof.

## Consistency and follow-up

Service implementations receive dependencies through self; usecase implementations
currently receive dependencies as trailing parameters. Both use constructor
injection on the generated bean and separate implementation classes. Unifying
these implementation protocols is an explicit roadmap item, not a settled permanent
difference. We keep this increment focused and do not rewrite usecases again.

## Tooling and tests

The compiler validates names, resolved types, dependency kinds, overload collisions,
imports and explicit validation access. Java compilation checks method bodies and
external signatures. Editor projection supplies Access/input-view contexts and
Optional implementation parameters. Navigation from Java service types and public
methods targets the corresponding Vernac declaration.

Tests cover parsing, semantic errors, generated Java compilation, real Spring
constructor injection, inline/external calls, self calls, Optional/null contracts,
preconditions and identity-preserving transfer between aggregates. The transfer
test operates in memory; it is not a cross-aggregate PostgreSQL integration test.
