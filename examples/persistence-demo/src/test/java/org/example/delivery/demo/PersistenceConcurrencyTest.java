package org.example.delivery.demo;

import org.example.delivery.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Requires a real PostgreSQL server with independent concurrent connections. */
@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false", "demo.run=false",
        "spring.datasource.url=${vernac.test.url:jdbc:postgresql://localhost:55434/delivery_test}",
        "spring.datasource.username=${VERNAC_TEST_USER:delivery}",
        "spring.datasource.password=${VERNAC_TEST_PASSWORD:local-demo-only}",
        // Two writers plus one observer for PostgreSQL's actual lock state.
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=5000"
})
@EnabledIfSystemProperty(named = "vernac.test.database", matches = "true")
class PersistenceConcurrencyTest {
    @Autowired DeliveryDemoUseCases usecases;
    @Autowired TourRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void rootVersionProtectsAllChildrenOfConcurrentRequests() throws Exception {
        TourId id = usecases.create();
        Tour initial = usecases.load(id);
        Stop first = initial.allStops().get(0);
        Stop second = initial.allStops().get(2);
        long version = initial.persistenceState().version();
        var bothLoaded = new CountDownLatch(2);
        var winnerSaved = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var winnerPid = new AtomicInteger();
        var loserPid = new AtomicInteger();
        var proposedChild = new AtomicReference<StopId>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = workers.submit(() -> transaction().executeWithoutResult(status -> {
                winnerPid.set(backendPid());
                Tour tour = repository.byId(id);
                assertEquals(version, tour.persistenceState().version());
                bothLoaded.countDown();
                await(bothLoaded);
                tour.changeAddress(first.id(), Address.of("Winner Street", "Bremen"));
                tour.reverseStops();
                repository.save(tour);
                winnerSaved.countDown();
                await(allowCommit); // Keep the root lock until the competing UPDATE is blocked.
            }));
            Future<?> loser = workers.submit(() -> transaction().executeWithoutResult(status -> {
                loserPid.set(backendPid());
                Tour tour = repository.byId(id);
                assertEquals(version, tour.persistenceState().version());
                bothLoaded.countDown();
                await(bothLoaded);
                tour.changeAddress(second.id(), Address.of("Loser Street", "Bremen"));
                tour.removeStop(first.id());
                Stop extra = Stop.create(Address.of("Loser-only Street", "Bremen"), Parcels.empty());
                proposedChild.set(extra.id());
                tour.addStop(extra);
                await(winnerSaved);
                repository.save(tour); // Waits for the winner, then must fail on the stale version.
            }));

            await(winnerSaved);
            assertNotEquals(winnerPid.get(), loserPid.get(), "Writers must use different PostgreSQL sessions");
            awaitDatabaseLock(winnerPid.get(), loserPid.get());
            allowCommit.countDown();
            winner.get(20, TimeUnit.SECONDS); // Success includes transaction commit.
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> loser.get(20, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, failure.getCause());

            Tour fresh = usecases.load(id); // A third, fresh transaction reconstructs the complete graph.
            assertEquals(version + 1, fresh.persistenceState().version());
            assertEquals(List.of(second.id(), first.id(), first.id()),
                    fresh.allStops().stream().map(Stop::id).toList());
            Stop loadedFirst = fresh.allStops().by(first.id()).orElseThrow();
            Stop loadedSecond = fresh.allStops().by(second.id()).orElseThrow();
            assertEquals(Address.of("Winner Street", "Bremen"), loadedFirst.address());
            assertEquals(second.address(), loadedSecond.address(), "No losing child update may leak");
            assertEquals(initial.title(), fresh.title());
            assertEquals(initial.price(), fresh.price());
            assertEquals(initial.notes(), fresh.notes());
            assertEquals(initial.tags(), fresh.tags());
            assertEquals(initial.status(), fresh.status());
            assertEquals(initial.createdAt(), fresh.createdAt());
            assertEquals(1, fresh.niceStops().size());
            assertSame(loadedFirst, fresh.niceStops().get(0));
            assertSame(loadedFirst, fresh.preferredStop().orElseThrow());
            assertSame(loadedFirst, fresh.allStops().get(2));
            assertSame(loadedFirst.parcels().iterator().next(), loadedSecond.parcels().iterator().next());
            assertEquals(2L, count("Tour.@entity:org.example.delivery.Stop", id));
            assertEquals(1L, count("Tour.@entity:org.example.delivery.Parcel", id));
            assertEquals(2L, count("Tour.@entity:org.example.delivery.Stop.parcels", id));
            assertEquals(3L, count("Tour.allStops", id));
            assertEquals(1L, count("Tour.niceStops", id));
            assertNotNull(proposedChild.get());
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM \"org.example.delivery\".\"Tour.@entity:org.example.delivery.Stop\""
                            + " WHERE \"@aggregateId\"=:root AND \"id\"=:child",
                    Map.of("root", id.value(), "child", proposedChild.get().value()), Long.class));
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            // Do not race cleanup against an active transaction if a test assertion failed.
            if (!workers.awaitTermination(25, TimeUnit.SECONDS)) {
                fail("Concurrent writers did not terminate; database cleanup was not attempted");
            }
            usecases.delete(id);
        }
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(20);
        return tx;
    }

    private int backendPid() {
        // Bound server-side lock waiting as well as Java-side coordination.
        jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '15s'");
        return jdbc.queryForObject("SELECT pg_backend_pid()", Map.of(), Integer.class);
    }

    private void awaitDatabaseLock(int winner, int loser) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            Boolean blocked = jdbc.queryForObject("SELECT :winner = ANY(pg_blocking_pids(:loser))",
                    Map.of("winner", winner, "loser", loser), Boolean.class);
            if (Boolean.TRUE.equals(blocked)) return;
            // Poll an observable server condition; no fixed delay determines the interleaving.
            TimeUnit.MILLISECONDS.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("The competing UPDATE did not wait for the winner's PostgreSQL lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out coordinating concurrent requests");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent request interrupted", e);
        }
    }

    private long count(String table, TourId id) {
        return jdbc.queryForObject("SELECT count(*) FROM \"org.example.delivery\".\"" + table
                + "\" WHERE \"@aggregateId\"=:id", Map.of("id", id.value()), Long.class);
    }
}
