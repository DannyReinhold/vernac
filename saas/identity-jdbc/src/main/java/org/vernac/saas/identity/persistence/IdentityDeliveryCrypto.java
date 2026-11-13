// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.vernac.saas.identity.persistence.domain.IdentityChallengePurpose;

import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.spec.SecretKeySpec;

/** Private envelope primitive. Not a bean, provider, durable queue or public credential API. */
final class IdentityDeliveryCrypto {
    static final int MAX_ENVELOPE_BYTES = 65_536;
    static final int MAX_PAYLOAD_BYTES = 48_000;
    static final long MAX_ENCRYPTIONS_PER_KEY = 1_048_576;
    private final String active;
    private final Map<String, Key> keys;
    private final Clock clock;
    private final EncryptionAdmission admission;

    /**
     * Must independently commit a deployment-wide, nonrefundable attempt BEFORE returning. Bind key
     * ID to fingerprint permanently, reject material changes/aliases and enforce
     * MAX_ENCRYPTIONS_PER_KEY across instances/restarts, including failed encryptions. No
     * process-local implementation or default allow is supplied by production code.
     */
    interface EncryptionAdmission {
        void reserve(String keyId, String fingerprint);
    }

    record Context(
            UUID jobId,
            UUID subject,
            IdentityChallengePurpose purpose,
            UUID locator,
            Instant expiresAt) {
        Context {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(subject);
            Objects.requireNonNull(purpose);
            Objects.requireNonNull(locator);
            Objects.requireNonNull(expiresAt);
        }

        @Override
        public String toString() {
            return "IdentityDeliveryContext[REDACTED]";
        }
    }

    private record Key(BytesEncryptor crypto, String fingerprint) {
        @Override
        public String toString() {
            return "DeliveryKey[REDACTED]";
        }
    }

    IdentityDeliveryCrypto(
            String active,
            Map<String, byte[]> configured,
            Clock clock,
            EncryptionAdmission admission) {
        try {
            this.active = validId(active);
            this.clock = Objects.requireNonNull(clock);
            this.admission = Objects.requireNonNull(admission);
            if (configured == null || configured.isEmpty() || configured.size() > 4)
                throw unavailable();
            Map<String, Key> selected = new HashMap<>();
            Set<String> fingerprints = new HashSet<>();
            for (var entry : configured.entrySet()) {
                String id = validId(entry.getKey());
                byte[] material = entry.getValue();
                if (material == null || material.length != 32) throw unavailable();
                byte[] copy = material.clone();
                try {
                    String fingerprint =
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(copy));
                    if (!fingerprints.add(fingerprint)) throw unavailable();
                    selected.put(
                            id,
                            new Key(
                                    AesGcmBytesEncryptor.withSecretKey(
                                                    new SecretKeySpec(copy, "AES"))
                                            .build(),
                                    fingerprint));
                } finally {
                    Arrays.fill(copy, (byte) 0);
                }
            }
            if (!selected.containsKey(active)) throw unavailable();
            this.keys = Map.copyOf(selected);
        } catch (RuntimeException | GeneralSecurityException invalid) {
            throw unavailable();
        }
    }

    final class Permit {
        private final IdentityDeliveryCrypto owner = IdentityDeliveryCrypto.this;
        private final AtomicBoolean used = new AtomicBoolean();

        private Permit() {}

        @Override
        public String toString() {
            return "EncryptionPermit[REDACTED]";
        }
    }

    Permit prepare() {
        try {
            Key key = keys.get(active);
            admission.reserve(active, key.fingerprint());
            return new Permit();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    String seal(Context context, byte[] payload, Permit permit) {
        byte[] clear = null;
        try {
            requireLive(context);
            if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES)
                throw unavailable();
            byte[] prefix = prefix(active, context);
            clear = new byte[prefix.length + payload.length];
            System.arraycopy(prefix, 0, clear, 0, prefix.length);
            System.arraycopy(payload, 0, clear, prefix.length, payload.length);
            Key key = keys.get(active);
            if (permit == null || permit.owner != this || !permit.used.compareAndSet(false, true))
                throw unavailable();
            // Durable attempt was committed before entering business/subject locks.
            String envelope =
                    "v1."
                            + active
                            + "."
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(key.crypto().encrypt(clear));
            if (envelope.length() > MAX_ENVELOPE_BYTES) throw unavailable();
            return envelope;
        } catch (RuntimeException invalid) {
            throw unavailable();
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    byte[] open(Context expected, String envelope) {
        byte[] clear = null;
        try {
            requireLive(expected);
            if (envelope == null || envelope.length() > MAX_ENVELOPE_BYTES) throw unavailable();
            String[] parts = envelope.split("\\.", 4);
            if (parts.length != 3 || !parts[0].equals("v1")) throw unavailable();
            Key key = keys.get(validId(parts[1]));
            if (key == null || parts[2].isEmpty()) throw unavailable();
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            if (!Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ciphertext)
                    .equals(parts[2])) throw unavailable();
            clear = key.crypto().decrypt(ciphertext);
            byte[] prefix = prefix(parts[1], expected);
            if (clear.length <= prefix.length
                    || clear.length > prefix.length + MAX_PAYLOAD_BYTES
                    || !MessageDigest.isEqual(prefix, Arrays.copyOf(clear, prefix.length)))
                throw unavailable();
            return Arrays.copyOfRange(clear, prefix.length, clear.length);
        } catch (RuntimeException invalid) {
            throw unavailable();
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    private void requireLive(Context context) {
        if (context == null || !context.expiresAt().isAfter(clock.instant())) throw unavailable();
    }

    private static String validId(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,31}")) throw unavailable();
        return value;
    }

    private static byte[] prefix(String keyId, Context context) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeUTF(keyId);
            for (UUID id : List.of(context.jobId(), context.subject(), context.locator())) {
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
            // Names are stable across enum declaration reordering.
            out.writeUTF(context.purpose().name());
            out.writeLong(context.expiresAt().getEpochSecond());
            out.writeInt(context.expiresAt().getNano());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        // Never retain third-party exceptions, configuration, key IDs, payloads or ciphertext.
        return new IllegalStateException("IDENTITY_DELIVERY_UNAVAILABLE");
    }

    @Override
    public String toString() {
        return "IdentityDeliveryCrypto[REDACTED]";
    }
}
