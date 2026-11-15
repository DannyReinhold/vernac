---
title: Durable payment application and resource registry
---
# Durable payment application and resource registry

Status: application implementation in progress. This topic does not yet close A09/A23.

```vernac-application
application consumer.paymentprocessing {
    tenancy explicit;
    authorization explicit;
    activate vernac.saas.subscriptions;
    activate vernac.saas.payments.local;
    activate vernac.saas.payments.jdbc;
    bind org.vernac.saas.payments.PaymentProvider
        to org.vernac.saas.payments.local.LocalPaymentProvider;
}
```

PaymentFlow is explicitly constructed with named providers, a plan catalog, the actual
SubscriptionLedger, a mandatory current-administrator authority and mandatory webhook
admission. These are application trust boundaries, not allow-all defaults. No scheduler
or public controller is installed automatically. Global payment writes reject inherited
actor/tenant/transaction contexts. Public account operations check current authority
before disclosing data or recording work. Ordinary product membership rights remain
separate from purchased allowances.

A stable request operation identifies a submitted checkout form. The first request
records its exact account, subscription, terms, period and provider profile; repeating
it returns the registered order without changing subscription renewal terms. Account
locks prevent parallel pending checkouts. Retrying a proven failed/cancelled/expired
attempt creates a new persisted provider operation while retaining the old attempt.
Unknown attempts cannot silently become a fresh charge.

Preparation validates the subscription and quote before external work. Customer and
financial calls run outside short writers. Leases fence stale acknowledgements; retries
retain their operation UUID, have finite attempt bounds and stop well before Mollie's
one-hour idempotency cache boundary. Exhausted/uncertain work requires reconciliation.
The library promises no exactly-once external execution.

## Verified notification routing

A callback's configured profile selects the verifier/client, never an account. Its
raw bounded notice is verified/interpreted by that provider and retrieved through the
authenticated API. The verified operation must already be registered locally for the
same profile, customer, exact amount and currency, with a started financial request.
Only that stored order selects the account/subscription. Unknown/conflicting resources
fail; no callback tenant claim or global account fallback is consulted.

A registered primary resource cannot be replaced by an arbitrary matching-metadata
resource. A different authenticated alias (for example Stripe's payment intent versus
checkout session) only triggers retrieval of the known primary resource; only its
current facts are used. Resource bindings have generated uniqueness and foreign-key
constraints. Duplicate/stale nonpaid reads cannot remove a proven settlement.

## Durable subscription effects

Payment truth and subscription application are explicit stages. A verified paid order
uses one stable settlement receipt, so a crash between application and acknowledgement
can be retried without extending benefits twice. A future paid period waits until its
effective boundary while preserving the current period. Explicit purchase terms are
installed atomically with verified settlement; an abandoned unpaid quote does not
pre-emptively change renewal terms. Authenticated full reversals queue a durable REVOKE stage. Its subscription receipt
tombstone fences late activation, including a refund which overtakes first application.
Replaying it after a restart cannot grant the refunded period; reversing an additional
paid attempt cannot revoke the winning agreement. A partial refund preserves benefits.

The first requested billing period is recorded before payment and shown to the customer.
UTC calendar months/years and explicit resumed periods are used, with no implicit
back-billing of missed periods. Pending/refused/unknown provider states never grant
paid benefits. Payment conflicts remain actionable review work, not a fabricated payer
refusal. Complete refund, renewal, reconciliation and UI acceptance is still required
within this topic before it can be called finished.

## Refund requests

An authorized administrator requests a positive refund against the winning paid order,
using a persisted operation UUID. Repeating the same request returns its existing
receipt; changing its order or amount conflicts. Account-serialized reservations include
all pending/unknown refunds and externally observed refunds, so concurrent requests
cannot allocate the same remaining balance. Provider POSTs use the stored operation;
unknown outcomes retain their reserved amount and stop after the finite retry horizon.
Registered remote refund IDs are unique within a profile and cannot be reassigned.

`processRefundOne()` explicitly performs one claimed step. After confirmed provider
refund, a separate durable reconciliation step retrieves total payment truth. It waits
for that total to include all confirmed local receipts before completing. Partial
refunds preserve purchased benefits. A full refund queues the ordinary reversal stage;
crashes between provider confirmation, reconciliation and reversal remain retryable.
Failed provider facts remain review work, never an invented successful refund.

## Renewal scheduling

`scheduleRenewalOne()` selects at most one due persisted billing agreement. It reloads
current subscription state and records a renewal only for the next calendar period,
using the previously verified customer and mandate. Preparation and dispatch recheck
renewal terms and cancellation. A changed quote is never silently charged using stale
terms. Existing pending work and previously recorded cycles prevent parallel charges.

Cancellation expires the agreement at its paid boundary without a renewal charge.
An explicitly scheduled Free offer is a nonfinancial transition. A completely missed
paid period creates review work; the scheduler never accumulates retrospective charges.
The application installs its own scheduler and exposes review work to its operator.
