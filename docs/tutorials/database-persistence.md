# Persist a complete aggregate with PostgreSQL

Use the standalone [persistence demo](../../examples/persistence-demo/README.md).
This tutorial assumes you already understand IDs, values, entities and collections.

## 1. Build and start

From the repository root:

```text
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter -am clean install
mvn -f examples/persistence-demo/pom.xml spring-boot:run
```

Docker Desktop must be running with Linux containers. The example's Compose file
starts PostgreSQL on localhost:55433. Spring Boot waits for it and Flyway runs the
committed migration. The runner then executes several independent usecase transactions.
Look for `Persistence walkthrough passed` in the output.

The example is a separate Maven project. Its POM has no parent reference to Vernac's
root POM, and the root reactor does not include it.

## 2. Read the model and generated API

Open `examples/persistence-demo/src/main/vernac/org/example/delivery/model.vernac`.
A Tour contains lists of Stops, an optional preferred Stop and value Tags. Stops
contain a set of Parcels. The same Stop may occur repeatedly, and the same Parcel
may belong to multiple Stops **within this aggregate**.

An explicit declaration requests persistence:

```vernac
repository TourRepository for Tour { }
```

The generated domain interface provides:

```java
Tour byId(TourId id);
Tour save(Tour aggregate);
void delete(Tour aggregate);
```

`byId` throws `AggregateNotFoundException` when missing. There is no lazy loading.
The implementation lives in `org.example.delivery.adapter.outbound.jdbc`; application
component scanning must include that package. Generated source is under
`target/generated-sources/vernac`. Do not edit it.

## 3. Keep transactions in usecases

The example's `DeliveryDemoUseCases` is a Spring service with `@Transactional` methods.
The runner calls that bean, so each call crosses Spring's transaction proxy.

```java
@Transactional
public void changeAndReorder(TourId id) {
    Tour tour = tours.byId(id);
    tour.changeAddress(tour.allStops().get(0).id(),
        Address.of("Changed Street", "Bremen"));
    tour.reverseStops();
    tours.save(tour);
}
```

The repository uses MANDATORY propagation. Calling it outside a transaction fails;
it does not silently start a transaction. Handwritten usecases are intentional here:
the example focuses on persistence without depending on the pending usecase-language
review. Avoid self-invocation as a substitute for calling a transactional Spring bean.

## 4. Observe shared identity and updates

The runner stores `[first, first, second]` and references `first` from `niceStops` and
`preferredStop` as well. After loading, all those references point to the same Java
object. Changing an entity through one reference is visible through the others.
The identity map is scoped to one load; two independent loads are separate snapshots.

The generated adapter supplies typed getters and factories to the JDBC runtime.
It does not use reflection, public setters, an ORM session or automatic dirty flushing.
Call `save` explicitly. Entity state and relationships are synchronized separately.
Reordering list entries changes their relationships rather than recreating entities.

Value collections may be replaced as a unit. Their elements have value semantics;
entity collections instead retain entity state by identity.

## 5. Remove relationships and inspect the database

Removing `niceStops` and `preferredStop` leaves the Stops present in `allStops`.
Removing every reference to a Stop deletes its state. The Parcel shared with the
other Stop survives until it too becomes unreachable from the root.

From the example directory, open PostgreSQL's shell:

```text
docker compose exec postgres psql -U delivery -d delivery
```

Then inspect the remaining root and state rows:

```sql
SELECT "id", "@version", "title" FROM "org.example.delivery"."Tour";
SELECT * FROM "org.example.delivery"."Tour.@entity:org.example.delivery.Stop";
SELECT * FROM "org.example.delivery"."Tour.@entity:org.example.delivery.Parcel";
SELECT * FROM "org.example.delivery"."Tour.allStops" ORDER BY "@aggregateId", "@position";
```

The completed runner leaves its Tour but removes its Stops and Parcels. Constraints
prevent cross-aggregate references. Entity-target FKs are deferred until transaction
end, permitting root/entity insertion in the same transaction.

## 6. Exercise failures deliberately

The runner retains an old Tour, saves a newer version, then tries saving the stale
snapshot. The latter fails with `OptimisticLockingFailureException`; no INSERT fallback
resurrects a missing root. Two different Java entity objects with the same type and ID
inside one graph are rejected before the first SQL write.

A rollback restores database state, **not Java object state**. In particular, an object's
technical version may already have advanced. After failure, do not retry that same
snapshot blindly; decide how the usecase should recover or reload.

## 7. Run the database tests

From the example directory:

```text
docker compose --profile test up -d --wait postgres-test
mvn test -Dvernac.test.database=true
```

The eight integration tests use a separate database on port 55434 and inspect actual
rows. A regular build does not require Docker; it explicitly skips these tests unless
you enable them. Enable them in CI against a disposable PostgreSQL database.

The concurrency test coordinates two real transactions and waits for a PostgreSQL
lock conflict before allowing the winner to commit. See the example README for the
focused `PersistenceConcurrencyTest` command.

Next: [evolve the schema with Flyway](flyway-workflow.md).
