---
title: Spring Boot 4 baseline for 0.5.4
---
# Spring Boot 4 baseline for 0.5.4

0.5.4 source builds move to the aligned Spring Boot **4.1.2** BOM/plugin and its
Spring Security **7.1.2** dependency set. Java application/generated output remains
Java 21; the compiler/tooling build remains JDK 25. Published releases through 0.5.3 retain their platform baseline. Consumers
upgrading to 0.5.4 must align their own Boot parent/BOM, starters and configuration;
Boot 3 application compatibility is not claimed for the 0.5.4 Spring integration.

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

Generated DTO codecs, runtime HTTP/JSON infrastructure and compiler metadata use Jackson 3 (`tools.jackson`). The JDBC starter supplies
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
There is no parallel Jackson 2 codec or converter support in 0.5.4.
