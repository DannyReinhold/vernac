---
title: Explicit usecase access at application startup
---
# Explicit usecase access at application startup

Status: implemented in **0.6.0-SNAPSHOT**. This is a Spring Boot application composition
check, supplementing [usecase authorization](usecase-authorization.md). It is not an
identity system, a policy evaluator or a whole-program security proof.

## Application declaration

```vernac-application
application example.app {
    authorization explicit;
}
```

The declaration belongs in `vernac-application.vapplication` beside the POM. At most
one declaration is allowed. Unknown modes and duplicates MUST fail parsing. It MUST
NOT appear in `.vmodule` or be enabled by a producer's application configuration during
module publication. The consuming application owns this assembly decision.

`authorization explicit;` and `tenancy explicit;` are independent. Neither implies
the other, marks ordinary aggregates tenant-bound, installs login/security filters,
or grants any permission. Ordinary applications without this declaration preserve
the existing omitted-clause behavior. The complete SaaS assembly MUST enable it;
the separate SaaS Wizard/starter remains future work and is not supplied by this slice.

## Inventory and registered reachability

The compiler generates a Spring BeanFactoryPostProcessor and an inventory of local
usecases plus public/internal dependency usecases from module-contracts-12 metadata.
Each inventory entry contains only the nominal operation identity, compiled class and
access mode. No actor, input, token, grant, secret or policy implementation is serialized.
The generated configuration is included through the existing application imports resource,
even if there are no adapter bindings or module activations.

After bean-definition registry processors have applied activation/provider selection,
the check MUST inspect registered usecase types, including lazy definitions. Every
registered generated usecase is a callable entry and therefore a conservative root:
it MUST declare `requires public`, `requires authenticated` or a permission/tree.
An internal operation does not escape this rule merely because its type is unexported
or a particular execution branch normally does not call it. This includes operations
reached through injected services, listeners and adapters once their module registers
those usecases. The check does not analyze Java branches or preauthorize inner calls.

Inactive type-only module dependencies MUST NOT require access declarations merely
because their metadata is present. Normal profile/provider resolution determines the
actual registry; profile selection does not change what an existing requirement means.
Registering a dependency usecase manually also makes it subject to the inventory check.

## Failure and execution timing

A registered inventory entry with mode OMITTED MUST fail normal startup before its
usecase is instantiated. The diagnostic MUST list all discovered omissions in stable
order and explain the explicit alternatives. Operation names are safe design metadata;
request data and arbitrary provider-selection values MUST NOT be included.

Inspection MUST NOT call a usecase, resolve an actor, evaluate a permission, open a
business transaction, load domain data or initialize a lazy FactoryBean to discover
its product type. A bean postprocessor additionally rejects an omitted generated
usecase if a type-opaque lazy FactoryBean exposes it later. For such opaque custom
factories, rejection occurs when the product becomes available, possibly at first
lookup rather than startup. Generated normal/module registrations have known types
and are checked at startup. The factory's own construction is not rolled back by this
inspection; do not put business side effects in Spring construction.

Accepting a declared mode only confirms explicit intent. `requires public` adds no
permission; authenticated/permission modes still need their normal runtime bindings.
Every reached protected call still evaluates its own requirement with its actual inputs
inside the existing REQUIRED boundary. Successful startup MUST NOT cache a grant or
precheck all inner permissions. Missing guard/policy infrastructure still fails through
its separate startup contract.

## Limits and evidence

This is a supported generated-application check, not a sandbox around arbitrary Java.
Direct `new` calls outside Spring, dynamically altered metadata, custom unmodeled entry
operations and arbitrary reflection are outside its discovery guarantee. Transport
security, resource ownership, tenant isolation, policy correctness and operational
monitoring remain separate responsibilities. No exemption/allowlist is introduced in
this first slice; make a deliberate explicit declaration instead.

Runtime tests cover eager/lazy inventory inspection, explicit modes, inactive types,
stable errors, malformed inventory and opaque factory products. Generated Spring tests
compile local and module Java and verify local omission failure, explicit public entry,
private activated operations, inactive dependencies and ordinary compatibility. Compiler
and LSP tests cover syntax, application-only scope, soft keywords and completion.
Final installed-plugin/manual and full SaaS end-to-end acceptance remain OPEN.
