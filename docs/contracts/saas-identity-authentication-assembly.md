---
title: Explicit public JDBC authentication assembly
---
# JDBC authentication assembly

`IdentityJdbcAuthentication.initializeDefaultPolicy` is an explicit Java assembly entry
point for the existing private JDBC provider and fresh session metadata lookup. It returns
only a standard Spring AuthenticationManager and IdentitySessionTrust. It exports no
Identity/credential/admission repository, registration bypass or tenant selection API.

Call it once during controlled application startup after the generated Identity module
migrations, outside all actor/tenant/transaction contexts. Supply the canonical writer's
NamedParameterJdbcTemplate, matching DataSourceTransactionManager/JdbcTransactionManager,
trusted server Clock, an explicit stable admission-key label and a deployment-supplied
256-bit HmacSHA256 key, plus one application-scoped BoundedCredentialPasswords worker.
Keys are never generated or fetched implicitly. The caller owns the password worker's
configuration and shutdown lifecycle.

This deliberately named default-policy entry point checks/initializes the existing
published capacity defaults, 300/minute global ingress and fixed keyed admission rules.
It commits initialization independently in the existing lock order. Restart retains
usage; existing configuration/key drift fails closed rather than widening/resetting it.
A failed bootstrap can leave already committed fixed initialization records, whose
idempotent retry and charges remain owned by the existing private helpers. This API does
not override a deployment with non-default policy or provide a hot policy-change API.

Pass its authenticationManager/sessionTrust to the opt-in browser-chain builder. Configure
Spring Session separately using its explicit DataSource/manager qualifiers. No generated
application/profile provider is silently selected by constructing this assembly. The
ordinary application configuration must explicitly supply these beans and transport,
request-line/header/time/concurrency limits. The browser chain also requires its [pre-session ingress gate](saas-identity-request-admission.md);
schedule the bounded admission-maintenance entry point explicitly. Mail-worker and
remaining public-journey assembly remain open.

Two mandatory real PostgreSQL tests exercise the public entry point with actual bounded
Spring password work, canonical login ownership, persisted admission across restart,
fresh epoch revocation, startup transaction rejection and cause-free key-drift failure.
They complement the separate framework-session/filter tests and do not claim a complete
registration/recovery application or the remaining operational acceptance gate.
