# PostgreSQL schema and migration candidates

This is a **schema-tooling example**, not a runnable persistence application.
The new JDBC repository generator and entity storage are separate follow-up work.

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
`src/main/vernac-schema/history`. Add `Title? note` to Tour and generate the next
candidate with `-Dvernac.migrationDescription=add_note`.

You alone are responsible for reviewing, testing and approving every migration
before application. Generation does not guarantee correctness or safety.
`-Dvernac.force=true` only permits generation despite identified data-loss risks.

See [the migration tutorial](../../docs/tutorials/postgresql-migrations.md)
for disposable database verification and team workflow.
