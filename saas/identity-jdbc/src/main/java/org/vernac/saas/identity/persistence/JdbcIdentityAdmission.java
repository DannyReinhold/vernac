// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.LIMITER_KEYS;
import static org.vernac.saas.identity.persistence.domain.IdentityAdmissionKind.*;

import org.jspecify.annotations.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.credentials.LoginAddress;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import javax.crypto.SecretKey;

/**
 * Private independently committed keyed admission; not an HTTP adapter or trusted-proxy resolver.
 */
@NullMarked
final class JdbcIdentityAdmission {
    enum Code {
        KEY_UNAVAILABLE,
        NOT_INITIALIZED,
        CONFIGURATION_MISMATCH,
        KEY_CAPACITY,
        STORAGE_UNAVAILABLE,
        KEY_COLLISION
    }

    static final class Failure extends IllegalStateException {
        private final Code code;

        Failure(Code code) {
            super("Identity admission unavailable: " + code);
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    enum Outcome {
        ADMITTED,
        GLOBAL_THROTTLED,
        SOURCE_THROTTLED,
        SUBJECT_THROTTLED,
        INVALID_INPUT
    }

    record Decision(Outcome outcome, int retryAfterSeconds) {
        boolean admitted() {
            return outcome == Outcome.ADMITTED;
        }
    }

    record Cleanup(int scanned, int removed, boolean cycleComplete) {}

    record Rule(long attempts, int seconds) {
        Rule {
            if (attempts <= 0 || seconds < 1 || seconds > 86400)
                throw new IllegalArgumentException("Invalid identity admission rule");
        }
    }

    private record Loaded(IdentityAdmissionKey row, boolean created) {}

    static final IdentityAdmissionId CONFIGURATION_ID =
            IdentityAdmissionId.of(UUID.fromString("61646d69-7373-496f-8000-000000000001"));
    private static final String TABLE =
            "\"org.vernac.saas.identity.persistence\".\"IdentityAdmissionKey\"";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcIdentityCapacity capacity;
    private final JdbcIdentityIngress global;
    private final IdentityAdmissionConfigurationRepository configurations;
    private final IdentityAdmissionKeyRepository keys;
    private final Clock clock;
    private final Map<IdentityAdmissionKind, Rule> rules;
    private final AdmissionDigests digests;
    private final IdentityAdmissionKeyLabel label;
    private final IdentityAdmissionDigest policyCheck;

    static Map<IdentityAdmissionKind, Rule> defaults() {
        return Map.of(
                LOGIN_SOURCE,
                new Rule(60, 900),
                LOGIN_KEY,
                new Rule(10, 900),
                MAIL_SOURCE,
                new Rule(20, 3600),
                MAIL_KEY,
                new Rule(3, 3600),
                CHALLENGE_SOURCE,
                new Rule(60, 900),
                CHALLENGE_LOCATOR,
                new Rule(10, 900));
    }

    JdbcIdentityAdmission(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            String keyLabel,
            SecretKey key,
            Map<IdentityAdmissionKind, Rule> rules) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        var copy = new EnumMap<IdentityAdmissionKind, Rule>(IdentityAdmissionKind.class);
        copy.putAll(rules);
        if (copy.size() != IdentityAdmissionKind.values().length
                || copy.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Incomplete identity admission policy");
        this.rules = Collections.unmodifiableMap(copy);
        label = IdentityAdmissionKeyLabel.of(keyLabel);
        digests = new AdmissionDigests(key);
        var canonical = new StringBuilder(label.label());
        for (var kind : IdentityAdmissionKind.values())
            canonical
                    .append('|')
                    .append(kind.name())
                    .append(':')
                    .append(copy.get(kind).attempts())
                    .append('/')
                    .append(copy.get(kind).seconds());
        policyCheck =
                digests.digest(
                        "CONFIGURATION", canonical.toString().getBytes(StandardCharsets.US_ASCII));
        capacity = new JdbcIdentityCapacity(jdbc);
        global = new JdbcIdentityIngress(jdbc, manager, clock);
        configurations = new JdbcIdentityAdmissionConfigurationRepository(jdbc);
        keys = new JdbcIdentityAdmissionKeyRepository(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    void initialize() {
        independently(
                () -> {
                    var existing = configurations.existing(CONFIGURATION_ID);
                    if (existing.isPresent()) {
                        check(existing.get());
                        return true;
                    }
                    if (!capacity.reserve(Map.of(LIMITER_KEYS, 1L)))
                        throw new Failure(Code.KEY_CAPACITY);
                    Instant now = clock.instant();
                    configurations.save(
                            IdentityAdmissionConfiguration.reconstitute(
                                    CONFIGURATION_ID,
                                    label,
                                    policyCheck,
                                    IdentityAdmissionSecond.of(now.getEpochSecond()),
                                    IdentityAdmissionCursor.of((UUID) null),
                                    now,
                                    now,
                                    0));
                    return true;
                });
    }

    Decision login(InetAddress source, @Nullable LoginAddress address) {
        return admit(LOGIN_SOURCE, LOGIN_KEY, source, address == null ? null : address.loginKey());
    }

    Decision mail(InetAddress source, @Nullable LoginAddress address) {
        return admit(MAIL_SOURCE, MAIL_KEY, source, address == null ? null : address.loginKey());
    }

    Decision challenge(InetAddress source, @Nullable String locator) {
        String valid =
                locator != null
                                && locator.length() == 36
                                && locator.matches(
                                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                        ? locator
                        : null;
        return admit(CHALLENGE_SOURCE, CHALLENGE_LOCATOR, source, valid);
    }

    private Decision admit(
            IdentityAdmissionKind sourceKind,
            IdentityAdmissionKind subjectKind,
            InetAddress source,
            @Nullable String subject) {
        Objects.requireNonNull(source, "Trusted identity admission source");
        noBusinessTransaction();
        var coarse = global.tryAcquire();
        if (!coarse.admitted())
            return new Decision(Outcome.GLOBAL_THROTTLED, coarse.retryAfterSeconds());
        var sourceDigest = digests.digest(sourceKind.name(), source.getAddress());
        var subjectDigest =
                subject == null
                        ? null
                        : digests.digest(
                                subjectKind.name(), subject.getBytes(StandardCharsets.US_ASCII));
        return independently(
                () -> {
                    var config = configuration();
                    Instant timestamp = clock.instant();
                    long now = config.observe(timestamp.getEpochSecond());
                    // Conservative pre-branch allocation keeps shared key pressure independent of
                    // prior traffic/identity existence.
                    if (!capacity.reserve(Map.of(LIMITER_KEYS, 2L)))
                        throw new Failure(Code.KEY_CAPACITY);
                    var sourceKey = load(sourceKind, sourceDigest, now, timestamp);
                    int created = sourceKey.created() ? 1 : 0;
                    boolean sourceAllowed = attempt(sourceKey.row(), now);
                    Decision decision;
                    if (!sourceAllowed)
                        decision =
                                new Decision(Outcome.SOURCE_THROTTLED, retry(sourceKey.row(), now));
                    else if (subjectDigest == null)
                        decision = new Decision(Outcome.INVALID_INPUT, 0);
                    else {
                        var subjectKey = load(subjectKind, subjectDigest, now, timestamp);
                        if (subjectKey.created()) created++;
                        decision =
                                attempt(subjectKey.row(), now)
                                        ? new Decision(Outcome.ADMITTED, 0)
                                        : new Decision(
                                                Outcome.SUBJECT_THROTTLED,
                                                retry(subjectKey.row(), now));
                    }
                    if (created < 2) capacity.release(Map.of(LIMITER_KEYS, (long) (2 - created)));
                    configurations.save(config);
                    return decision;
                });
    }

    private Loaded load(
            IdentityAdmissionKind kind,
            IdentityAdmissionDigest digest,
            long now,
            Instant timestamp) {
        var id = AdmissionDigests.id(digest);
        var found = keys.existing(id);
        var rule = rules.get(kind);
        long window = Math.floorDiv(now, rule.seconds());
        if (found.isPresent()) {
            var row = found.get();
            if (row.kind() != kind || !AdmissionDigests.equal(row.digest(), digest))
                throw new Failure(Code.KEY_COLLISION);
            validate(row, now);
            return new Loaded(row, false);
        }
        return new Loaded(
                IdentityAdmissionKey.reconstitute(
                        id,
                        kind,
                        digest,
                        IdentityAdmissionWindow.of(window),
                        IdentityAdmissionCount.of(0),
                        IdentityAdmissionSecond.of(end(window, rule.seconds())),
                        timestamp,
                        timestamp,
                        0),
                true);
    }

    private boolean attempt(IdentityAdmissionKey row, long now) {
        var rule = rules.get(row.kind());
        long window = Math.floorDiv(now, rule.seconds());
        boolean admitted = row.attempt(window, end(window, rule.seconds()), rule.attempts());
        if (admitted) keys.save(row);
        return admitted;
    }

    private void validate(IdentityAdmissionKey row, long now) {
        var rule = rules.get(row.kind());
        if (!row.id().equals(AdmissionDigests.id(row.digest()))
                || row.used().amount() > rule.attempts()
                || row.window().number() > Math.floorDiv(now, rule.seconds())
                || row.expires().number() != end(row.window().number(), rule.seconds()))
            throw new Failure(Code.STORAGE_UNAVAILABLE);
    }

    private static long end(long window, int seconds) {
        return Math.multiplyExact(Math.addExact(window, 1), seconds);
    }

    private static int retry(IdentityAdmissionKey row, long now) {
        return (int) Math.max(1, Math.min(86400, row.expires().number() - now));
    }

    private IdentityAdmissionConfiguration configuration() {
        var config =
                configurations
                        .existing(CONFIGURATION_ID)
                        .orElseThrow(() -> new Failure(Code.NOT_INITIALIZED));
        check(config);
        return config;
    }

    private void check(IdentityAdmissionConfiguration config) {
        if (!label.equals(config.keyLabel())
                || !AdmissionDigests.equal(policyCheck, config.policyCheck()))
            throw new Failure(Code.CONFIGURATION_MISMATCH);
    }

    Cleanup cleanup(int batchSize) {
        if (batchSize < 1 || batchSize > 500)
            throw new IllegalArgumentException("Invalid identity admission cleanup batch");
        return independently(
                () -> {
                    var config = configuration();
                    long now = config.observe(clock.instant().getEpochSecond());
                    var after = config.cursor().after();
                    var parameters = new HashMap<String, Object>();
                    parameters.put("limit", batchSize);
                    after.ifPresent(id -> parameters.put("after", id));
                    var ids =
                            jdbc.query(
                                    "SELECT \"id\" FROM "
                                            + TABLE
                                            + (after.isPresent() ? " WHERE \"id\">:after" : "")
                                            + " ORDER BY \"id\" LIMIT :limit",
                                    parameters,
                                    (rows, n) ->
                                            IdentityAdmissionId.of(rows.getObject(1, UUID.class)));
                    int removed = 0;
                    for (var id : ids) {
                        var row = keys.byId(id);
                        validate(row, now);
                        if (row.expires().number() <= now) {
                            keys.delete(row);
                            removed++;
                        }
                    }
                    if (removed > 0) capacity.release(Map.of(LIMITER_KEYS, (long) removed));
                    boolean complete = ids.size() < batchSize;
                    config.advanceCursor(
                            IdentityAdmissionCursor.of(complete ? null : ids.getLast().value()));
                    configurations.save(config);
                    return new Cleanup(ids.size(), removed, complete);
                });
    }

    private static void noBusinessTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())
            throw new IllegalTransactionStateException(
                    "Identity admission must precede business transactions");
    }

    private <T> T independently(Supplier<T> work) {
        noBusinessTransaction();
        try {
            return Objects.requireNonNull(
                    transaction.execute(status -> capacity.withBudgetLock(work)));
        } catch (DataAccessException
                | TransactionException
                | DomainValidationException
                | ArithmeticException unavailable) {
            throw new Failure(Code.STORAGE_UNAVAILABLE);
        }
    }
}
