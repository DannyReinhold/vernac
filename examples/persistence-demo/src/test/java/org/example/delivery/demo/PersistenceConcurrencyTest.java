package org.example.delivery.demo;

import org.example.delivery.domain.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.AggregateNotFoundException;
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

    @Test void concurrentDeleteWinsWithoutResurrectingTheAggregate() throws Exception {
        assertDeleteSaveConflict(true);
    }

    @Test void concurrentSaveWinsWithoutLosingItsChildren() throws Exception {
        assertDeleteSaveConflict(false);
    }

    private void assertDeleteSaveConflict(boolean deleteWins) throws Exception {
        TourId id = usecases.create();
        Tour initial = usecases.load(id);
        var bothLoaded = new CountDownLatch(2);
        var winnerWritten = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var winnerPid = new AtomicInteger();
        var loserPid = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = workers.submit(() -> transaction().executeWithoutResult(status -> {
                winnerPid.set(backendPid());
                Tour tour = repository.byId(id);
                assertEquals(initial.persistenceState().version(), tour.persistenceState().version());
                bothLoaded.countDown();
                await(bothLoaded);
                writeOrDelete(tour, deleteWins);
                winnerWritten.countDown();
                await(allowCommit);
            }));
            Future<?> loser = workers.submit(() -> transaction().executeWithoutResult(status -> {
                loserPid.set(backendPid());
                Tour tour = repository.byId(id);
                assertEquals(initial.persistenceState().version(), tour.persistenceState().version());
                bothLoaded.countDown();
                await(bothLoaded);
                await(winnerWritten);
                writeOrDelete(tour, !deleteWins);
            }));
            await(winnerWritten);
            assertNotEquals(winnerPid.get(), loserPid.get());
            awaitDatabaseLock(winnerPid.get(), loserPid.get());
            allowCommit.countDown();
            winner.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> loser.get(20, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, failure.getCause());

            if (deleteWins) {
                assertThrows(AggregateNotFoundException.class, () -> usecases.load(id));
            } else {
                Tour fresh = usecases.load(id);
                assertEquals(initial.persistenceState().version() + 1, fresh.persistenceState().version());
                assertEquals(initial.allStops().asList().reversed().stream().map(Stop::id).toList(),
                        fresh.allStops().stream().map(Stop::id).toList());
                Stop first = fresh.allStops().by(initial.allStops().get(0).id()).orElseThrow();
                Stop second = fresh.allStops().by(initial.allStops().get(2).id()).orElseThrow();
                assertEquals(Address.of("Saved before deletion", "Bremen"), first.address());
                assertEquals(initial.allStops().get(2).address(), second.address());
                assertEquals(initial.title(), fresh.title());
                assertEquals(initial.price(), fresh.price());
                assertEquals(initial.tags(), fresh.tags());
                assertEquals(initial.status(), fresh.status());
                assertEquals(initial.createdAt(), fresh.createdAt());
                assertEquals(Notes.of("Saved", null), fresh.notes().orElseThrow());
                assertEquals(1, fresh.niceStops().size());
                assertSame(first, fresh.niceStops().get(0));
                assertSame(first, fresh.preferredStop().orElseThrow());
                assertSame(first, fresh.allStops().get(2));
                assertSame(first.parcels().iterator().next(), second.parcels().iterator().next());
            }
            // Check every table of this model directly, including rows invisible to a root loader.
            assertEquals(deleteWins ? 0L : 2L, count("Tour.@entity:org.example.delivery.Stop", id));
            assertEquals(deleteWins ? 0L : 1L, count("Tour.@entity:org.example.delivery.Parcel", id));
            assertEquals(deleteWins ? 0L : 2L, count("Tour.@entity:org.example.delivery.Stop.parcels", id));
            assertEquals(deleteWins ? 0L : 3L, count("Tour.allStops", id));
            assertEquals(deleteWins ? 0L : 1L, count("Tour.niceStops", id));
            assertEquals(deleteWins ? 0L : 1L, jdbc.queryForObject(
                    "SELECT count(*) FROM \"org.example.delivery\".\"Tour\" WHERE \"id\"=:id",
                    Map.of("id", id.value()), Long.class));
            assertEquals(deleteWins ? 0L : 2L, jdbc.queryForObject(
                    "SELECT count(*) FROM \"org.example.delivery\".\"Tour.tags\" WHERE \"@ownerId\"=:id",
                    Map.of("id", id.value()), Long.class));
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(25, TimeUnit.SECONDS)) {
                fail("Concurrent writers did not terminate; database cleanup was not attempted");
            }
            try {
                usecases.delete(id);
            } catch (AggregateNotFoundException alreadyDeleted) {
                // The winning deletion has already cleaned up this test's aggregate.
            }
        }
    }

    private void writeOrDelete(Tour tour, boolean delete) {
        if (delete) {
            repository.delete(tour);
        } else {
            tour.changeAddress(tour.allStops().get(0).id(), Address.of("Saved before deletion", "Bremen"));
            tour.reverseStops();
            tour.recordNotes(Notes.of("Saved", null));
            repository.save(tour);
        }
    }

    @Test void readCommittedLoadRejectsACommitBetweenRootAndChildQueries() throws Exception {
        TourId id = usecases.create();
        Tour initial = usecases.load(id);
        var childTableLocked = new CountDownLatch(1);
        var readerStarted = new CountDownLatch(1);
        var allowWrite = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        var readerPid = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = workers.submit(() -> transaction().executeWithoutResult(status -> {
                writerPid.set(backendPid());
                Tour tour = repository.byId(id);
                // Test-only scheduling: pause the loader at a child SELECT after its root SELECT.
                // Production repositories do not acquire table locks.
                jdbc.getJdbcTemplate().execute(
                        "LOCK TABLE \"org.example.delivery\".\"Tour.@entity:org.example.delivery.Stop\" IN ACCESS EXCLUSIVE MODE");
                childTableLocked.countDown();
                await(allowWrite);
                writeOrDelete(tour, false);
            }));
            Future<Tour> reader = workers.submit(() -> {
                await(childTableLocked);
                var read = transaction();
                read.setReadOnly(true);
                return read.execute(status -> {
                    readerPid.set(backendPid());
                    readerStarted.countDown();
                    return repository.byId(id);
                });
            });
            await(childTableLocked);
            await(readerStarted);
            assertNotEquals(writerPid.get(), readerPid.get());
            awaitDatabaseLock(writerPid.get(), readerPid.get());
            String blockedQuery = jdbc.queryForObject("SELECT query FROM pg_stat_activity WHERE pid=:pid",
                    Map.of("pid", readerPid.get()), String.class);
            assertNotNull(blockedQuery);
            assertTrue(blockedQuery.startsWith("SELECT ")
                    && blockedQuery.contains("\"Tour.@entity:org.example.delivery.Stop\""), blockedQuery);
            allowWrite.countDown();
            writer.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> reader.get(20, TimeUnit.SECONDS));
            assertInstanceOf(OptimisticLockingFailureException.class, failure.getCause());

            // Recovery is a new read after the concurrent commit, not a hidden automatic retry.
            Tour fresh = usecases.load(id);
            assertEquals(initial.persistenceState().version() + 1, fresh.persistenceState().version());
            assertEquals(Notes.of("Saved", null), fresh.notes().orElseThrow());
            assertEquals(initial.allStops().asList().reversed().stream().map(Stop::id).toList(),
                    fresh.allStops().stream().map(Stop::id).toList());
            Stop first = fresh.allStops().by(initial.allStops().get(0).id()).orElseThrow();
            Stop second = fresh.allStops().by(initial.allStops().get(2).id()).orElseThrow();
            assertEquals(Address.of("Saved before deletion", "Bremen"), first.address());
            assertEquals(initial.allStops().get(2).address(), second.address());
            assertEquals(initial.tags(), fresh.tags());
            assertSame(first, fresh.allStops().get(2));
            assertSame(first, fresh.preferredStop().orElseThrow());
            assertSame(first, fresh.niceStops().get(0));
            assertSame(first.parcels().iterator().next(), second.parcels().iterator().next());
        } finally {
            allowWrite.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(25, TimeUnit.SECONDS)) {
                fail("Reader/writer did not terminate; database cleanup was not attempted");
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
        fail("The competing operation did not wait for the expected PostgreSQL lock");
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
