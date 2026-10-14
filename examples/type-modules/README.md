# Publish domain values, consume their contracts

Three separate Maven artifacts demonstrate a reusable type module. The `library`
project publishes IDs, values, an enum and a named list. The `application` project
imports them through an ordinary dependency, includes them in an aggregate and
uses the library's actual compiled factories. No dependency classes are regenerated.
This example is a separate reactor, not part of the Vernac root build.

Install the current Vernac tools first, from the repository root (IntelliJ Maven):

```text
mvn clean install
mvn -f examples/type-modules/pom.xml clean install
```

`TypeModuleDemoTest` prints the constructed aggregate and verifies its external
values. Inspect `library/target/classes/META-INF/vernac/module.json`: it contains
only explicit exports and their contract fingerprints. InternalNote remains a
compiled implementation type but cannot be imported into another Vernac model.
The sources JAR includes `vernac/example/shared/values.vernac` for IDE navigation.

For a separate consumer build, after installing the example reactor:

```text
mvn -f examples/type-modules/application/pom.xml clean verify
mvn -f examples/type-modules/application/pom.xml vernac:schema
```

The second command writes the application's owned schema candidate. Money is
flattened in Account's table, including the external Amount value. The type library
owns no database tables and needs no activation. No database is required for this
example. Review any generated SQL before applying it.

Open `library/vernac-module.vmodule` to edit exports. New → Vernac Module Definition
creates this project-level file beside a POM; it is not a namespace source file.
Build the consumer to refresh its resolved dependency index. External type navigation
uses a matching locally available sources JAR when possible, otherwise a clearly
marked read-only contract view under target. No artifact versions are independently
selected by the language server.

See [the module contract](../../docs/contracts/modules.md) for the distinction between
type-only imports and explicit functional-module activation/migrations.

Follow [the module lifecycle tutorial](../../docs/tutorials/type-modules.md) for
initial migrations, an exported-value change, application backfills and Flyway
application. It covers type-only imports, functional activation and module-owned
histories. For the executable cross-module lifecycle and HTTP API, see
[the persistent module example](../persistent-modules/README.md).

## A reusable port contract

The library also exports PaymentProvider with `ExternalRef charge(Money)`. The
application imports that interface and defines DevelopmentPaymentProvider using
ordinary adapter behavior. The test invokes it directly through PaymentProvider;
it is a local fixture and makes no payment. No Spring activation or automatic
provider selection is implied. This shows the prerequisite for a separate provider
artifact; the separate providers artifact now demonstrates implementation exports and selection.

After this compiler update, rebuild producer artifacts: the metadata contract version
is `module-contracts-9`. Existing development metadata is intentionally not accepted.

## Run the Spring profile demonstration

The `providers` project exports NoopPaymentProvider and ConfiguredTestPaymentProvider.
Both are local fixtures and make no payment. The application definition selects the
provider from `billing.payment-provider`, supplied by application-dev.yaml or
application-prod.yaml. Generated Boot auto-configuration performs registration;
there is no broad component scan of the provider module.

After installing the example reactor, from the Vernac repository root:

```text
mvn -f examples/type-modules/application/pom.xml spring-boot:run
```

The default dev profile requires no fixture API key. In PowerShell, test the prod
profile using a local fixture value (never a real provider credential):

```powershell
$env:PAYMENT_FIXTURE_API_KEY = 'local-fixture-only'
mvn -f examples/type-modules/application/pom.xml spring-boot:run "-Dspring-boot.run.profiles=prod"
```

For IntelliJ, use the unquoted Maven argument and set the environment variable in
that run configuration. ProfileBindingTest exercises both selections automatically.
Functional-module activation and independent module migrations are supported in
[the persistent module example](../persistent-modules/README.md). Importing the shared
contracts and selecting a fixture here does not require activate.


## Vocabulary travels with exported types

The library defines a term named Money in the same namespace as its Money value object.
After building the updated producer and consumer, hover over Money in the application's
Vernac source: its definition, example and note come from module metadata, even without
a sources JAR. Inspect target/vernac-glossary.md or .json in either project for build-generated
vocabulary. Module metadata is module-contracts-9; rebuild development artifacts together.
