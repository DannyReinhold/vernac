# Document Workspace

An independent Vernac application: ordinary tenant `Document` aggregates, generated repositories/usecases and explicit SaaS module/provider bindings. Membership roles authorize operations; purchased benefits control capacity and quotas separately.

Use JDK 21+ and Docker Compose. For a SNAPSHOT checkout first install matching producers from the repository root:

```sh
mvn -B -ntp -pl saas/local-development,saas/billing-spring-mvc,saas/invoicing-pdf,saas/restrictions,saas/payments-mollie,saas/payments-stripe,saas/identity-jdbc,saas/mail-local,saas/mail-brevo -am -DskipTests install
cd examples/saas-document-workspace
./mvnw spring-boot:run
```

Local startup persists PostgreSQL data and local keys, uses Spring Boot Docker Compose for local services, generates a loopback HTTPS certificate and separates migration credentials from the restricted runtime role. Open https://localhost:8443/auth/register. Accept the local certificate. In a second terminal, `./mvnw exec:java -Dexec.args=mail` shows capability-protected simulated messages. Stop the app with Ctrl+C, restart with the same command; `docker compose --env-file .local/compose.env stop` stops PostgreSQL without deleting data. Keep `.local` private and back it up together with the database. Losing delivery keys can make pending encrypted mail unrecoverable. Start `DemoApplication` directly in IntelliJ or rerun the Maven command after source changes. Application configuration is YAML; production explicitly selects another profile and supplies managed credentials. Python is used only by optional internal acceptance tooling.

The local profile explicitly selects persistent simulated payments and captured mail. Free permits 3 documents, 1,000 characters per operation and 2 exports per UTC calendar month; Pro is a simulated EUR 12/month offer with 100 documents, 10,000 characters and 100 monthly exports. Local seller data and TEST invoices are demonstration fixtures. No external mail or payment is sent. Opening billing pages does not create subscriptions: selecting a verified workspace or explicitly starting Free does.

`/billing` provides owner-authorized billing profiles, checkout, order recovery, subscription actions and immutable PDF/ZUGFeRD/XML downloads. Customer numbers are assigned by the application from the verified account, never editable buyer claims. Account history is keyset-paginated. Unresolved financial work blocks user-directed subscription changes; close a proven unsent/failed order or reconcile it first. Cancellation retains benefits through the paid period; it does not promise a refund. Refund decisions belong to the merchant and are not exposed as unrestricted customer actions. A separate merchant capability authorizes explicit refund requests; customer ownership alone never does.

The stateless `/billing/provider-notice` ingress admits through the shared durable gate, bounds bodies/signatures and routes exclusively through the configured profile and registered provider resources. Provider data cannot choose an account. Local callbacks only retrieve the local simulated ledger; real adapters independently verify their provider facts/signatures. Polling remains a recovery path. Configure a public HTTPS origin and explicit real mail/payment credentials before deploying; the loopback local profile is for development only. Match proxy timeouts/body/concurrency limits, protect database/backups, supply persistent managed encryption keys and restrict migration credentials. Real Mollie/Stripe sandbox acceptance is still required separately.

Automated acceptance: package the app, install `browser` dependencies and Chromium, then run `verify.py` with `VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER`, and `VERNAC_PG_TEST_PASSWORD` for a disposable-database-capable PostgreSQL administrator. The 29 independently named scenarios each create a fresh database and restricted runtime role, use real HTTPS/browser flows, control time and selected external failures, and restart the application where required. Missing, skipped or failed scenarios fail the evidence gate. See the [coverage matrix](../../docs/saas/testabdeckung.md). They send no real mail/payment.

Remaining release gates and known gaps are tracked in `docs/development/release-0.6.0.md` in the Vernac repository. A successful local demonstration is not release approval.


## Operator actions and closure

The local launcher retains an independent merchant capability alongside its other private
local settings. Inspect bounded worker-stage counters with `./mvnw exec:java -Dexec.args=status`.
For an expressly authorized operator refund use
`./mvnw exec:java -Dexec.args="refund ACCOUNT_ID PAYMENT_ORDER_ID CENTS STABLE_OPERATION_UUID"`.
Keep the same UUID for retries of the same request. These commands target only the local
application; its local profile uses simulated payments. Production operator endpoints are
disabled unless `billing.operator.enabled=true` and a separate managed
`billing.operator.capability` are explicitly configured. Apply operator network controls.

Account closure refuses unresolved payment/refund work, enabled paid renewals and an
unexpired paid period. First cancel renewal and wait for the period boundary, or handle an
explicit merchant refund case. The payment writer rechecks active account state under a
shared lock, and a closed account loses tenant product access. Original invoices and
corrections remain available through retained current owner authorization. Identity
closure still protects the last active owner of other active accounts.

A failed renewal or invoice/profile stage no longer stops unrelated processing. The
operator counters have fixed stage labels and contain no customer/token payloads; they
are local liveness counters, not a persistent queue count or proof of delivery.

The [illustrated SaaS guide](../../docs/saas/index.md) covers all use cases, customer
journeys, developer/operator decisions and recovery. Real provider and comprehensive
acceptance tests are intentionally left for the maintainer's later verification.
