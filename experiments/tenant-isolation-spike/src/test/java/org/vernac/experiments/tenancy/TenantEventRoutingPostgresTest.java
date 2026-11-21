// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.experiments.tenancy.PostgresFixture.*;
import static org.vernac.experiments.tenancy.EventRoutingProbe.*;

/** SQL/routing feasibility only. Production EventContext still rejects durable tenant publication. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class TenantEventRoutingPostgresTest {
    private static final Origin GLOBAL = Origin.global(), TENANT_A = new Origin(A), TENANT_B = new Origin(B);
    private PostgresFixture fixture;
    private EventRoutingProbe probe;

    @BeforeAll void database() throws Exception {
        fixture = new PostgresFixture();
        try (var connection = fixture.ownerSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("event-schema.sql"));
        }
        fixture.admin("GRANT SELECT,INSERT ON tenant_spike.event_payload,tenant_spike.event_value TO " + fixture.runtime);
        fixture.admin("GRANT SELECT,INSERT,UPDATE,DELETE ON tenant_spike.event_delivery TO " + fixture.runtime);
    }
    @AfterAll void cleanupDatabase() throws Exception { if (fixture != null) fixture.close(); }
    @BeforeEach void setup() throws Exception {
        fixture.reset();
        fixture.admin("TRUNCATE tenant_spike.event_value,tenant_spike.event_payload,tenant_spike.event_delivery");
        probe = new EventRoutingProbe(fixture.pool);
    }
    @AfterEach void cleanContext() throws Exception {
        assertTrue(TenantContext.currentTenant().isEmpty());
        assertTrue(TenantContext.transactionTenant().isEmpty());
        try (var first = fixture.pool.getConnection(); var second = fixture.pool.getConnection()) {
            for (var connection : List.of(first, second)) try (var sql = connection.createStatement();
                    var row = sql.executeQuery("SELECT current_setting('vernac.tenant_id',true)")) {
                assertTrue(row.next()); String selected = row.getString(1);
                assertTrue(selected == null || selected.isEmpty(), "A pooled session retained tenant routing");
            }
        }
    }
    private Payload payload(Origin origin, String text) {
        return new Payload(UUID.randomUUID(), origin.tenant() == null ? null : origin.equals(TENANT_A) ? INVOICE_A : INVOICE_B,
                text, List.of(text + "-first", text + "-second"));
    }
    private long count(String table) { return new JdbcTemplate(fixture.adminSource()).queryForObject("SELECT count(*) FROM tenant_spike." + table, Long.class); }
    private String title(UUID id) { return new JdbcTemplate(fixture.adminSource()).queryForObject("SELECT title FROM tenant_spike.invoice WHERE id=?", String.class, id); }
    private void updateInvoice(Payload payload, String title) {
        if (payload.invoice() != null)
            assertEquals(1, probe.jdbc.update("UPDATE tenant_spike.invoice SET title=? WHERE id=?", title, payload.invoice()));
    }
    private String status(Claim claim) { return probe.jdbc.queryForObject("SELECT status FROM tenant_spike.event_delivery WHERE id=?", String.class, claim.id()); }

    @Test void nullableForeignKeysNeedANonNullScopeKeyForGlobalChildren() {
        Payload a = payload(TENANT_A, "A"), global = payload(GLOBAL, "global");
        probe.publish(TENANT_A, a, List.of()); probe.publish(GLOBAL, global, List.of());
        fixture.admin("ALTER TABLE tenant_spike.event_payload ADD CONSTRAINT naive_key UNIQUE(tenant_id,id)");
        fixture.admin("CREATE TABLE tenant_spike.naive_event_child(tenant_id UUID,event_id UUID,FOREIGN KEY(tenant_id,event_id) REFERENCES tenant_spike.event_payload(tenant_id,id))");
        fixture.admin("GRANT INSERT ON tenant_spike.naive_event_child TO " + fixture.runtime);
        try {
            assertEquals(1, probe.inOrigin(GLOBAL, () -> probe.jdbc.update("INSERT INTO tenant_spike.naive_event_child VALUES(NULL,?)", a.id())),
                    "PostgreSQL skips a MATCH SIMPLE FK when any referencing component is null");
            assertThrows(DataAccessException.class, () -> probe.inOrigin(GLOBAL, () -> probe.jdbc.update(
                    "INSERT INTO tenant_spike.event_value VALUES(NULL,'GLOBAL',?,99,'wrong origin')", a.id())));
            assertThrows(DataAccessException.class, () -> probe.inOrigin(TENANT_B, () -> probe.jdbc.update(
                    "INSERT INTO tenant_spike.event_value VALUES(?,?,?,99,'wrong tenant')", B, TENANT_B.key(), a.id())));
            assertThrows(DataAccessException.class, () -> probe.inOrigin(TENANT_A, () -> probe.jdbc.update(
                    "INSERT INTO tenant_spike.event_value VALUES(NULL,'GLOBAL',?,99,'downgrade')", global.id())),
                    "A tenant-bound write cannot silently publish a global row");
        } finally {
            fixture.admin("DROP TABLE tenant_spike.naive_event_child");
            fixture.admin("ALTER TABLE tenant_spike.event_payload DROP CONSTRAINT naive_key");
        }
    }

    @Test void payloadPolicyAndExactOriginLoadingRejectCrossTenantAndForgedRouting() {
        Payload a = payload(TENANT_A, "private-A"), b = payload(TENANT_B, "private-B"), global = payload(GLOBAL, "global");
        probe.publish(TENANT_A, a, List.of("receiver")); probe.publish(TENANT_B, b, List.of("receiver")); probe.publish(GLOBAL, global, List.of("receiver"));
        assertEquals(1L, probe.inOrigin(GLOBAL, () -> probe.jdbc.queryForObject("SELECT count(*) FROM tenant_spike.event_payload", Long.class)));
        assertEquals(2L, probe.inOrigin(TENANT_A, () -> probe.jdbc.queryForObject("SELECT count(*) FROM tenant_spike.event_payload", Long.class)),
                "RLS permits the current tenant and deliberately global payloads");
        assertThrows(IllegalStateException.class, () -> probe.inOrigin(TENANT_B, () -> probe.load(TENANT_B, a.id())));
        assertThrows(IllegalStateException.class, () -> probe.inOrigin(GLOBAL, () -> probe.load(GLOBAL, a.id())));
        assertThrows(IllegalStateException.class, () -> probe.inOrigin(TENANT_A, () -> probe.load(TENANT_A, global.id())),
                "Delivery loading uses exact origin even though the read policy permits global rows");
        assertThrows(IllegalStateException.class, () -> probe.publish(TENANT_B, a, List.of("other")));
        probe.jdbc.update("UPDATE tenant_spike.event_delivery SET tenant_id=NULL WHERE event_id=?", a.id());
        Set<UUID> invoked = new HashSet<>();
        for (Claim claim; (claim = probe.claim()) != null;) probe.process(claim, p -> invoked.add(p.id()));
        assertEquals(Set.of(b.id(), global.id()), invoked, "A forged global delivery never loads a tenant payload or calls its handler");
    }

    @Test void graphAndDeliveriesRollbackAtomicallyAndFirstRecipientsStayFixed() {
        Payload a = payload(TENANT_A, "A");
        assertThrows(IllegalStateException.class, () -> probe.inOrigin(TENANT_A, () -> {
            probe.append(TENANT_A, a, List.of("one", "two")); updateInvoice(a, "rolled back");
            throw new IllegalStateException("fixture failure");
        }));
        for (String table : List.of("event_payload", "event_value", "event_delivery")) assertEquals(0, count(table));
        assertEquals("A title", title(INVOICE_A));
        probe.publish(TENANT_A, a, List.of("one", "two"));
        probe.publish(TENANT_A, a, List.of("one", "two", "new receiver"));
        assertEquals(1, count("event_payload")); assertEquals(2, count("event_value")); assertEquals(2, count("event_delivery"));
        var first = probe.claim(); assertTrue(probe.process(first, p -> assertEquals(a, p)));
        assertEquals(1L, probe.jdbc.queryForObject("SELECT count(*) FROM tenant_spike.event_delivery WHERE status='PENDING'", Long.class));
        assertThrows(IllegalStateException.class, () -> probe.publish(TENANT_A,
                new Payload(a.id(), a.invoice(), "changed", a.values()), List.of("one")));
    }

    @Test void retryAfterWorkerRestartRetainsOriginAndOtherRecipientsContinue() {
        Payload a = payload(TENANT_A, "A"), b = payload(TENANT_B, "B");
        probe.publish(TENANT_A, a, List.of("one", "two")); probe.publish(TENANT_B, b, List.of("one"));
        var failed = probe.claim(); assertEquals(TENANT_A, failed.origin());
        assertFalse(probe.process(failed, p -> {updateInvoice(p, "must roll back"); throw new IllegalStateException("sensitive fixture payload");}));
        assertEquals("A title", title(INVOICE_A)); assertEquals("PENDING", status(failed));
        String diagnostic = probe.jdbc.queryForObject("SELECT last_error FROM tenant_spike.event_delivery WHERE id=?", String.class, failed.id());
        assertEquals(IllegalStateException.class.getName(), diagnostic);
        fixture.pool.close(); fixture.pool = fixture.pool(fixture.runtime); probe = new EventRoutingProbe(fixture.pool);
        var secondReceiver = probe.claim(); assertNotEquals(failed.id(), secondReceiver.id());
        assertTrue(probe.process(secondReceiver, p -> updateInvoice(p, "second receiver")));
        var otherTenant = probe.claim(); assertEquals(TENANT_B, otherTenant.origin());
        assertTrue(probe.process(otherTenant, p -> updateInvoice(p, "B completed")));
        probe.jdbc.update("UPDATE tenant_spike.event_delivery SET next_attempt_at=clock_timestamp() WHERE id=?", failed.id());
        var retry = probe.claim(); assertEquals(failed.id(), retry.id()); assertEquals(TENANT_A, retry.origin()); assertEquals(2, retry.attempt());
        assertTrue(probe.process(retry, p -> updateInvoice(p, "A completed")));
        assertEquals("A completed", title(INVOICE_A)); assertEquals("B completed", title(INVOICE_B));
        assertEquals(3L, probe.jdbc.queryForObject("SELECT count(*) FROM tenant_spike.event_delivery WHERE status='SUCCEEDED'", Long.class));
    }

    private void expire(Claim claim) { probe.jdbc.update("UPDATE tenant_spike.event_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", claim.id()); }
    @Test void expiredClaimsFenceOldWritesAndFinalAttemptCrashesDoNotStick() {
        probe.publish(TENANT_A, payload(TENANT_A, "A"), List.of("one"));
        var stale = probe.claim(); expire(stale); var replacement = probe.claim();
        assertNotEquals(stale.token(), replacement.token());
        assertFalse(probe.process(stale, p -> updateInvoice(p, "stale write")));
        assertEquals("A title", title(INVOICE_A)); assertEquals("PROCESSING", status(replacement));
        assertTrue(probe.process(replacement, p -> updateInvoice(p, "winner")));
        probe.publish(TENANT_B, payload(TENANT_B, "B"), List.of("one"));
        Claim abandoned = null;
        for (int i = 1; i <= 3; i++) { abandoned = probe.claim(); assertEquals(i, abandoned.attempt()); expire(abandoned); }
        assertNull(probe.claim()); assertEquals("FAILED", status(abandoned));
        try (var selected = TenantContext.openVerified(TenantId.of(A))) { assertThrows(IllegalStateException.class, probe::claim); }
        probe.transaction.executeWithoutResult(status -> assertThrows(IllegalStateException.class, probe::claim));
    }

    @Test void contaminatedPooledSessionsCannotLoadGlobalPayloadsAsAFallback() {
        var global = payload(GLOBAL, "global"); probe.publish(GLOBAL, global, List.of());
        try (var isolated = fixture.pool(fixture.runtime)) {
            isolated.setMaximumPoolSize(1);
            var worker = new EventRoutingProbe(isolated); var sql = new JdbcTemplate(isolated);
            sql.queryForObject("SELECT set_config('vernac.tenant_id',?,false)", String.class, A.toString());
            try {
                var error = assertThrows(IllegalStateException.class, () -> worker.inOrigin(GLOBAL, () -> worker.load(GLOBAL, global.id())));
                assertEquals("CONTAMINATED_GLOBAL_CONNECTION", error.getMessage());
            } finally { sql.queryForObject("SELECT set_config('vernac.tenant_id','',false)", String.class); }
            assertEquals(global, worker.inOrigin(GLOBAL, () -> worker.load(GLOBAL, global.id())));
        }
    }

    @Test void parallelWorkersClaimOnceAndUseIndependentTenantTransactions() throws Exception {
        probe.publish(TENANT_A, payload(TENANT_A, "A"), List.of("one")); probe.publish(TENANT_B, payload(TENANT_B, "B"), List.of("one"));
        var barrier = new CyclicBarrier(2);
        Map<String,Integer> connections = new ConcurrentHashMap<>(); Set<UUID> jobs = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(n -> executor.submit(() -> {
                var claim = probe.claim(); assertNotNull(claim); assertTrue(jobs.add(claim.id()));
                boolean success = probe.process(claim, p -> {
                    connections.put(claim.origin().key(), probe.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception failed) {throw new IllegalStateException("rendezvous failed", failed);}
                    updateInvoice(p, p.text());
                });
                assertTrue(TenantContext.currentTenant().isEmpty()); return success;
            })).toList();
            for (var task : tasks) assertTrue(task.get(10, TimeUnit.SECONDS));
        }
        assertEquals(Set.of(TENANT_A.key(), TENANT_B.key()), connections.keySet());
        assertEquals(2, new HashSet<>(connections.values()).size()); assertEquals(2, jobs.size());
        assertEquals(2L, probe.jdbc.queryForObject("SELECT count(*) FROM tenant_spike.event_delivery WHERE status='SUCCEEDED'", Long.class));
    }
}
