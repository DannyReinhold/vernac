# Document Workspace scenarios

Vernac defines a document, its deletion permission, a generated MVC adapter
and a typed page. Test-scoped Spring configurations supply an editor or reader,
a document store and a transaction manager. The same permission decision
controls the displayed Delete form and the guarded command.

Run the default cases from the repository root.

```sh
mvn -pl examples/document-workspace-scenarios -am test
```

For committed storage checks, set `VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER` and
`VERNAC_PG_TEST_PASSWORD` for a disposable PostgreSQL database. Each case creates
and drops its own schema. The database user needs permission to create schemas.

```sh
mvn -pl examples/document-workspace-scenarios -am -Dvernac.testProfile=postgres -Dtest=VernacScenariosTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The PostgreSQL cases read committed documents and event records through separate
usecase invocations after the delete transaction has finished. The fixture uses
test JDBC adapters and an injected listener failure to exercise rollback. The
Spring Boot wizard's `database-tests` profile exercises the generated JDBC
repository and Flyway migration.
