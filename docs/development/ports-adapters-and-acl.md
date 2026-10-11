# Ports and ACL implementation plan

The [port contract](../contracts/ports-adapters-and-acl.md) and its linked DTO,
mapping and REST contracts define observable behavior. The implementation includes
manual adapters, typed DTO/mapping generation, HTTP request/response bindings and
declarative errors. PortDemoRunner/PortDemoTest demonstrate the integration through
real loopback HTTP. This document records architecture and remaining extensions.

## Implementation slices

1. Port resolution and adapter behavior: complete port interfaces, namespace
   resolution, common self access for inline/external code, dependency injection,
   architectural checks and concurrent invocation contexts.
2. DTOs and mappings: records, strict decoding, unknown-property tolerance,
   explicit names/strategies, optional fields, PatchField and nested/collection
   conversions. Validate generated Java, not just generated strings.
3. REST adapter and errors: request/response mappings, real HTTP method handling,
   declarative/Java errors, explicit 404 absence and diagnostic context.
4. Tooling and examples: completion/navigation/Java projection, updated reference
   and tutorials, REST plus a non-HTTP adapter demonstration.

Keep contract, semantic analysis, generation, LSP and IDE views consistent via
shared resolved models. Do not introduce a second string-based type resolver.
Each slice includes relevant tests and documentation rather than deferring all
verification until the end.

## Shared implementation boundaries

- Mapping scopes and generated technical subpackages. DTOs are adapter-local nested
  records in <namespace>.adapter.outbound.dto.<Adapter>Dtos; see the DTO contract.
- Mapping APIs and Java hook signatures are defined in the mapping contract.
- Error-category inventory and typed domain-code access: settled in the HTTP runtime contract.
- Missing error-body operands: three-valued ErrorCondition; only TRUE matches.
  Compiler conditions must use this shared truth model.
- Runtime success/status/body combinations are documented in the HTTP contract;
  the compiler must validate operation-specific absence/void/result declarations.
- Further declarative request-diagnostic/correlation configuration remains an extension.
- Exception wrapping boundaries: user programming defects must not masquerade
  indiscriminately as remote contract errors.

## Required coverage

- Cross-file imports, inferred/explicit names, collisions, unsupported signatures,
  complete implementation of all port methods and deterministic declaration order.
- Inline and implemented-by bodies share the same self contract.
- Concurrent calls cannot leak invocation IDs, dependencies or request details.
- Unknown fields tolerated recursively; missing/null required values rejected;
  wrong JSON types rejected; optional empty/missing/null distinctions documented.
- Naming overrides and collisions, nested strategy independence.
- Every patch state, illegal clear/set(null), required wrapper checks, empty
  collections, encoding/decoding and state-preserving mappings.
- Domain factory validation, enum failures and mapping paths in diagnostics.
- Ordered error rules, unavailable/malformed bodies, explicit optional 404,
  enum-code resolution, domain exception preservation and default fallback.
- Real HTTP integration tests including timeout/invalid-response cases, without
  hidden retries. A non-HTTP example proves that core exceptions are independent
  of HTTP.

## Later work

MapStruct integration; additional naming strategies; recursive PATCH/list edits;
async ports; HTTP/MCP inbound generation; explicit retry/idempotency policy and
coordination with the deferred worker. Revisit dependency/self consistency across
usecases and services as already planned.

## Review checklist

For each slice ask: what user problem does it solve, what is generated, can Java
interoperate naturally, are diagnostics actionable, is IDE support complete, are
examples/tutorials adequate, and do shared compiler structures keep semantics
consistent? Optimize for useful generated Java and minimal predictable boilerplate.
