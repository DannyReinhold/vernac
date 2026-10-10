# Usecase contract

A usecase declares one application operation. It coordinates domain objects,
repositories, services and ports. Domain invariants remain in the domain objects.
This contract replaces the experimental usecase syntax; there is no compatibility mode.

## Declaration

```vernac
usecase RenameTour(TourId, Title)
    returns (TourId, Title)
    uses TourRepository tours
validates {
    require(!self.title().string().isBlank(), "Title must not be blank");
}
behavior {
    execute {
        var tour = tours.byId(tourId);
        tour.rename(title);
        tours.save(tour);
        return resultFor(tour.id(), tour.title());
    }
    private Result resultFor(TourId id, Title title) {
        return Result.of(id, title);
    }
}
```

The example assumes those domain types and repository already exist. Redundant
validation of an invariant already enforced by Title is unnecessary in real code.
A `validates` block is optional and can express operation-specific combinations
of otherwise valid inputs.

* Names are optional for inputs, result fields and dependencies. All use the common
  default-name convention. Collisions require explicit names; no numbering is invented.
* `uses` has no parentheses. Dependencies are comma-separated.
* Exactly one `execute` is required. It has no repeated signature.
* Additional behavior methods must be private and inline.
* No per-type package overrides, load/save statements or inferred result types.
* Java statements belong inside execute/private bodies. Loading and saving use the
  ordinary repository API; persistence is never implicit.

## Types and results

Inputs and results are IDs, value objects, enums or declared collections of those
values. Java scalars require a domain wrapper. Entities, aggregates and collections
of them cannot cross the public operation boundary.

Omit `returns` for void. `returns Title` declares a required single value;
`returns Title?` declares Optional<Title>. `returns (TourId, Title? title)` declares
an embedded public static `Result` record. Empty result tuples are not allowed.

The record provides equals/hashCode/toString over every component. Required
components contain their domain type; optional components contain Optional<T>.
The public canonical constructor checks every component for null, including the
Optional container. It accepts Optional explicitly, not nullable values.

`Result.of(...)` accepts all fields, with @Nullable T for optional components,
and wraps them with Optional.ofNullable. If any fields are optional, a second
factory accepts only required fields and delegates to the full factory. This
includes an of() convenience factory when every result field is optional.

## Nullability and execution

All generated types are @NullMarked. Public execute parameters use @Nullable T
for optional inputs. Required inputs are checked with DomainChecks before validation.
The implementation and private helpers receive optional parameters as Optional<T>.
A null required result or null Optional result throws BehaviorContractException.
Optional.empty() is a valid optional result. Result factories and canonical
constructors enforce their own component checks.

## Preconditions

A separate generated validation class receives only a `<Usecase>Read` interface.
That interface exposes getters for inputs, with Optional getters for optional inputs.
It does not expose dependencies, execute or setters. Rules use self.parameter().
Bare input references are rejected. The existing validation-expression grammar applies;
arbitrary Java statements and lambdas are not introduced by this change.

Preconditions run after null checks and before the implementation, inside the
transaction. They run for inline and external implementations alike. This is
an API boundary, not a Java purity checker or security sandbox.

## Implementation and imports

The Spring bean delegates through a generated top-level implementation class.
Inline bodies and private static helpers live there, without access to bean fields.
Parameters and declared dependencies are ordinary Java parameters of its execute
method. Helpers receive any required values/dependencies explicitly.

Use the existing `java imports { java.util.Locale; }` block first inside behavior.
Imports may not shadow visible Vernac types, built-ins or the generated Result.
Unlike domain behavior, application behavior may use repository dependencies.

```vernac
usecase RenameTour(TourId, Title)
    returns (TourId, Title)
    uses TourRepository tours
behavior {
    execute implemented by com.example.RenameTourImplementation;
}
```

The external class is not required to be a Spring bean. It supplies a public static
execute method. Arguments are inputs in declaration order, then dependencies in
uses order. Optional inputs are Optional<T>. Its result matches the declared Java
result exactly. The generated bridge remains present for external implementations.
A qualified implementation name is used directly. An unqualified name resolves to
an explicit Java import, otherwise the usecase's Java package. Java compilation
checks the external method signature and all inline method bodies.

## Spring and transactions

The bean is generated in `<namespace>.usecase`, uses constructor injection, and
holds no per-invocation state. Its public execute method explicitly declares
@Transactional(propagation = Propagation.REQUIRED).

Through Spring's proxy, execution starts a transaction or participates in the
current one. An injected inner usecase participates in the same physical transaction;
its successful return is not an independent commit. No savepoints or REQUIRES_NEW
semantics are generated. Inner rollback-only status affects the whole transaction.
Spring's standard unchecked-exception rollback rules apply. Input validation,
implementation and result checking are within the intercepted method.

Calling a manually constructed bean directly does not activate Spring transactions.
External I/O is not made atomic by a database transaction. Java object state is not
restored on rollback. Cyclic Spring dependency graphs are not supported magically.

## Generated artifacts and tooling

* `<namespace>.usecase.<Name>`: public bean and optional nested Result.
* `<namespace>.usecase.__VernacBehavior_<Name>`: internal implementation.
* `<namespace>.usecase.access.<Name>Read`: input view when validations exist.
* `<namespace>.usecase.__VernacValidation_<Name>`: validation delegate when needed.

The editor recognizes the new keywords and projects execute/private bodies and
validation expressions into Java contexts. Java usecase type/execute navigation
points to the declaration. Result-component navigation is not added in this increment.

Services, ports and events retain their separately deferred project-generation
status. Declaring a usecase dependency does not silently migrate those generators.
Repository and usecase dependencies work with the reviewed project pipeline.

## Verification

UseCaseGeneratorTest covers shape and diagnostics. UseCaseExecutionTest compiles
real generated Java, runs Optional and validation contracts, and uses Spring proxies
with a counting transaction manager to verify shared REQUIRED transactions and
rollback on validation/result failures. It needs no PostgreSQL server. Existing
persistence tests remain responsible for database transaction behavior.
