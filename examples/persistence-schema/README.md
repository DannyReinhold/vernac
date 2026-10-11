# PostgreSQL schema and migration candidates

This is a **schema-tooling example**, not a runnable persistence application.
It introduces a simple Tour → Stop → Parcel containment chain. For a runnable
application with shared entities, queries and events, see
[the persistence demo](../persistence-demo/README.md).

Install the current Maven plugin from the repository root:

```text
mvn -pl vernac-maven-plugin -am clean install
```

From this example directory:

```text
mvn vernac:schema
mvn vernac:migration -Dvernac.migrationDescription=initial
```

Inspect `target/vernac-schema/schema.sql`, the candidate in
`src/main/resources/db/migration`, and the snapshot under
`src/main/vernac-schema/history`. Add `Title? note` to the fields of Tour and generate the next
candidate with `-Dvernac.migrationDescription=add_note`.

You alone are responsible for reviewing, testing and approving every migration
before application. Generation does not guarantee correctness or safety.
`-Dvernac.force=true` only permits generation despite identified data-loss risks.

See [the migration tutorial](../../docs/tutorials/postgresql-migrations.md)
for disposable database verification and team workflow.
