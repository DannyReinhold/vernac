// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.persistence.domain.IdentityChallengePurpose;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class IdentityDeliveryCryptoTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] SECRET =
            "synthetic-recipient@example.test|synthetic-secret".getBytes(StandardCharsets.UTF_8);
    private static final IdentityDeliveryCrypto.Context CONTEXT =
            new IdentityDeliveryCrypto.Context(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    IdentityChallengePurpose.VERIFICATION,
                    UUID.randomUUID(),
                    NOW.plusSeconds(600));

    @Test
    void encryptionBindsContextAndSurvivesReconstruction() {
        AtomicInteger reserved = new AtomicInteger();
        var one = crypto("old", Map.of("old", key(1)), reserved);
        String envelope = one.seal(CONTEXT, SECRET);
        String second = one.seal(CONTEXT, SECRET);
        assertNotEquals(envelope, second);
        assertFalse(envelope.contains("synthetic"));
        assertArrayEquals(
                SECRET, crypto("old", Map.of("old", key(1)), reserved).open(CONTEXT, envelope));
        assertEquals(2, reserved.get()); // Reading consumes no encryption admission.
        assertEquals("IdentityDeliveryCrypto[REDACTED]", one.toString());
        assertEquals("IdentityDeliveryContext[REDACTED]", CONTEXT.toString());
    }

    @Test
    void everyTrustedContextFieldRejectsCiphertextSubstitution() {
        var crypto = crypto("old", Map.of("old", key(1)), new AtomicInteger());
        String envelope = crypto.seal(CONTEXT, SECRET);
        for (var changed :
                List.of(
                        new IdentityDeliveryCrypto.Context(
                                UUID.randomUUID(),
                                CONTEXT.subject(),
                                CONTEXT.purpose(),
                                CONTEXT.locator(),
                                CONTEXT.expiresAt()),
                        new IdentityDeliveryCrypto.Context(
                                CONTEXT.jobId(),
                                UUID.randomUUID(),
                                CONTEXT.purpose(),
                                CONTEXT.locator(),
                                CONTEXT.expiresAt()),
                        new IdentityDeliveryCrypto.Context(
                                CONTEXT.jobId(),
                                CONTEXT.subject(),
                                IdentityChallengePurpose.RECOVERY,
                                CONTEXT.locator(),
                                CONTEXT.expiresAt()),
                        new IdentityDeliveryCrypto.Context(
                                CONTEXT.jobId(),
                                CONTEXT.subject(),
                                CONTEXT.purpose(),
                                UUID.randomUUID(),
                                CONTEXT.expiresAt()),
                        new IdentityDeliveryCrypto.Context(
                                CONTEXT.jobId(),
                                CONTEXT.subject(),
                                CONTEXT.purpose(),
                                CONTEXT.locator(),
                                CONTEXT.expiresAt().plusNanos(1)))) {
            safe(() -> crypto.open(changed, envelope));
        }
    }

    @Test
    void rotatedKeyRingReadsOldJobsAndRejectsRemovalOrRekeying() {
        var old = crypto("old", Map.of("old", key(1)), new AtomicInteger());
        String envelope = old.seal(CONTEXT, SECRET);
        var rotated = crypto("new", Map.of("old", key(1), "new", key(2)), new AtomicInteger());
        assertArrayEquals(SECRET, rotated.open(CONTEXT, envelope));
        String next = rotated.seal(CONTEXT, SECRET);
        safe(() -> old.open(CONTEXT, next));
        safe(
                () ->
                        crypto("new", Map.of("new", key(2)), new AtomicInteger())
                                .open(CONTEXT, envelope));
        safe(
                () ->
                        crypto("old", Map.of("old", key(3)), new AtomicInteger())
                                .open(CONTEXT, envelope));
        // Even an alternate configured ID with matching material is forbidden.
        safe(() -> crypto("old", Map.of("old", key(1), "alias", key(1)), new AtomicInteger()));
    }

    @Test
    void malformedTamperedAndOversizedInputsNeverExposeLibraryExceptions() {
        var crypto = crypto("old", Map.of("old", key(1)), new AtomicInteger());
        String envelope = crypto.seal(CONTEXT, SECRET);
        String[] parts = envelope.split("\\.");
        byte[] tampered = Base64.getUrlDecoder().decode(parts[2]);
        tampered[tampered.length / 2] ^= 1;
        for (String input :
                List.of(
                        "",
                        "v2.old." + parts[2],
                        "v1.missing." + parts[2],
                        envelope + "=",
                        envelope.substring(0, envelope.length() - 2),
                        "v1.old."
                                + Base64.getUrlEncoder().withoutPadding().encodeToString(tampered),
                        "x".repeat(65_537),
                        "v1.old.synthetic-secret")) safe(() -> crypto.open(CONTEXT, input));
        safe(() -> crypto.open(CONTEXT, null));
    }

    @Test
    void boundsAndExpiryAreCheckedBeforeSpendingAnAttempt() {
        AtomicInteger spent = new AtomicInteger();
        var crypto = crypto("old", Map.of("old", key(1)), spent);
        safe(() -> crypto.seal(CONTEXT, new byte[48_001]));
        safe(() -> crypto.seal(CONTEXT, new byte[0]));
        safe(() -> crypto.seal(CONTEXT, null));
        var expired =
                new IdentityDeliveryCrypto.Context(
                        CONTEXT.jobId(),
                        CONTEXT.subject(),
                        CONTEXT.purpose(),
                        CONTEXT.locator(),
                        NOW);
        safe(() -> crypto.seal(expired, SECRET));
        assertEquals(0, spent.get());
        byte[] largest = new byte[48_000];
        Arrays.fill(largest, (byte) 42);
        String envelope = crypto.seal(CONTEXT, largest);
        assertTrue(envelope.getBytes(StandardCharsets.UTF_8).length <= 65_536);
        assertArrayEquals(largest, crypto.open(CONTEXT, envelope));
        var later =
                new IdentityDeliveryCrypto(
                        "old",
                        Map.of("old", key(1)),
                        Clock.fixed(CONTEXT.expiresAt(), ZoneOffset.UTC),
                        (id, hash) -> fail());
        safe(() -> later.open(CONTEXT, envelope));
        assertEquals(1, spent.get());
    }

    @Test
    void missingInvalidOrExcessKeysFailClosedAndCallerMutationCannotChangeKeys() {
        for (var configured :
                List.of(
                        Map.<String, byte[]>of(),
                        Map.of("old", new byte[31]),
                        Map.of("bad.id", key(1)),
                        Map.of("a", key(1), "b", key(2), "c", key(3), "d", key(4), "e", key(5))))
            safe(() -> crypto("old", configured, new AtomicInteger()));
        safe(() -> new IdentityDeliveryCrypto("old", Map.of("old", key(1)), CLOCK, null));
        byte[] material = key(1);
        var crypto = crypto("old", Map.of("old", material), new AtomicInteger());
        Arrays.fill(material, (byte) 0);
        assertArrayEquals(
                SECRET,
                crypto("old", Map.of("old", key(1)), new AtomicInteger())
                        .open(CONTEXT, crypto.seal(CONTEXT, SECRET)));
    }

    @Test
    void refusedAdmissionCannotEncryptAndItsExceptionIsSanitized() {
        var crypto =
                new IdentityDeliveryCrypto(
                        "old",
                        Map.of("old", key(1)),
                        CLOCK,
                        (id, hash) -> {
                            assertEquals("old", id);
                            assertTrue(hash.matches("[0-9a-f]{64}"));
                            throw new IllegalArgumentException("synthetic-secret");
                        });
        safe(() -> crypto.seal(CONTEXT, SECRET));
    }

    @Test
    void concurrentCallersCannotBypassTheirSharedAdmissionBoundary() throws Exception {
        // Test double only: real durable/nonrefundable JDBC admission is a separate gate.
        AtomicInteger spent = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        IdentityDeliveryCrypto.EncryptionAdmission admission =
                (id, hash) -> {
                    int value = spent.incrementAndGet();
                    if (value > 3) throw new IllegalStateException("denied");
                };
        var first = new IdentityDeliveryCrypto("old", Map.of("old", key(1)), CLOCK, admission);
        var second = new IdentityDeliveryCrypto("old", Map.of("old", key(1)), CLOCK, admission);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                var chosen = i % 2 == 0 ? first : second;
                jobs.add(
                        pool.submit(
                                () -> {
                                    try {
                                        chosen.seal(CONTEXT, SECRET);
                                        accepted.incrementAndGet();
                                    } catch (IllegalStateException denied) {
                                        assertEquals(
                                                "IDENTITY_DELIVERY_UNAVAILABLE",
                                                denied.getMessage());
                                    }
                                }));
            }
            for (var job : jobs) job.get();
        }
        assertEquals(32, spent.get());
        assertEquals(3, accepted.get());
    }

    private static IdentityDeliveryCrypto crypto(
            String active, Map<String, byte[]> keys, AtomicInteger spent) {
        return new IdentityDeliveryCrypto(
                active, keys, CLOCK, (id, hash) -> spent.incrementAndGet());
    }

    private static byte[] key(int fixture) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fixture);
        return key;
    }

    private static void safe(org.junit.jupiter.api.function.Executable work) {
        var error = assertThrows(IllegalStateException.class, work);
        assertEquals("IDENTITY_DELIVERY_UNAVAILABLE", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        var output = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(output));
        assertFalse(output.toString().contains("synthetic-secret"));
        assertFalse(output.toString().contains("synthetic-recipient"));
    }
}
