---
title: Declarative usecase authorization
---
# Declarative usecase authorization

Status: **local and reusable-module declarations with generated enforcement implemented
in 0.6.0-SNAPSHOT**. Use matching compiler/runtime/tooling versions and rebuild module
artifacts for module-contracts-11. This is not a complete identity/roles/subscription
system or exposure audit.

## Declarations and identities

```vernac
namespace example.authorization;

id InvoiceId;
permission InvoicePermissions {
    VIEW;
    EXPORT;
}

usecase ExportInvoice(InvoiceId) returns InvoiceId
    requires all {
        permission InvoicePermissions.VIEW on invoiceId;
        permission InvoicePermissions.EXPORT on invoiceId;
    }
behavior {
    execute { return invoiceId; }
}
```

This is a complete compilable declaration example. Its identity-returning body only
illustrates the access boundary; it does not export an invoice. Running the protected
operation requires the [Spring runtime bindings](usecase-authorization-runtime.md),
a real transaction, a verified actor/tenant and an application policy.

A permission declaration MUST have at least one distinct Java-valid member. Members
are separated by semicolons. The declaration participates in normal Vernac namespace,
import and duplicate-name rules. Import the Vernac identity, for example
`example.authorization.InvoicePermissions`, not its generated Java package.

The generated enum is `<namespace>.authorization.<Declaration>`, implementing
PermissionKey. `qualifiedName()` yields `<namespace>.<Declaration>.<member>`. The
Java package suffix is not part of that stable permission identity. Constants such
as String, name, values and qualifiedName remain valid when Java permits the spelling.
Declaring a key grants nothing. Renaming deployed keys needs a deliberate stored-grant
migration; no alias or inferred permission inheritance exists.

Permissions are technical authorization types, not domain enums. They MUST NOT be
used as aggregate/entity/VO fields, persisted values or usecase input/result types.
Ordinary `value Status = ...` declarations remain domain enums and cannot be used
as permission declarations.

## Clause order and requirement tree

The order is inputs, optional returns, optional requires, optional uses, optional
validates, then behavior. A usecase has at most one requires clause.

```text
requires public
requires authenticated
requires permission InvoicePermissions.EXPORT
requires permission InvoicePermissions.EXPORT on invoiceId
requires any {
    permission InvoicePermissions.EXPORT on invoiceId;
    permission InvoicePermissions.VIEW on invoiceId;
}
requires all {
    permission InvoicePermissions.VIEW on invoiceId;
    any {
        permission InvoicePermissions.EXPORT on invoiceId;
        permission InvoicePermissions.VIEW;
    }
}
```

These are alternative clause fragments. A standalone requirement has no semicolon.
Permission leaves inside groups have semicolons; nested groups have no trailing
semicolon. Groups MUST be nonempty. public/authenticated MUST be standalone, not
alternatives inside a group. Exact duplicate sibling leaves are rejected after
normalizing permission references, including simple versus fully qualified spelling.

`on` MUST identify one named required ID/VO input (including domain enum VOs). Resolve
implicit input names first. Optional values, collections, pages, unknown names,
dependencies, getters, result references and arbitrary Java expressions are not
resource inputs. The runtime receives the actual nominal Java type and value, not a
String/UUID obtained by guessing. A compound required VO can be a resource reference.

No role/plan names, not operator, arbitrary predicates, quotas or side-effectful
restrictions are introduced into this tree. Those require separate contracts.

## Meaning and generated entrypoint

| Form | Enforcement |
| --- | --- |
| Omitted requires | Existing execute/constructor behavior; no new guard or inferred public classification |
| requires public | No identity or permission prerequisite. An optional Guard provider preserves invocation context when configured; absence does not require installing authorization infrastructure |
| requires authenticated | A required Guard verifies an enabled human or explicitly mapped technical actor; no permission evaluator call or tenant selection is required merely by this mode |
| Permission/group | A required Guard verifies actor and selected tenant, then evaluates the pure policy with ordered all/any semantics |

The optional public provider does not supply an allow policy or grant access to an
inner protected usecase. It neither clears an outer context nor replaces Spring URL
security. It also does not infer a public route. A protected usecase's required Guard
constructor dependency fails bean creation if missing; use the explicit runtime
configuration to reject missing or ambiguous actor/policy providers at startup.

