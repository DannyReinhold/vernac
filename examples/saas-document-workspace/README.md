# Document Workspace

An independent Vernac application: ordinary tenant `Document` aggregates, generated repositories/usecases and explicit SaaS module/provider bindings. Membership roles authorize operations; purchased benefits control capacity and quotas separately.

Use JDK 21+, Python 3 and Docker Compose. For a SNAPSHOT checkout first install matching producers from the repository root:

```sh
mvn -B -ntp -pl saas/billing-spring-mvc,saas/invoicing-pdf,saas/restrictions,saas/payments-mollie,saas/payments-stripe,saas/identity-jdbc,saas/mail-local,saas/mail-brevo -am -DskipTests install
cd examples/saas-document-workspace
python3 run-local.py
```

The launcher persists PostgreSQL data and local keys, generates a loopback HTTPS certificate and separates migration credentials from the restricted runtime role. Open https://localhost:8443/auth/register. Accept the local certificate. In a second terminal, `python3 run-local.py --mail` shows capability-protected simulated messages. Stop the app with Ctrl+C, restart with the same command; `--stop` stops PostgreSQL without deleting data. Keep `.local` private and back it up together with the database. Losing delivery keys can make pending encrypted mail unrecoverable. Delete `target` or run `./mvnw clean package` after source changes.

The local profile explicitly selects persistent simulated payments and captured mail. Free permits 3 documents, 1,000 characters per operation and 2 exports per UTC calendar month; Pro is a simulated EUR 12/month offer with 100 documents, 10,000 characters and 100 monthly exports. Local seller data and TEST invoices are demonstration fixtures. No external mail or payment is sent. Opening billing pages does not create subscriptions: selecting a verified workspace or explicitly starting Free does.

`/billing` provides owner-authorized billing profiles, checkout, order recovery, subscription actions and immutable PDF/ZUGFeRD/XML downloads. Customer numbers are assigned by the application from the verified account, never editable buyer claims. Account history is keyset-paginated. Unresolved financial work blocks user-directed subscription changes; close a proven unsent/failed order or reconcile it first. Cancellation retains benefits through the paid period; it does not promise a refund. Refund decisions belong to the merchant and are not exposed as unrestricted customer actions.

The stateless `/billing/provider-notice` ingress admits through the shared durable gate, bounds bodies/signatures and routes exclusively through the configured profile and registered provider resources. Provider data cannot choose an account. Local callbacks only retrieve the local simulated ledger; real adapters independently verify their provider facts/signatures. Polling remains a recovery path. Configure a public HTTPS origin and explicit real mail/payment credentials before deploying; the loopback local profile is for development only. Match proxy timeouts/body/concurrency limits, protect database/backups, supply persistent managed encryption keys and restrict migration credentials. Real Mollie/Stripe sandbox acceptance is still required separately.

Automated acceptance: package the app, install `browser` dependencies and Chromium, then run `verify.py` with `VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER`, and `VERNAC_PG_TEST_PASSWORD` for a disposable-database-capable PostgreSQL administrator. Tests create restricted runtime roles, use real HTTPS/browser flows and restart the application. They send no real mail/payment.

Remaining release gates and known gaps are tracked in `docs/development/release-0.6.0.md` in the Vernac repository. A successful local demonstration is not release approval.
