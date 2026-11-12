# Vernac SaaS modules

Optional, free and open-source components under the repository's **Apache-2.0** license.
They share Vernac's 0.6.0-SNAPSHOT version and release pipeline. These are real separate
Maven artifacts, not extra compiler keywords or dependencies of the general runtime.

| Implemented artifact | Current scope |
| --- | --- |
| `org.vernac:vernac-saas-identity` | Nominal UserId, identity status vocabulary and trusted IdentityDirectory port; published Vernac module contracts |
| `org.vernac:vernac-saas-identity-spring-security` | Explicit principal/live-status ActorResolver and credential parsing/corpus/real encoder primitives |
| `org.vernac:vernac-saas-identity-jdbc` | Explicit optional persistent status directory; internal repositories, private storage-budget foundation and separate module migrations |

The module identity is `vernac.saas.identity`. Import exported definitions under
`org.vernac.saas.identity`, not the module identifier. Type-only use needs no activation.
The security adapter has no automatic bean or filter registration.

Build/test these components from the repository root with JDK 25:

```shell
mvn -pl saas/identity-jdbc -am verify
```

The prerequisite Maven plugin tests can include PostgreSQL cases. Configure the normal
disposable-database test environment when validating the full reactor; skipped database
cases do not count as passed. CI separately requires the relevant database proofs.

Read the [implemented identity foundation contract](../docs/contracts/saas-identity-foundation.md)
the [accepted credential policy](../docs/contracts/saas-identity-credentials.md)
and the [larger lifecycle proposal](../docs/development/identity-membership-0.6.0.md).

## Deliberately unfinished

The optional [credential primitives](../docs/contracts/saas-credential-primitives.md)
provide explicit Java validation/encoding, not automatic login. An explicitly selected
[bundled offline corpus](../docs/contracts/saas-password-corpus.md) now supplies a pinned
real dataset with license and provenance.
The optional [JDBC directory](../docs/contracts/saas-identity-jdbc.md) persists internal eligibility. No persisted password credentials, registration/verification/recovery,
login/session filter chain, account/membership/role implementation or SaaS starter is
provided yet. The directory supplies internal state, not the missing trusted workflows.
Do not implement a directory that returns ACTIVE for everyone or treat an actor as a
membership/permission grant. Later modules must supply the reviewed real workflows.

Accounts/access, dynamic limits/quotas, subscriptions, payment/mail providers and local
simulators, invoicing, reusable UI and the separate SaaS example/Wizard remain required
0.6.0 delivery work. The simple non-SaaS example/starter remains separate. Nothing in
this directory makes the whole-SaaS release acceptance complete.


The [private capacity ledger](../docs/contracts/saas-identity-capacity.md) is the first
persistent budget foundation. It does not yet enforce capacity against actual registration,
challenge or mail rows; those lifecycle/admission/cleanup proofs remain required.
