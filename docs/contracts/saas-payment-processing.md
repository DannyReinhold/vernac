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
pre-emptively change renewal terms. Full reversals require a durable subscription receipt
tombstone to fence late activation; they must not merely change a browser session.

The first requested billing period is recorded before payment and shown to the customer.
UTC calendar months/years and explicit resumed periods are used, with no implicit
back-billing of missed periods. Pending/refused/unknown provider states never grant
paid benefits. Payment conflicts remain actionable review work, not a fabricated payer
refusal. Complete refund, renewal, reconciliation and UI acceptance is still required
within this topic before it can be called finished.
