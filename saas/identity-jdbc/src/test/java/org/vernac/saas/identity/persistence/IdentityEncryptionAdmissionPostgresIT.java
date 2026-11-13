// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.saas.identity.persistence.domain.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityEncryptionAdmissionPostgresIT {
    @TempDir Path root;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    static JdbcIdentityEncryptionAdmission gate(AssertableApplicationContext c) {
        assertNull(c.getStartupFailure());
        return new JdbcIdentityEncryptionAdmission(
                c.getBean(NamedParameterJdbcTemplate.class),
                c.getBean(PlatformTransactionManager.class),
                CLOCK);
    }

    static TransactionTemplate tx(AssertableApplicationContext c) {
        return new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
    }

    static String fingerprint(int n) {
        return String.format("%064x", n);
    }

    static IdentityEncryptionKey stored(AssertableApplicationContext c, String name) {
        return tx(c).execute(
                        s ->
                                c.getBean(IdentityEncryptionKeyRepository.class)
                                        .named(IdentityEncryptionKeyName.of(name))
                                        .orElseThrow());
    }

    static void safe(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalStateException.class, action);
        assertEquals("IDENTITY_DELIVERY_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void attemptsSurviveUnusedPermitsBusinessRollbackAndRestart() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        var gate = gate(c);
                                        safe(() -> gate.reserve("first", fingerprint(1)));
                                        gate.initialize();
                                        gate.reserve("first", fingerprint(1));
                                        tx(c).executeWithoutResult(
                                                        s -> {
                                                            s.setRollbackOnly();
                                                        });
                                        gate.initialize();
                                        assertEquals(1, stored(c, "first").attempts().amount());
                                        // Neither key material nor the prepared secret needs to be
                                        // persisted.
                                        byte[] key = new byte[32];
                                        Arrays.fill(key, (byte) 7);
                                        var crypto =
                                                new IdentityDeliveryCrypto(
                                                        "actual",
                                                        Map.of("actual", key),
                                                        CLOCK,
                                                        gate);
                                        crypto.prepare(); // abandoned request, no encryption and
                                        // no refund
                                        assertEquals(1, stored(c, "actual").attempts().amount());
                                        tx(c).executeWithoutResult(s -> safe(crypto::prepare));
                                        assertEquals(1, stored(c, "actual").attempts().amount());
                                    });
                            db.boot.run(
                                    c -> {
                                        var gate = gate(c);
                                        gate.initialize();
                                        gate.reserve("first", fingerprint(1));
                                        assertEquals(2, stored(c, "first").attempts().amount());
                                    });
                            assertEquals(10, db.migrationCount());
                        });
    }

    @Test
    void twoInstancesCompeteForTheLastAttemptWithoutResettingTheCap() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var first = gate(c);
                                            var second = gate(c);
                                            first.initialize();
                                            first.reserve("first", fingerprint(1));
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var r = stored(c, "first");
                                                                c.getBean(
                                                                                IdentityEncryptionKeyRepository
                                                                                        .class)
                                                                        .save(
                                                                                IdentityEncryptionKey
                                                                                        .reconstitute(
                                                                                                r
                                                                                                        .id(),
                                                                                                r
                                                                                                        .keyName(),
                                                                                                r
                                                                                                        .fingerprint(),
                                                                                                IdentityEncryptionAttempts
                                                                                                        .of(
                                                                                                                1_048_575),
                                                                                                r
                                                                                                        .createdAt(),
                                                                                                r
                                                                                                        .updatedAt(),
                                                                                                r.persistenceState()
                                                                                                        .version()));
                                                            });
                                            AtomicInteger admitted = new AtomicInteger();
                                            try (var pool = Executors.newFixedThreadPool(8)) {
                                                List<Future<?>> jobs = new ArrayList<>();
                                                for (int i = 0; i < 16; i++) {
                                                    var gate = i % 2 == 0 ? first : second;
                                                    jobs.add(
                                                            pool.submit(
                                                                    () -> {
                                                                        try {
                                                                            gate.reserve(
                                                                                    "first",
                                                                                    fingerprint(1));
                                                                            admitted
                                                                                    .incrementAndGet();
                                                                        } catch (
                                                                                IllegalStateException
                                                                                        expected) {
                                                                            assertEquals(
                                                                                    "IDENTITY_DELIVERY_UNAVAILABLE",
                                                                                    expected
                                                                                            .getMessage());
                                                                        }
                                                                    }));
                                                }
                                                for (var job : jobs)
                                                    try {
                                                        job.get(10, TimeUnit.SECONDS);
                                                    } catch (Exception e) {
                                                        throw new AssertionError(e);
                                                    }
                                            }
                                            assertEquals(1, admitted.get());
                                            assertEquals(
                                                    1_048_576,
                                                    stored(c, "first").attempts().amount());
                                            first.initialize();
                                            safe(() -> second.reserve("first", fingerprint(1)));
                                        }));
    }

    @Test
    void keyIdentityAliasesAndBoundedHistoryFailClosedAcrossRestart() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        var gate = gate(c);
                                        gate.initialize();
                                        gate.reserve("first", fingerprint(1));
                                        safe(() -> gate.reserve("first", fingerprint(2)));
                                        safe(() -> gate.reserve("alias", fingerprint(1)));
                                        assertEquals(1, stored(c, "first").attempts().amount());
                                        for (int i = 2; i <= 64; i++)
                                            gate.reserve("key-" + i, fingerprint(i));
                                        safe(() -> gate.reserve("overflow", fingerprint(65)));
                                        gate.reserve("first", fingerprint(1));
                                        assertEquals(2, stored(c, "first").attempts().amount());
                                    });
                            db.boot.run(
                                    c -> {
                                        var gate = gate(c);
                                        gate.initialize();
                                        safe(() -> gate.reserve("alias", fingerprint(1)));
                                        safe(() -> gate.reserve("overflow", fingerprint(65)));
                                    });
                        });
    }

    @Test
    void damagedRegistryCannotSilentlyRestartFromZero() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var gate = gate(c);
                                            gate.initialize();
                                            gate.reserve("first", fingerprint(1));
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    c.getBean(
                                                                                    IdentityEncryptionRegistryRepository
                                                                                            .class)
                                                                            .delete(
                                                                                    c.getBean(
                                                                                                    IdentityEncryptionRegistryRepository
                                                                                                            .class)
                                                                                            .byId(
                                                                                                    JdbcIdentityEncryptionAdmission
                                                                                                            .ID)));
                                            safe(gate::initialize);
                                            safe(() -> gate.reserve("first", fingerprint(1)));
                                        }));
    }
}
