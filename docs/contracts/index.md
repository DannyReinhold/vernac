# Feature contracts

Feature contracts define the observable API and runtime semantics. Development-only
features are explicitly marked; use matching compiler/runtime versions. They form part of the
[Vernac specification](../specification/index.md). That index provides the complete
reading order and authority rules. Use the [language reference](../language-reference.md)
for syntax and the [tutorials](../tutorials/index.md) for guided use.

Use the specification index for the full feature catalog. In this revision:

- [Shared Java implementation rules](implementation-contracts.md): nullable parameters, external target resolution and Java overload identity (0.5.0 development).
- [HTTP inbound adapters](inbound-http.md): request bindings, usecase invocation, response mapping and error defaults.
- [Pagination](pagination.md): runtime APIs and offset/cursor JDBC queries; completed usecase results and HTTP DTO integration.

## Additional implemented feature contracts

- [Aggregate field uniqueness](aggregate-uniqueness.md): aggregate-field requirements, derived PostgreSQL constraints, tenant scope, migrations and safe JDBC conflicts.

- [Versioned modules](modules.md): versioned exports/imports, explicit activation, provider selection, independent migrations and module-owned events/HTTP entrypoints.

- [Ubiquitous language](ubiquitous-language.md): namespaced terms, model association, hover, glossary output and exported-type documentation.

- [Domain diagrams](domain-diagrams.md): resolved namespace graphs and shared Maven/IDE exports.

- [Actor and authorization runtime foundation](usecase-authorization-runtime.md): implemented Java/Spring guard SPI; with the [permission/requires contract](usecase-authorization.md); [module authorization metadata](module-authorization.md) implemented; full SaaS integration still pending.

- [Explicit application access audit](explicit-authorization.md): opt-in startup
  detection of omitted requires clauses on registered local/module usecases.

- [Optional SaaS identity foundation](saas-identity-foundation.md): nominal library and
  explicit live-status Spring actor bridge; full identity workflows remain OPEN.

- [Optional JDBC identity directory](saas-identity-jdbc.md): explicit persistent status lookup and module-owned migration; registration remains OPEN.

- [SaaS credential policy](saas-identity-credentials.md): accepted D14 policy for registration, passwords, one-time actions and browser sessions; delivery remains partial.

- [Credential primitives](saas-credential-primitives.md): implemented optional Java parsing, corpus and Spring password operations; not a complete login system.

- [Bundled password corpus](saas-password-corpus.md): pinned offline dataset, license/provenance, explicit loading and reproducible maintenance.

- [Private pending registration lifetime](saas-identity-registrations.md): charged seven-day pending state, atomic completion and bounded cleanup; login-address ownership remains OPEN.

- [Private keyed identity admission](saas-identity-admission.md): shared source/address/locator budgets, purpose-separated HMAC keys, bounded storage and primary-key cursor cleanup.
- [Bounded credential work](saas-credential-work.md): shared local worker/queue limits around existing Spring password operations; no automatic ingress wiring.
- [Private credential persistence](saas-identity-credentials-jdbc.md): redacted Spring encodings, guarded updates and identity security epochs; authentication/session integration remains OPEN.
- [Private global identity admission gate](saas-identity-ingress.md): independently committed deployment-wide fixed-window budget; public wiring and keyed gates remain OPEN.
- [Private one-time identity challenges](saas-identity-challenges.md): purpose-bound tokens, atomic consumption/replacement and actual-row budget coupling.
- [Private identity storage budgets](saas-identity-capacity.md): transactional fixed-resource capacity foundation; admission/cleanup and public registration remain OPEN.
