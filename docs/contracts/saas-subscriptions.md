---
title: Plan catalog and subscriptions
---
# Plan catalog and subscriptions

Status: domain and persistent writer implemented in the subscriptions topic PR. This
contract is the D07 implementation baseline; complete application authorization/UI,
payment settlement integration, renewal scheduling and full A08 acceptance are still OPEN.

## Developer model

The optional `vernac-saas-subscriptions` module uses ordinary Vernac values and one
`Subscription` aggregate. No new language keyword, annotation processor or provider
configuration language is introduced. Import its nominal `SubscriptionId`, `OfferTerms`
and other exports through the usual module mechanism. The Java helpers `PlanCatalog`
and `Benefits` construct explicit immutable configuration and canonical snapshots.
Core Vernac never depends on this module. Identity supplies only the nominal AccountId.

An account owns a subscription; a human owns membership rights. Purchased benefits
are named allowances, **not permissions**. A missing benefit means zero; -1 means
explicit unlimited; zero is a valid disabled allocation. The restrictions module must
combine these facts with independently authorized product operations and usage.

An offer contains a plan key, revision, exact price in euro minor units, monthly/yearly
interval and a complete immutable benefit snapshot. The initial currency vocabulary
is EUR; this is commercial pricing, not an invoice tax calculation. At most 128 offers
and 64 benefits per snapshot keep configuration finite. Keys/revisions have bounded
grammars. Duplicate IDs are errors even for equal offers. Exactly one explicit initial
free offer is selected; it must cost zero. There is no inferred cheapest/free fallback.

## Customer contracts survive deployment

Purchases copy complete terms into the subscription. A new catalog revision never
changes existing customers. A revision still present in configuration must match its
stored snapshot exactly. Withdrawn historical offers remain readable but cannot be
selected for a new purchase. Startup/migration must run compatibility checks for retained
terms; do not derive current customer rights by looking up a mutable plan key.

Initial paid activation requires a settlement for the exact expected terms/window.
Only the trusted payment workflow may call it after provider verification and resource
routing; submitting an OfferTerms or AccountId from an HTTP request is no evidence of
payment or ownership. Free activation is a separate zero-price transition.

The first policy deliberately avoids implicit prorating: requested offer changes take
effect at the next settled period boundary. Until then the previous terms remain active.
Cancellation clears the scheduled change and stops renewal at period end. Resumption
is possible only while the current agreement is active and unexpired. A paid period is
half-open [start,end); expiry removes benefits even if no scheduler has run. Nonpayment
suspends; cancellation ends. There is no automatic grace interval, free downgrade,
data deletion or privilege escalation. Above-limit existing product data is retained;
restrictions reject further allocations, while deletion/reduction remains possible.

Renewal must start exactly at the previous end, use the scheduled/current terms and
advance the end. Duplicate/old/mismatched settlements cannot extend twice. External
payment idempotency is additionally required in the payment ledger. BillingCalendar uses UTC calendar months/years, clamping month-end to the last valid
day. Each renewal starts at the previous end. It does not divide months into fixed
seconds. Early verified renewal confirmation returns NOT_DUE without changing the
current period or benefits; the payment workflow must retain and retry that durable
settlement at its effective boundary. No callback/provider call is retried implicitly.

## Boundary and verification

Generated domain transitions do not start transactions, authenticate account admins,
prove external payments, install endpoints or run a billing scheduler. Their application
writer must serialize one current agreement per account and atomically record transitions
with durable follow-up work. Retrying an uncertain provider call is not a domain retry.
These remaining obligations must be implemented in this same topic before A08 is closed.

SubscriptionDomainTest exercises free/paid separation, window boundaries, retained terms,
scheduled changes, cancellation, nonpayment/recovery, replay, canonical bounded snapshots
and invalid catalogs. Public docs/AI references/Wizard must not advertise a complete
billing system from this domain-only slice. There is no new syntax to add to parser/LSP.

## Explicit module composition

```vernac-application
application consumer.subscriptions {
    tenancy explicit;
    authorization explicit;
    activate vernac.saas.subscriptions;
}
```

The module owns the generated global subscription and settlement tables. AccountId
is immutable and unique on Subscription; settled operations are unique and refer to
an existing subscription. Imported identity/account tables are not owned or migrated
by subscriptions. Runtime credentials require CRUD only, without schema CREATE,
superuser or bypass-RLS privileges; migration uses the separate standard module owner.

SubscriptionLedger is an explicitly constructed internal writer. Applications must
perform current account authorization before administration and verified payment
routing before confirm; do not bind it directly to public HTTP request data. It
rejects inherited business actor/tenant/transaction scopes for writes. Its read-only
benefit facts may join the ordinary authorized product transaction and never allocate
or provision anything. Account-scoped transaction
locks serialize creation and mutations, including independent instances. Generated
repositories preserve normal version, hydration and timestamp semantics. Settlement
receipt and subscription changes share one physical transaction. Reusing an operation
key with different terms/account/period fails; replay cannot renew a second time.
These facts are not a replacement for provider checkout idempotency and reconciliation.

## Evidence checkpoint

Source 171533112e3d8ce004a5a26c5aef0ecd604e773c passed the dedicated CI job with
8 domain and 4 actual restricted-role PostgreSQL tests, packaged module activation,
restart/concurrency/rollback and live migration replay/schema-check. Subsequent changes
add historical receipt compatibility, a product-transaction read path, explicit reopen
and early-renewal protection; their exact-head checks must pass before review completion.
No real payment was requested and no invoice was issued. This topic remains a draft
until the remaining integration/acceptance obligations are met.


## Reversal and resumption

revokeVerifiedSettlement is an internal full-reversal operation for an authoritative
payment/refund workflow. It writes a durable REVOKED receipt even when activation has
not yet occurred. Confirmation and reversal serialize on the same account lock: a
late callback can never activate a reversed payment. Repeated reversal is idempotent;
conflicting receipt terms/account/period fail. Reversing an older period does not close
a newer paid agreement. The status migration backfills old receipts as SETTLED because
the preceding implementation could create receipts only after successful settlement;
there was no previous reversal state. Review this additive migration before deployment.

A verified explicit new payment may resume a suspended agreement with a new period
starting after the old end. Missed periods are not charged implicitly. The payment
application must distinguish an explicit resumed purchase from an automatic renewal;
automatic catch-up charges after a long outage are not authorized by this domain API.
