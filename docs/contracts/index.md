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

- [Versioned modules](modules.md): versioned exports/imports, explicit activation, provider selection, independent migrations and module-owned events/HTTP entrypoints.

- [Ubiquitous language](ubiquitous-language.md): namespaced terms, model association, hover, glossary output and exported-type documentation.

- [Domain diagrams](domain-diagrams.md): resolved namespace graphs and shared Maven/IDE exports.

- [Actor and authorization runtime foundation](usecase-authorization-runtime.md): implemented Java/Spring guard SPI; with the [permission/requires contract](usecase-authorization.md); [module authorization metadata](module-authorization.md) implemented; full SaaS integration still pending.

- [Explicit application access audit](explicit-authorization.md): opt-in startup
  detection of omitted requires clauses on registered local/module usecases.
