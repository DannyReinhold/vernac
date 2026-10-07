# Enum implementation notes

The [enum contract](../contracts/enum-value-objects.md) is implemented in this
change; full Maven verification must be performed with the project's supported JDK.

## Implementation boundaries

- Grammar: constants are contextual names, with no persistence-code arguments.
- AST: EnumConstantNode contains only its source location and name.
- Generation: a public @NullMarked Java enum implementing ValueObject, with ordered
  constants and author-defined methods; no extra state, factory, or toString override.
- Resolution: enum types use the same namespace-aware type identities as IDs and VOs.
- API validation: constants and methods are separate Java name categories. Method
  signatures include resolved types and inherited enum restrictions. Optional String
  is not a valid toString return type because it generates Optional<String>.
- LSP: enum-member tokens for declarations (including lowercase/keyword-like names),
  shared compiler diagnostics, and existing type completion/navigation. Constant
  references inside embedded Java bodies remain outside the current symbol service.

## Persistence and ACL follow-up

PostgresSchemaUtils previously emitted dbValue() accesses. It now rejects automatic
enum flattening with a source-located diagnostic, including recursive VO flattening.
No name()/ordinal() fallback was introduced. RepositoryGenerator's reconstruction
also needs a dedicated enum strategy when persistence is reviewed: treating an enum
as a zero-field VO and emitting of() is invalid.

PortGenerator still assumes of(...) construction for some scalar/VO responses.
Its handling of enums, external codes, and unknown responses must be redesigned
with the general ACL mapping review. No general enum conversion is promised there.
The strict multi-file generation path already rejects non-reviewed port/repository
kinds; the legacy single-source paths are not a complete persistence/ACL solution.

## Verification

Existing enum AST/generator tests are updated to the new contract. The former
persistence-code test now asserts rejection of removed syntax; unrelated tests stay.
EnumValueObjectContractTest adds compiled-Java behavior, unusual constants, real
signature conflicts, legal overloads/overrides, Unicode, namespace-aware VO fields,
nullness behavior, and the explicit persistence boundary.

LSP tests exercise unusual constant highlighting, correction of method conflicts,
and imported enum type navigation/completion without constant/type confusion.
Local targeted checks exercised the actual resolver/validator and enum generator,
compiled generated Java, and invoked it. That check used JDK 17 with preview and a
temporary getFirst-to-get(0) substitution outside the repository, since the local
environment lacks Maven and JDK 21/25. It is not a substitute for the full Maven
suite, regenerated ANTLR parser, or installation testing in IntelliJ.
