---
title: Spring Boot 4 baseline for 0.6.0
---
# Spring Boot 4 baseline for 0.6.0

0.6.0 source builds move to the aligned Spring Boot **4.1.2** BOM/plugin and its
Spring Security **7.1.2** dependency set. Java application/generated output remains
Java 21; the compiler/tooling build remains JDK 25. This is a coordinated platform
change following the [mail crypto proof](../../experiments/identity-mail-crypto/README.md),
not an isolated Security override. Published 0.5.x artifacts are unchanged. Consumers
upgrading to 0.6.0 must align their own Boot parent/BOM, starters and configuration;
Boot 3 application compatibility is not claimed for the 0.6.0 Spring integration.

## Migration scope

- Runtime module initialization and generated binding checks use Boot 4's Flyway
  packages. Event polling orders itself after Boot 4 JDBC/transaction configuration.
- The JDBC starter includes Boot's Flyway starter explicitly. Provided auto-configuration
  dependencies are explicit in runtime-jdbc; adding Flyway alone no longer supplies
  Boot's initializer. Migration SQL and schema snapshots are unchanged.
- Bundled parents/templates and the tenant experiment use the same Boot baseline.
  Web applications select the Web MVC starter. Default-user exclusion and the embedded
  servlet context use the new Boot package names.
- The repository's JUnit API version aligns with Boot's JUnit 6 platform.

## Preserve existing JSON contracts deliberately

Generated DTO codecs, runtime HTTP/JSON infrastructure, compiler metadata and SaaS
adapters use Jackson 3 (`tools.jackson`). The JDBC starter supplies
`spring-boot-jackson`; Boot 4 selects Jackson 3 by default. Bundled applications
explicitly select `spring.http.converters.preferred-json-mapper=jackson`.
Plain MVC tests use `JacksonJsonHttpMessageConverter`.

Custom codecs extend `ValueDeserializer` / `ValueSerializer` and use
`tools.jackson.databind.annotation`. Core annotations such as `JsonInclude` remain
in `com.fasterxml.jackson.annotation`, as required by Jackson 3. Immutable
`JsonMapper` builders replace mapper mutation. Strict DTO field validation,
PATCH/null omission, duplicate/trailing-token rejection and page contracts remain.
Schema snapshots keep their deterministic LF/inline-array representation; this
migration must not rewrite historical snapshots or SQL.

The migration follows the [Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md).
There is no parallel Jackson 2 codec or converter support in 0.6.0.
Mustangproject's unused JSON conversion dependency is excluded from the PDF/XML
adapter. Its fiscal PDF/A and embedded XML generation/validation remain covered
by the rendering and PostgreSQL integration suites; its separate JSON API is not
part of Vernac's adapter contract.

## Evidence and remaining boundaries

Required proofs include actual module startup/migration under restricted PostgreSQL
roles, upgrade/history replay, event delivery, authorization, real generated HTTP/DTO
requests, template profile/CSRF behavior and standalone applications. Installed IntelliJ
and final release acceptance remain separate. This change adds no mail worker, endpoint,
provider binding or automatic key generation. Brevo/Noop and A11 delivery work remain OPEN.

Primary upgrade references:

- [Boot 4 migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
- [Boot 4.1 upgrade notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes).
- [JSON mapper selection](https://docs.spring.io/spring-boot/reference/features/json.html).


## Deprecation boundaries in tests

Use Spring 7's `isUnprocessableContent()` matcher for HTTP 422.
HTTP tests exercise the Jackson 3 converter without removal-warning suppressions.

LSP tests initialize through `workspaceFolders`. The server retains deprecated
`rootUri`/`rootPath` wire fields solely in a documented fallback for older clients.
`WorkspaceInitializationTest` verifies modern-folder precedence and both old formats
through initialization JSON, without deprecated setters in ordinary test setup.
