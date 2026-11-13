# Identity mail crypto compatibility proof

Isolated prerequisite for the [D10 identity-delivery design](../../docs/development/identity-delivery-0.6.0.md).
This is a test-only experiment, not a published module or a production key/envelope API.
It imports the complete Spring Boot 4.1.2 dependency BOM; no isolated Security override
is added to Vernac's runtime. The [coordinated migration](../../docs/development/spring-boot-4-baseline.md)
now aligns 0.6.0 source builds; this isolated proof remains independently executable. The candidate resolves Spring Security
Crypto 7.1.2 and JUnit 6.0.3. Generated/application Java remains Java 21; CI exercises
JDK 21 and the repository's JDK 25 build baseline.

```shell
mvn -B -ntp -f experiments/identity-mail-crypto/pom.xml clean verify
```

No database, API keys, SMTP server, Brevo account or network delivery is used. Maven
needs access to its dependency repositories. All key and payload material is synthetic.

The five required cases establish library API compatibility for AES-256 raw-key use,
reconstruction with an externally retained key, randomized repeated encryption,
IV/body/tag tamper and truncation rejection, old/new read keys during rotation and
invalid key rejection. They do not implement a key ring or prove deployment configuration,
key-ID stability, usage/nonce limits, envelope context binding, secret-safe diagnostics,
provider behavior, resource budgets or a Spring application upgrade. Repeated ciphertext
inequality is a smoke test, not a statistical uniqueness guarantee. Library exceptions
are deliberately not treated as a safe public exception boundary.

Next: review the Boot 4 migration impact and align the reactor/starters/generated
bindings/templates/examples as one platform change. Its Flyway/JDBC auto-configuration
package moves and transitive dependencies need real application and PostgreSQL proofs.
Do not put Crypto 7.1.2 onto the existing Security 6.4.2 classpath as a shortcut. Only
then implement the private bounded key/envelope adapter and durable ledger.

Provider selection remains Brevo or the explicitly selected local Noop/test provider
through the shared port. This experiment changes neither provider selection nor A11
release acceptance and writes no persistent secrets.

Primary references checked 2026-11-13:

- [Spring Boot 4.1.2 release](https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now/).
- [Spring Boot 4.1 upgrade notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes).
- [Spring AES advisory and migration](https://spring.io/security/cve-2026-47842/).