Generated execute retains Spring `@Transactional(REQUIRED)`. Its order is:

1. Open/join REQUIRED through Spring and enter the normal event context.
2. Check mandatory input nullness with DomainChecks (DomainValidationException).
3. Enter the applicable authorization frame and enforce the requirement.
4. Run validates, inline/external behavior and result checks.
5. Close scopes on every exit; commit/rollback at the outer Spring transaction boundary.

A denied operation MUST NOT execute validates, implementation, writes, emits or external
I/O. The normal required-null/binding errors can precede authorization. An actually
reached inner usecase checks its own requirement; unreachable branches are not
preauthorized, and an outer Allow is not cached as an inner grant. A caught inner
REQUIRED runtime failure can leave the transaction rollback-only. No independent
commit, savepoint, retry or in-memory rollback is introduced.

Inline and implemented-by bodies use the same generated guard and ordinary Access
contract. They receive no credentials, Spring Authentication or replacement actor/
tenant setters. Java callers still use the same execute parameter/result signature.
Manual construction does not create a transaction and protected direct execution
fails technically before policy/body; direct static companion calls are unsupported.

all/any evaluate in declaration order and short-circuit. Technical failures abort
immediately, including inside any. Deny is not an infrastructure outage. The existing
[runtime contract](usecase-authorization-runtime.md) defines these semantics and safe
failure codes, transaction/tenant pinning, context lifetime and Spring principal mapping.

## Integration and boundaries

Keep the application's Spring Security filter chain for authentication, session/token
handling, CSRF and URL restrictions. Bind ActorResolver and PermissionEvaluator
intentionally. The mapper must verify trusted principal/issuer and enabled subject;
authentication is not tenant membership or a plan grant. Resolve tenant selection
through trusted membership integration before tenant repository use. No user model,
organization field or account policy is inferred from a resource ID.

Policies can read through MANDATORY tenant repositories inside the existing transaction.
They must not mutate business state or consume quota. RLS/JDBC scope enforcement remains
independent: a policy Allow cannot authorize cross-tenant SQL. Missing scope never
becomes unrestricted global permission. The current permission SPI requires a selected
tenant; a separate global policy-scope mechanism is not supplied.

The shared terminal HTTP/MVC defaults are 401 AUTHENTICATION_REQUIRED; 403 ACCESS_DENIED/
SCOPE_REQUIRED; 500 AUTHORIZATION_CONFIGURATION_ERROR; 503 AUTHORIZATION_UNAVAILABLE.
Generic domain/error rules cannot override them or run a redisplay loader. A normal
repository absence after authorization retains 404. Policies must not distinguish
hidden and missing resource facts through public denial details.

## Modules, IDE and acceptance limits

Permissions and usecase requirements are preserved in module-contracts-11 metadata.
An exported usecase's permission types MUST be explicitly exported by their owning
module. Imports do not activate components or grant rights. Public and internal
usecase access headers are retained for dependency compatibility checks; changed
requirements require security review and a dependent rebuild. See the
[module authorization contract](module-authorization.md) and
[publication walkthrough](../tutorials/module-authorization.md).

The shared compiler supplies live diagnostics; LSP adds the new keywords, declaration
indexing and a permission snippet. Native references locate permission declaration
names in requires, and Java navigation locates generated permission enums/constants.
Imported permission declarations support read-only metadata navigation and vocabulary
hover without a sources attachment; available source attachments improve declaration
locations. Advanced requirement/member completion, resource-name navigation and full
installed-IDE acceptance remain follow-up work.

Compiler tests cover invalid declarations/trees/resources/imports, ordinary Java/domain
name collisions, explicit module export closure and authorization compatibility. Generated PostgreSQL/Spring tests
compile the real enum/guard code and prove null/deny ordering, validates suppression,
inline/external parity, public/authenticated modes, nominal cross-tenant resource
reads, missing-Guard startup, conditional nested checks and REQUIRED rollback.
The SaaS reachable-usecase exposure audit, identity/membership/role/plan policies,
limits/quotas and full 0.6.0 acceptance remain OPEN.
