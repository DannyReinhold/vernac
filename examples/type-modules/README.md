# Publish domain values, consume their contracts

Two separate Maven artifacts demonstrate a reusable type module. The `library`
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
this implemented type-module slice and planned functional-module activation/migrations.

Follow [the module lifecycle tutorial](../../docs/tutorials/type-modules.md) for
initial migrations, an exported-value change, application backfills and Flyway
application. It distinguishes the executable type-module workflow from planned
functional activation and module-owned histories.
