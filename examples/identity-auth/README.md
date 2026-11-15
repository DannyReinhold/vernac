# Identity/Auth sample

This standalone Spring Boot application composes the ordinary `.vapplication` module activation,
strict product role catalog, generated permission-guarded workspace usecase, JDBC identity
metadata, bounded password/admission processing, encrypted durable mail jobs and shared
JDBC browser sessions. It has no payment or subscription features. The workspace
operation is deliberately read-only and has no resource-specific object policy.

## Automated disposable acceptance

Install matching compiler/runtime, mail and identity artifacts first (the repository CI
does this). Then:

```shell
mvn -B -ntp -f examples/identity-auth/pom.xml clean package
cd examples/identity-auth/browser
npm install --ignore-scripts --no-audit --no-fund
npx playwright install --with-deps chromium
cd ../../..
python3 examples/identity-auth/verify.py
```

Set VERNAC_PG_TEST_URL, VERNAC_PG_TEST_USER and VERNAC_PG_TEST_PASSWORD for a **disposable**
PostgreSQL administrator; acceptance creates and removes one random database and restricted
runtime role. It uses psql, keytool, Java, Python 3 and Node. It starts real HTTPS on
loopback port 8443 with an ephemeral certificate, keys and private preview capability.
The local provider sends nothing externally. Chromium executes real fragment scrubbing,
registration/verification, login, CSRF/cookie policy, account selection, invitations and
password recovery/change. Application logs must not contain seeded credentials, delivery
bearers, addresses or keys. Private temporary files are removed afterward.

## Interactive local setup

Use a disposable PostgreSQL database and two distinct principals. The migration principal
owns the schemas and may execute module/application migrations. The runtime principal must
be NOSUPERUSER/NOBYPASSRLS, have USAGE plus SELECT/INSERT/UPDATE/DELETE in
`org.vernac.saas.identity.persistence` and `identity_sessions`, and no schema CREATE or
table TRUNCATE rights. Establish default table privileges before initial migrations.
The generated module coordinator owns the identity migration stream; the application's
Flyway stream owns only the reviewed Spring Session schema.

Configure SPRING_DATASOURCE_URL/USERNAME/PASSWORD and separate
SPRING_FLYWAY_URL/USER/PASSWORD. Supply a PKCS12 certificate through
SERVER_SSL_KEY_STORE/PASSWORD, IDENTITY_ADMISSION_KEY_LABEL and IDENTITY_DELIVERY_KEY_LABEL
plus their independent 32-byte base64 IDENTITY_ADMISSION_KEY and IDENTITY_DELIVERY_KEY.
There are no default keys or credentials. For this disposable demo,
SPRING_PROFILES_ACTIVE=local also requires a separate random 32-byte base64url
IDENTITY_PREVIEW_CAPABILITY. The server binds 127.0.0.1 and defaults to 8443.
Open https://localhost:8443/identity/login and explicitly trust your local certificate.

GET /__local/mail requires HTTPS, the loopback transport peer and Authorization:
Bearer followed by that capability. It is a bounded, ten-minute in-memory preview for
the local operator, with no-store responses. It has no unauthenticated browser inbox,
query-token access, CORS or mutating API. Keep the capability out of URLs, shared history
and logs. Restart discards previews; the durable outbox retains its defined lifecycle.

## Deployment boundary

The local profile is for one private development operator. For an actual deployment,
remove that profile, choose the normal Brevo provider with
vernac.mail.provider=org.vernac.saas.mail.brevo.BrevoMailProvider and its documented API key
and sender configuration, set identity.origin to the exact public HTTPS origin and keep
identity.allow-loopback-simulation=false. No preview chain/controller is registered.
Review connector/proxy limits, trust and certificate handling before changing the loopback
binding; this example ignores forwarding headers. All application instances must share
the exact admission policy/key, delivery keyring, clock discipline, role catalog and
PostgreSQL session state. The minimal sample exposes only the active delivery key;
use the IdentityJourneys keyring API for a reviewed multi-key rotation deployment.
See [the operational contract](../../docs/contracts/saas-identity-operations.md).
