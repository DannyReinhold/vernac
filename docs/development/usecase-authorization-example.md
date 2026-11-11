---
title: Who loads the invoice? A complete resource-policy example
---
# Who loads the invoice? A complete resource-policy example

Status: **design example for review; authorization APIs are not implemented**.
This accompanies the [proposed authorization contract](usecase-authorization-contract.md).
It explains who supplies each part of `permission ... on invoiceId`, including data
access and registration. It is not a complete invoicing/SaaS application or evidence
that the Spring feasibility spike passed.

## The boundary in one sentence

The consuming application supplies the rule that connects its Invoice to its Account;
the reusable authorization module supplies effective member/entitlement decisions;
the generated usecase guard selects and invokes an explicitly registered typed policy.
Neither Vernac nor Spring Security discovers invoice ownership by convention.

## 1. Application model and an ordinary repository/service

This part uses existing Vernac model, query and service constructs. AccountId is a
shared identity here; it could instead be explicitly imported from an account module.
The invoice is deliberately minimal: it is not a financial document/tax model.

```vernac
namespace example.invoicing;

id AccountId;
id InvoiceId;
value InvoiceNumber(String);
value ExportLocation(String);

aggregate Invoice[InvoiceId](AccountId, InvoiceNumber);

repository InvoiceRepository for Invoice {
    find Invoice? inAccount(InvoiceId, AccountId)
        where id = :invoiceId and accountId = :accountId;
}

service InvoiceAccess uses InvoiceRepository behavior {
    public boolean belongsToAccount(InvoiceId, AccountId) {
        return self.invoiceRepository()
                .inAccount(invoiceId, accountId)
                .isPresent();
    }
}

port InvoiceExporter {
    ExportLocation export(InvoiceNumber);
}
```

**InvoiceAccess loads the invoice**, through its constructor-injected repository.
`inAccount` is a chosen method name, not name-derived SQL. Its explicit predicate
requires both the ID and the supplied account. The Optional result is empty for a
missing invoice and for one in a different account. Both produce the same public
policy denial. Repository failures are technical errors, not `false`.

There is no hidden `exists`, scalar projection or partial aggregate in this example.
Under the current repository contract, a match materializes a **complete aggregate**.
The service opens no transaction; the outer usecase's REQUIRED transaction supplies
it, satisfying the repository's MANDATORY requirement. The service is an ordinary
reusable ownership rule, not another usecase with recursive authorization.

The example's AccountId field is immutable. Applications with transferable resource
ownership need an explicit concurrency strategy: authorization followed by a second
read is not a lock or a guarantee that facts cannot change.

## 2. Declare the protected usecase

Add the following to the same namespace. These permission/requirement clauses are
**proposed syntax**, not executable with the current compiler:

```text
permission InvoicePermissions {
    EXPORT;
}

usecase ExportInvoice(InvoiceId)
    returns ExportLocation
    requires permission InvoicePermissions.EXPORT on invoiceId
    uses InvoiceRepository, InvoiceExporter
behavior {
    execute {
        var invoice = self.invoiceRepository().byId(invoiceId);
        return self.invoiceExporter().export(invoice.invoiceNumber());
    }
}
```

The exporter port deliberately leaves the actual storage/PDF implementation behind
an adapter; its behavior is not part of this ownership-policy example. Permission
assignment, account creation and pricing are also separate module responsibilities.
The author does not add an ownership query to this execute body merely to trigger
authorization. Both inline and implemented-by bodies use the generated entry guard.

## 3. Give the rule a precise typed binding

For the first feasibility spike, use an explicit Spring bean rather than inventing
a second policy-language construct. The following is a **proposed Java SPI and its
usage**; package/interface names and generated binding support do not exist yet.
This is a concrete developer-facing design to validate, not a current API promise.

Semantically, a resource-policy binding contains:

- Exactly one declared permission key.
- The nominal resource input type (InvoiceId).
- The expected verified execution-scope type (AccountId).
- A stateless callback receiving that typed context and resource.

For readability, the SPI is sketched as `ResourcePermissionPolicy<R, S>` with a typed
`bind` factory accepting a callback `(AuthorizationContext<S>, R) -> AuthorizationDecision`.
`context.actor()` is an opaque trusted actor reference; `context.scope()` is S.
`AuthorizationDecision` has `allow()`, `deny()` and `isAllowed()`. A denial contains
only a safe reason; technical errors propagate separately. These names are proposals.

The account subsystem is reached through this application-side Java integration port:

```java
// Proposed integration types; imports from the future authorization runtime omitted.
public interface AccountPermissionChecker {
    AuthorizationDecision decide(
            ActorReference actor,
            AccountId accountId,
            InvoicePermissions permission);
}
```

Its implementation is supplied by the configured authorization/SaaS module adapter:
it checks enabled membership, assigned rights and applicable purchased benefits in
that account. It must be bound explicitly; there is no default implementation that
allows everything. Its storage/role/plan rules belong to the later D05 contract.
This example does not pretend those rules or adapters have already been implemented.

The application registers its invoice-specific composition:

