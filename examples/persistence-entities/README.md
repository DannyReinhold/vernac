# Aggregate entity graph schema

This standalone example focuses on the **schema generator** and shared entity
relationships. It does not start an application or database. Install the current Maven plugin from the repository root:

```text
mvn -pl vernac-maven-plugin -am clean install
mvn -f examples/persistence-entities/pom.xml vernac:schema
```

Inspect `target/vernac-schema/schema.sql` inside this example. Tour refers to Stops
through `allStops`, `niceStops` and optional `preferredStop`. All three relationships
can target the same Stop state. A Stop contains a set of Parcels and a list of Tags.

Expect eight tables: Tour, Stop state, Parcel state, two root entity-list relationships,
Stop-to-Parcel set relationships, Stop tags and Tour tags. State tables include the
fully qualified entity type in their names. No namespace/import special cases apply.

For a migration candidate:

```text
mvn -f examples/persistence-entities/pom.xml vernac:migration -Dvernac.migrationDescription=initial_entities
```

Review and test every candidate before applying it. You alone are responsible for
its correctness and suitability for your database and deployment. Generation and
verification are aids, not guarantees. Never overwrite already applied migrations.

Changing entity relationships affects their own tables; shared entity state tables
are independent of individual incoming paths. List positions belong to relationships.
The JDBC writer/loader preserves shared Java identity and removes state only when
no longer reachable from the aggregate. Run [the persistence demo](../persistence-demo/README.md)
to observe and test that behavior. This focused example only generates its schema.

See [the persistence contract](../../docs/contracts/postgresql-persistence.md).
