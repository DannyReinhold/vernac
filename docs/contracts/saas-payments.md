---
title: Shared payment providers and verified resource routing
---
# Shared payment providers and verified resource routing

Status: provider implementation in progress in the payments topic. Both Mollie and
Stripe were explicitly selected for 0.6.0, alongside a credential-free persistent
local provider. A09/A17/A23 remain OPEN until durable application checkout/routing,
reconciliation/refunds/renewal, UI and actual sandbox acceptance are evidenced.

PaymentProvider is an ordinary exported Vernac port. No provider-specific compiler
keyword or paid-entitlement bypass is introduced. Typed requests contain an opaque
operation identity, provider customer/mandate, exact payable EUR minor units and
server-owned callback/return URLs. They carry no AccountId or claimed tenant authority.
Customer/payment/refund resources are separately bound by the application's durable
registry. Every mutating provider stage needs its own persisted operation UUID.

## Explicit local composition

```vernac-application
application consumer.payments {
    tenancy explicit;
    authorization explicit;
    activate vernac.saas.subscriptions;
    activate vernac.saas.payments.local;
    bind org.vernac.saas.payments.PaymentProvider
        to org.vernac.saas.payments.local.LocalPaymentProvider;
}
```

The explicit local profile must set vernac.payments.local.allowed=true. Default false
rejects accidental simulated payments; selecting a real provider never falls back.
The local provider owns generated customer/payment/refund tables and normal migrations.
Its default inspect result is PAID; configurable finite scenarios cover pending,
refusal/cancellation/expiry, unavailable reads and a paid outcome with a lost response.
Customers receive a usable local mandate only after their first successful payment.
Resources, scenario cursors, idempotency and refunds persist across application restart.
The application must still run its ordinary notification/inspection/result workflow.
No public simulator control endpoint or arbitrary successful-payment callback exists.

## External provider boundary

Select MolliePaymentProvider or StripePaymentProvider through ordinary application
bindings. Lazy configuration requires only the selected provider's API credentials;
unselected providers make no startup call. Stripe additionally requires its endpoint
webhook signing secret. Configure that endpoint for the supported event types in Stripe;
a checkout's return URL is not a webhook registration or payment proof.

Mollie classic Payments API callbacks contain an untrusted resource ID. Read it only
as a hint, retrieve the full payment through the configured authenticated API and then
match its operation/customer/amount/currency/mode to the persisted local request.
Stripe verifies the exact raw UTF-8 notification with the official pinned stripe-java
SDK, then retrieves the resource through the authenticated API as well. Duplicate,
late or reordered notifications never authorize a local transition by themselves.
A signed event's embedded state is not used as the current settlement authority.

Stripe checkout uses a first on-session payment and off_session future-use setup.
Mollie uses sequenceType=first. Renewals use the registered customer/mandate through
explicit off-session charge calls; there is no second independent provider-owned
subscription/calendar. A missing mandate remains a recoverable renewal prerequisite,
never permission to invent a charge method. Refunds have distinct operation IDs.

The shared transport has fixed production API origins, no redirects, finite request/
response sizes, 15-second full-call timeout and four concurrent calls per instance.
It disables connection retries and 503/zero automatic retry. POST timeouts/unavailable
responses can mean OUTCOME_UNKNOWN; never treat them as proof that no charge occurred.
No API keys, provider body or cause-chain payload is returned in safe failures.
A loopback-only construction seam exists for protocol tests, not application properties.

Idempotency headers are not a durable billing ledger. Mollie's documented cache window
is one hour; uncertain requests outside that window require provider reconciliation,
not blind resubmission. Local records must retain the original provider profile and
operation independently of that cache. Only a verified registered resource may select
its local subscription/account and subsequent trusted tenant scope; no global fallback
or caller-supplied tenant is permitted. Early notifications may bind only to an existing
local operation after all configured provider/request facts match.

## Evidence and scope

Local protocol tests verify requests, signatures/tampering/expiry, exact money/mode
checks, no automatic retry, finite bodies and safe failures. They send no real external
payment. Persistent local-provider tests exercise the actual packaged module and
restricted PostgreSQL roles. These are not real Mollie/Stripe sandbox attestations.
The complete application ledger/routing/worker/consumer integration remains required
within this same topic. Language/LSP syntax is unchanged; do not advertise complete
billing from these provider primitives.

Primary API references checked 15 November 2026:

- https://docs.mollie.com/reference/create-payment
- https://docs.mollie.com/reference/payments-api-webhooks
- https://docs.mollie.com/reference/api-idempotency
- https://docs.mollie.com/reference/create-refund
- https://docs.stripe.com/api/checkout/sessions/create
- https://docs.stripe.com/events/manage-webhook-endpoints
- https://docs.stripe.com/api/payment_intents/create
- https://docs.stripe.com/api/refunds/create
