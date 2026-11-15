---
title: Plan catalog and subscriptions
---
# Plan catalog and subscriptions

Status: domain implementation in progress in the subscriptions topic PR. This contract
is the D07 implementation baseline; persistent application assembly, authorization,
payment settlement integration, renewal scheduling and A08 acceptance are still OPEN.

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
payment idempotency is additionally required in the payment ledger. The application
calculates calendar period boundaries with its explicit billing clock/zone policy;
the domain does not divide months into fixed seconds.

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
