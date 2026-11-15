---
title: Explicit shared JDBC browser sessions
---
# JDBC browser sessions

Explicitly import `IdentityJdbcSessions` alongside the
[Identity browser chain](saas-identity-browser-chain.md). The class has no component
stereotype or auto-configuration registration. It uses the Boot-managed Spring Session
JDBC version, standard SessionRepositoryFilter and JdbcIndexedSessionRepository; there
is no custom session protocol, local store fallback or generated Identity schema change.

Supply one explicitly qualified `@SpringSessionDataSource` DataSource and matching
`@SpringSessionTransactionManager` DataSourceTransactionManager (JdbcTransactionManager
is also supported). The manager must reference that same DataSource. The servlet
application must register Spring's `springSessionRepositoryFilter` before
`springSecurityFilterChain`, once each. In a Boot servlet application use its supported
Session filter registration; in a plain servlet application register the matching
DelegatingFilterProxy beans explicitly. Merely importing a repository config does not
prove container filter registration.

The framework table prefix is `identity_sessions.spring_session`; idle expiry is
1,800 seconds. The shared cookie is `__Host-VERNACSESSION`, Secure, HttpOnly, SameSite=Lax,
Path=/ and without Domain. Production HTTPS is required, including local acceptance;
there is no insecure-cookie switch. Fresh Identity epoch checks and twelve-hour absolute
authentication lifetime still apply independently of sliding idle expiry.

## Framework schema ownership

These are framework tables, outside Vernac-generated module schema/history ownership.
Before startup, a separately privileged, reviewed deployment migration must:

1. Create the dedicated `identity_sessions` schema under the migration owner.
2. Apply the exact Boot-managed Spring Session resource
   `org/springframework/session/jdbc/schema-postgresql.sql` with that search path.
   Preserve the reviewed version/resource digest in the deployment's migration history;
   dependency upgrades require review of framework-table changes. Do not silently rerun
   an initial script against existing tables or enable Boot session schema initialization.
3. Grant the runtime role schema USAGE and SELECT/INSERT/UPDATE/DELETE on both tables.
   The runtime role must not own the schema/tables, create schema objects, have table
   TRUNCATE, SUPERUSER or BYPASSRLS. Retain foreign keys/indexes supplied by Spring.

An explicit startup guard checks table/column availability, matching transaction manager,
required CRUD and absence of the listed privileged capabilities. Missing tables or
incomplete/privileged runtime configuration fails startup with a fixed cause-free error.
It executes no DDL and cannot replace deployment migration history or full schema-drift
inspection. The ordinary framework expiration cleanup retains its standard schedule.

Three mandatory PostgreSQL tests apply the actual dependency's schema using a disposable
migration owner and then run under a restricted role. They compose SessionRepositoryFilter
with the configured browser security chain, proving secure cookie attributes, fixation,
CSRF, authentication across two contexts and restart, thirty-minute idle expiry, shared
logout deletion and rejection of missing tables/CREATE/TRUNCATE privileges. Authentication
uses an identified Spring test manager; actual Argon2/JDBC provider behavior has its
separate PostgreSQL proofs. These tests do not claim a real HTTP connector or full
registration/recovery journey.
