# Contributing to Vernac

Vernac is an architecture language for Java applications. Contributions should
keep domain modeling, generated code, diagnostics and the developer experience
consistent. Small, well-explained changes are easier to review.

## Find the right starting point

- [Documentation map](docs/documentation-map.md): user, language and contributor guides.
- [Contributor documentation](docs/development/index.md): compiler and IDE architecture,
  testing, design decisions and lessons learned.
- [Language specification](docs/specification/index.md): the language contract.
- [Feature review checklist](docs/development/feature-review-checklist.md): questions
  to answer before extending the language.
- [Maintainer release guide](docs/maintainers/releasing.md): release builds,
  publication, acceptance checks and credentials handling.
- [Website privacy maintenance](docs/maintainers/website-privacy.md): publication
  responsibilities for vernac.org.

## Report a problem or propose a change

Use the issue templates. Describe the expected outcome, actual behavior and a
minimal reproducer. For IDE problems include the exact IntelliJ build, plugin
version, JDK version and whether Java generation has already run. Remove credentials,
customer data and proprietary source from reports and diagnostic files.

Discuss substantial language or architecture changes before implementing them.
Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
All participation follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Build and verify

Use **JDK 25** and **Maven 3.9+** from the repository root. Generated application
code targets Java 21.

```shell
mvn clean verify
```

For local example builds that consume current artifacts, use `mvn clean install`
first. Examples under `examples/` are standalone projects; follow their READMEs.
PostgreSQL integration tests require an explicitly configured disposable server.
Consult the [AI tools reference](docs/ai-tools-reference.md) for test environment
variables and build/schema commands. A skipped database test is not a passing test.
Never point destructive test tooling at an application or production database.

Run checks appropriate to your change and report exactly what ran, failed or was
skipped. IDE changes also need acceptance with the packaged plugin in IntelliJ;
headless PSI tests do not establish that completion popups or debugger UI work.

## Submit a pull request

Explain the problem, resulting behavior and validation. Add meaningful regression
coverage for behavior changes. Update documentation alongside code: specification
and contracts, tutorials/examples, AI references and plugin What's New where relevant.
Keep generated artifacts, local configuration, experimental migrations and secrets
out of the change. Review migration candidates before applying or committing them;
their generation does not establish deployment safety.

AI-assisted contributions are welcome. The contributor remains responsible for
understanding the change, verifying its behavior and checking its sources and
licensing. Do not submit confidential data to external tools.

Contributions are made under the repository's [Apache-2.0 license](LICENSE).