```java
// Proposed integration API; not current compilable application code.
@Configuration(proxyBeanMethods = false)
public class InvoiceAuthorizationConfiguration {
    @Bean
    ResourcePermissionPolicy<InvoiceId, AccountId> invoiceExportPolicy(
            InvoiceAccess invoiceAccess,
            AccountPermissionChecker accountPermissions) {
        return ResourcePermissionPolicy.bind(
                InvoicePermissions.EXPORT,
                InvoiceId.class,
                AccountId.class,
                (context, invoiceId) -> {
                    var accountDecision = accountPermissions.decide(
                            context.actor(),
                            context.scope(),
                            InvoicePermissions.EXPORT);
                    if (!accountDecision.isAllowed()) {
                        return accountDecision;
                    }
                    return invoiceAccess.belongsToAccount(invoiceId, context.scope())
                            ? AuthorizationDecision.allow()
                            : AuthorizationDecision.deny();
                });
    }
}
```

Register this configuration through the application's ordinary Spring configuration
or explicit module auto-configuration, and activate/provide the involved functional
modules under the existing module contract. Java-only dependencies are not inferred
from a lambda: a binding must not assume its repository/service module was activated
just because its classes are on the classpath. Required activation metadata or explicit
application activation must be part of the first implementation.

Spring injects the generated InvoiceAccess service and the account-permission adapter.
The account check runs first; an unauthorized member need not trigger an invoice
query. This callback is the **single composed policy decision** for this requirement:
the guard must not independently repeat the same account/role/entitlement check.
Neither component calls the protected usecase or the global policy dispatcher again.
Such recursive policy routing must be diagnosed rather than overflowing the stack.

An independently supplied VIEW requirement needs its own binding or an explicit
multi-binding helper; registering EXPORT does not grant/register VIEW automatically.
A requirement without `on` needs a separately registered scope-only policy; it does
not automatically borrow this resource binding or invent an InvoiceId.
No priority-based or first-bean-wins resolution: the dispatcher selects the exact
permission/resource/scope binding. Missing or ambiguous binding fails startup.
The bound scope type must match verified context, with a controlled configuration
error rather than an unchecked cast, global fallback or string-based type coercion.

## 4. How the typed context reaches that binding

1. Spring Security authenticates the request. A trusted adapter maps the principal
   to ActorReference, without retaining credentials in domain code.
2. An account resolver verifies the selected account's membership/access and maps
   it to AccountId. A browser's X-Account header or path value alone is not trusted.
   A selection kept in a session must be revalidated under the chosen revocation policy.
3. The generated usecase guard supplies the current trusted actor/account context and
   the actual InvoiceId input to the exact policy binding.
4. `AccountPermissionChecker` determines effective EXPORT rights for that actor/account;
   InvoiceAccess checks the invoice's ownership through the declared repository query.
5. The guard allows execution only if both checks allow. No selected/verified account,
   unknown binding or unavailable policy source can turn into a global permission.

The account resolver's integration is required configuration, not inferred from the
name AccountId. An imported/shared AccountId must retain its nominal module identity.
Technical actors need explicit account authorization, not a fake browser login or
unconditional membership. Roles and plans are not submitted by the client.

## 5. Concrete outcomes and SQL work

| Situation | Account decision | Ownership query | Result |
| --- | --- | --- | --- |
| Member with EXPORT, invoice in current account | Allow | Match | Run ExportInvoice |
| Member without EXPORT | Deny | Not executed | ACCESS_DENIED; no exporter call |
| EXPORT in A, requested invoice belongs to B | Allow for A | Empty for A | ACCESS_DENIED; no exporter call |
| EXPORT in A, requested invoice does not exist | Allow for A | Empty | Same safe ACCESS_DENIED |
| Account/ownership backend fails | Technical failure | As applicable | Technical failure, never ordinary denial |

The body may load the invoice again via byId after the ownership query. Under the
current contract **two aggregate loads are possible**; there is no automatic identity
map, data transfer from the policy or projection cache. This cost is intentional and
visible for the first correctness-oriented spike, not hidden by an invented query API.
If the invoice disappears after authorization, the ordinary byId failure remains.

A transaction alone does not guarantee an unchanged snapshot at every statement.
For mutable authorization facts use scoped reads/appropriate locking and a defined
consistency policy. Do not cache an Invoice or Allow on singleton policy/service
fields. Later optimization may use explicitly typed invocation-local facts, but must
prove freshness and isolation before changing this contract. Tenant-aware repositories
remain useful for defense in depth and must not be replaced by an unscoped permission
assumption elsewhere in the application.

## 6. Developer effort and the next decision

The product developer writes the ownership rule with ordinary Vernac queries/services,
and a small explicit binding. Reusable modules supply the account-grant machinery and
trusted context integration. This example intentionally exposes that division rather
than suggesting that `on invoiceId` discovers persistence or tenant relationships.

For now, prefer proving the typed Java binding first. A later declarative binding
could reference an existing service operation and known context values, but its
syntax must be reviewed separately. It should remove registration boilerplate, not
invent ownership fields, database projections or role/plan precedence.

Review before freezing the contract: whether this small Java assembly step is an
acceptable initial integration point, and whether a declarative service-operation
binding should be part of the first production language slice. Neither choice changes
the already agreed aim of declarative usecase requirements.

Spike acceptance: inject real generated InvoiceAccess/InvoiceRepository and the
candidate typed dispatcher; prove the outcome table with PostgreSQL, nominal-type
isolation, same-transaction reads, no recursion/duplicate policy evaluation, missing/
ambiguous bindings and no sensitive data in captured logs. Keep the broader
[release ledger](release-0.6.0.md) OPEN until those tests actually pass.
