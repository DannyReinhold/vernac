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

Generated DTO codecs and runtime HTTP/JSON infrastructure still use Jackson 2. The
JDBC starter supplies `spring-boot-jackson2` alongside Boot's managed dependencies.
Applications exposing generated DTOs must explicitly select
`spring.http.converters.preferred-json-mapper=jackson2`. Bundled web applications and
the web wizard template do so. Plain Spring MVC/standalone setups must select
`MappingJackson2HttpMessageConverter` explicitly. Do not assume Jackson 3 honors the
Jackson 2 custom serializer/deserializer classes. Existing null-omission, strict input
and pagination wire assertions remain required; selecting a converter must not weaken
them or install global DTO-specific modules.

This compatibility path is deprecated upstream. Jackson 3 migration is separate work
before removing it; merely changing the default mapper would silently alter wire
contracts. Other consumer JSON configuration remains application-owned.

## Evidence and remaining boundaries

Required proofs include actual module startup/migration under restricted PostgreSQL
roles, upgrade/history replay, event delivery, authorization, real generated HTTP/DTO
requests, template profile/CSRF behavior and standalone applications. Installed IntelliJ
and final release acceptance remain separate. This change adds no mail worker, endpoint,
provider binding or automatic key generation. Brevo/Noop and A11 delivery work remain OPEN.

Primary upgrade references:

- [Boot 4 migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
- [Boot 4.1 upgrade notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes).
- [Jackson 2 compatibility and mapper selection](https://docs.spring.io/spring-boot/reference/features/json.html).
