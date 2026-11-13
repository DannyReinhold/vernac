---
title: Optional SaaS identity foundation
---
# Optional SaaS identity foundation

Status: implemented in **0.6.0-SNAPSHOT**. This is the first reusable identity boundary,
not a complete registration/login system. The [lifecycle design](../development/identity-membership-0.6.0.md)
remains the guide for unfinished workflows and the [release ledger](../development/release-0.6.0.md)
remains the acceptance authority.

## Artifacts and ownership

The SaaS modules live in this repository under `saas/`, use Apache-2.0 and the shared
Vernac version, and publish separate Maven artifacts under `org.vernac`:

- `vernac-saas-identity`: generated domain contracts and Vernac module metadata.
- `vernac-saas-identity-spring-security`: explicit Java principal/actor adapter;
  depends on the identity artifact and Spring Security core.

There is no compiler/general-runtime dependency back to either artifact. The identity
artifact does not require Spring Security for adopting its values. Compiler use in its
tests and the Maven compiler plugin are build-time concerns, not runtime dependencies.
No SaaS starter, repository, filter chain, module auto-configuration or credentials
are installed by adding these dependencies. A normal Vernac application remains normal.

## Nominal domain contracts

The logical module is `vernac.saas.identity`. It explicitly exports UserId,
IdentityStatus and IdentityDirectory under the namespace `org.vernac.saas.identity`.
Current module-contracts-12 tools consume the standard META-INF/vernac/module.json;
no new metadata format is required. Vocabulary accompanies the exported types.

A complete consuming model, compiled against the actual producer JAR in the module test:

```vernac
namespace consumer;

import org.vernac.saas.identity.UserId;
import org.vernac.saas.identity.IdentityStatus;

value Owner(UserId);
port StatusReader {
    IdentityStatus? status(UserId);
}
```

The import does not prefix types with the module name, regenerate producer classes,
activate beans/migrations, create accounts or prove ownership. UserId is an ordinary
nominal generated ID. Its UUID can be persisted/embedded using existing Vernac rules,
and it is not an AccountId or TenantId.

IdentityStatus has PENDING_VERIFICATION, ACTIVE, DISABLED and CLOSED. The enum names
are public vocabulary, not implementations of those lifecycle transitions. The
IdentityDirectory port returns Optional<IdentityStatus> for a UserId. Missing identities
return empty. A lookup failure MUST fail technically rather than look like an absent
or active user. Implementations must read trusted current facts with the transaction
semantics of their actual caller; a public browser user lookup is not supplied.

The directory is a bootstrap metadata read. It MUST NOT invoke an actor-guarded
usecase that recursively resolves the actor. Use a controlled internal read with the
appropriate transaction participation instead. Reentering the same resolver fails
with AUTHORIZATION_CONFIGURATION_ERROR and its per-thread resolution frame is cleared.

An optional [JDBC directory](saas-identity-jdbc.md) now supplies internal global metadata
storage. It exposes neither unrestricted user queries nor registration workflows.

## Technical principal

IdentityPrincipal is a final immutable Java Principal in
`org.vernac.saas.identity.security`. Construct it only after successful trusted
Spring authentication. It holds the UUID of a nominal UserId; it holds no password,
email, enabled snapshot, role, entitlement or tenant claim.

userId() reconstructs the nominal domain ID. getName() returns its stable UUID text
for technical session identity/indexing; that is security-sensitive metadata and
MUST NOT be added to ordinary logs by default. toString() is redacted.

The technical principal supports Java serialization for a trusted session-store
boundary. Generated domain values remain non-Serializable; there is no new domain
serialization contract. Invalid missing subjects are rejected on deserialization.
This is not an invitation to deserialize client input or a claim that Spring Session
JDBC, its schema/filters or complete session lifecycle is already integrated. Session
storage and framework serialization still need the reviewed implementation/security setup.

## Explicit live actor resolution

IdentityActorResolver implements the existing ActorResolver using the existing
SpringSecurityActorResolver. It accepts only authenticated, non-anonymous Spring
contexts whose principal is exactly the supported IdentityPrincipal type. Foreign
principal names and arbitrary authorities do not establish a standard human identity.

Every supported resolution calls IdentityDirectory with the nominal UserId:

| Lookup result | Resolver result |
| --- | --- |
| ACTIVE | VerifiedActor with realm `vernac.saas.identity`, subject UserId.asString(), kind HUMAN |
| Empty or any non-active status | No verified actor |
| Runtime lookup failure | Safe AUTHORIZATION_UNAVAILABLE; no original cause/payload retained |
| Explicit safe technical authorization failure | Preserve its configuration/unavailable code without a provider payload |
| Null instead of Optional | Safe AUTHORIZATION_CONFIGURATION_ERROR |

The resolver does not cache enabled state, interpret ROLE_ADMIN or other authorities,
select a tenant, grant permissions, verify memberships or produce a technical actor.
The caller still owns Spring's authentication/context lifecycle. Parallel callers use
their own SecurityContext; the resolver neither replaces nor clears another context.

A binding example for the existing explicit runtime configuration is:

```java
@Bean
ActorResolver actorResolver(IdentityDirectory identities) {
    return new IdentityActorResolver(identities);
}
```

Import ActorResolver from `org.vernac.runtime.authorization` and IdentityDirectory
from `org.vernac.saas.identity.domain`. This is a wiring fragment, not a complete
runnable SaaS configuration. Bind the real directory, PermissionEvaluator and existing
VernacAuthorizationConfiguration explicitly. Do not register two competing resolvers
or use an always-active directory as a temporary production shortcut.

The generated guard still owns invocation-time checks inside REQUIRED. Missing/disabled
identities cannot enter an authenticated body. Directory infrastructure failure is not
a fabricated bad password or business denial. A live lookup is not an instantaneous
revocation barrier: its visibility depends on the backing transaction and implementation.
The full SaaS module must subsequently recheck membership/roles and manage stale sessions.
If eligibility disappears while a nested invocation frame is still active, the existing
actor pin rejects the changed actor with AUTHORIZATION_CONFIGURATION_ERROR and the
operation must abort. It does not authorize the inner body or erase the outer context.
This boundary is tested; the final UI/revocation-outcome handling must be reviewed as
part of D02 before the full identity/session lifecycle is accepted.

## Tests and completion limits

Post-package tests inspect the actual Maven-built producer JAR and an independent
compiled consumer. Further tests cover exported vocabulary,
no regeneration/activation, supported/foreign/anonymous principals, every status,
current-state changes, concurrent subjects, runtime guard composition, safe failures,
redacted diagnostics and the technical principal roundtrip. The transaction-composition
unit uses Spring's transaction lifecycle, not a database; PostgreSQL identity storage
is still future work.

Release tooling selects the nested reactor modules, requires the optional SaaS artifacts from
0.6.0 onward, and discovers their nested test reports. Plugin-only recovery for older
releases retains the earlier artifact inventory. No artifact is published by this PR.

A01/A02 and all complete SaaS acceptance remain OPEN. Credentials/canonicalization,
registration/verification/provisioning, session integration, memberships/roles and their
mail/UI/migration paths must be implemented and reviewed before claiming a working SaaS.


The [accepted D14 policy](saas-identity-credentials.md) and
[implemented credential primitives](saas-credential-primitives.md) describe the next
boundary. The latter supplies explicit Java helpers, without registration endpoints,
credential persistence or session integration.
