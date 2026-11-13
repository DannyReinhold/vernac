// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Manual private worker. Explicit assembly/scheduler/provider wiring is a separate boundary. */
final class JdbcIdentityMailWorker {
    static final Duration LEASE = Duration.ofSeconds(45);
    static final IdentityMailSweepId DISPATCH =
            IdentityMailSweepId.of(UUID.fromString("6d61696c-7377-4565-8000-000000000001"));
    static final IdentityMailSweepId CLEANUP =
            IdentityMailSweepId.of(UUID.fromString("6d61696c-7377-4565-8000-000000000002"));
    static final IdentityMailSweepId CHALLENGES =
            IdentityMailSweepId.of(UUID.fromString("6d61696c-7377-4565-8000-000000000003"));
    static final IdentityMailSweepId REGISTRATIONS =
            IdentityMailSweepId.of(UUID.fromString("6d61696c-7377-4565-8000-000000000004"));
    private static final UUID START = new UUID(0, 0);

    enum Result {
        EMPTY,
        ACCEPTED,
        RETRY,
        OBSOLETE
    }

    /** Must bound transport attempts to <=30 seconds; no hidden retries or business writes. */
    interface Sender {
        boolean submit(IdentityMailContent content, IdentityDeliveryCrypto.Context context);
    }

    static final class Claim {
        final IdentityMailJob job;
        final UUID token;

        private Claim(IdentityMailJob job, UUID token) {
            this.job = job;
            this.token = token;
        }

        @Override
        public String toString() {
            return "IdentityMailClaim[REDACTED]";
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final IdentityDeliveryCrypto crypto;
    private final Sender sender;
    private final JdbcIdentityCapacity capacity;
    private final JdbcIdentityMailLedger ledger;
    private final IdentityMailJobRepository jobs;
    private final IdentityMailSweepRepository cursors;
    private final IdentityChallengeRepository challenges;
    private final IdentityRepository identities;
    private final IdentityLoginAddressRepository addresses;
    private final IdentityPendingRegistrationRepository registrations;

    JdbcIdentityMailWorker(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            IdentityDeliveryCrypto crypto,
            Sender sender) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.crypto = Objects.requireNonNull(crypto);
        this.sender = Objects.requireNonNull(sender);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
        capacity = new JdbcIdentityCapacity(jdbc);
        ledger = new JdbcIdentityMailLedger(jdbc);
        jobs = new JdbcIdentityMailJobRepository(jdbc);
        cursors = new JdbcIdentityMailSweepRepository(jdbc);
        challenges = new JdbcIdentityChallengeRepository(jdbc);
        identities = new JdbcIdentityRepository(jdbc);
        addresses = new JdbcIdentityLoginAddressRepository(jdbc);
        registrations = new JdbcIdentityPendingRegistrationRepository(jdbc);
    }

    void initialize() {
        write(
                () -> {
                    for (var id : List.of(DISPATCH, CLEANUP, CHALLENGES, REGISTRATIONS))
                        if (cursors.existing(id).isEmpty()) {
                            Instant now = clock.instant();
                            cursors.save(
                                    IdentityMailSweep.reconstitute(
                                            id, IdentityMailSweepPosition.of(START), now, now, 0));
                        }
                    return true;
                });
    }

    Result runOnce(int batch) {
        Claim claim = claim(batch);
        if (claim == null) return Result.EMPTY;
        boolean send =
                write(
                        () -> {
                            var row = owned(claim);
                            if (row == null) return false;
                            Instant now = clock.instant();
                            if (row.state() == IdentityMailState.CANCELLED || !live(row, now)) {
                                ledger.remove(row);
                                return false;
                            }
                            return now.isBefore(row.leaseUntil().instant());
                        });
        if (!send) return Result.OBSOLETE;
        boolean accepted = false;
        byte[] clear = null;
        try {
            clear = crypto.open(context(claim.job), claim.job.envelope().encoded());
            var content = IdentityMailContent.decode(clear, claim.job.challenge().value());
            boolean valid =
                    write(
                            () -> {
                                var row = owned(claim);
                                if (row == null
                                        || row.state() != IdentityMailState.LEASED
                                        || !clock.instant().isBefore(row.leaseUntil().instant())
                                        || !live(row, clock.instant())) return false;
                                var address = addresses.existing(row.subject());
                                var challenge = challenges.existing(row.challenge());
                                return address.isPresent()
                                        && challenge.isPresent()
                                        && address.get()
                                                .deliveryAddress()
                                                .encoded()
                                                .equals(content.recipient())
                                        && IdentityChallengeToken.parse(content.deliveryToken())
                                                .matches(challenge.get().verifier().encoded());
                            });
            if (valid) accepted = sender.submit(content, context(claim.job));
        } catch (RuntimeException sanitized) {
            accepted = false;
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
        settle(claim, accepted);
        return accepted ? Result.ACCEPTED : Result.RETRY;
    }

    Claim claim(int batch) {
        return write(
                () -> {
                    var cursor = cursor(DISPATCH);
                    var ids = scan(cursor, batch);
                    Instant now = clock.instant();
                    for (var id : ids) {
                        var row = jobs.byId(id);
                        cursor.advance(id.value());
                        boolean leased =
                                row.state() != IdentityMailState.READY
                                        && now.isBefore(row.leaseUntil().instant());
                        if (row.state() == IdentityMailState.CANCELLED
                                || !live(row, now)
                                || row.attempts().amount() >= 8) {
                            if (!leased) ledger.remove(row);
                            else if (row.state() != IdentityMailState.CANCELLED) {
                                row.cancel();
                                jobs.save(row);
                            }
                            continue;
                        }
                        if (leased
                                || now.isBefore(row.due().instant())
                                || !crypto.canRead(row.envelope().encoded())) continue;
                        UUID token = UUID.randomUUID();
                        row.claim(token, now.plus(LEASE));
                        jobs.save(row);
                        cursors.save(cursor);
                        return new Claim(row, token);
                    }
                    if (ids.isEmpty()) cursor.advance(START);
                    cursors.save(cursor);
                    return null;
                });
    }

    void settle(Claim claim, boolean accepted) {
        write(
                () -> {
                    var row = owned(claim);
                    if (row == null) return false;
                    Instant now = clock.instant();
                    if (accepted
                            || row.state() == IdentityMailState.CANCELLED
                            || !live(row, now)
                            || row.attempts().amount() >= 8) {
                        ledger.remove(row);
                        return true;
                    }
                    long seconds = Math.min(1800, 60L << (row.attempts().amount() - 1));
                    row.retry(now.plusSeconds(seconds));
                    jobs.save(row);
                    return true;
                });
    }

    int sweep(int batch) {
        return write(
                () -> {
                    var cursor = cursor(CLEANUP);
                    var ids = scan(cursor, batch);
                    int removed = 0;
                    Instant now = clock.instant();
                    for (var id : ids) {
                        var row = jobs.byId(id);
                        cursor.advance(id.value());
                        if (row.state() == IdentityMailState.CANCELLED
                                || !live(row, now)
                                || row.attempts().amount() >= 8) {
                            boolean leased =
                                    row.state() != IdentityMailState.READY
                                            && now.isBefore(row.leaseUntil().instant());
                            if (!leased) {
                                ledger.remove(row);
                                removed++;
                            } else if (row.state() != IdentityMailState.CANCELLED) {
                                row.cancel();
                                jobs.save(row);
                            }
                        }
                    }
                    if (ids.isEmpty()) cursor.advance(START);
                    cursors.save(cursor);
                    return removed;
                });
    }

    int expireChallenges(int batch) {
        return write(
                () -> {
                    var cursor = cursor(CHALLENGES);
                    Instant now = clock.instant();
                    var ids = scanIds("IdentityChallenge", cursor, batch);
                    int removed = 0;
                    for (var id : ids) {
                        var row = challenges.byId(IdentityChallengeId.of(id));
                        cursor.advance(id);
                        if (!now.isBefore(row.expiresAt().instant())) {
                            ledger.cancel(row.id());
                            challenges.delete(row);
                            capacity.release(
                                    Map.of(
                                            JdbcIdentityCapacity.Resource.ACTIONABLE_CHALLENGES,
                                            1L));
                            removed++;
                        }
                    }
                    if (ids.isEmpty()) cursor.advance(START);
                    cursors.save(cursor);
                    return removed;
                });
    }

    int expireRegistrations(int batch) {
        return write(
                () -> {
                    var cursor = cursor(REGISTRATIONS);
                    var result =
                            new JdbcIdentityRegistrations(jdbc, clock)
                                    .sweep(
                                            cursor.after().value().equals(START)
                                                    ? null
                                                    : cursor.after().value(),
                                            batch);
                    cursor.advance(result.after() == null ? START : result.after());
                    cursors.save(cursor);
                    return result.removed();
                });
    }

    private List<UUID> scanIds(String table, IdentityMailSweep cursor, int batch) {
        if (batch < 1
                || batch > 500
                || !(table.equals("IdentityMailJob") || table.equals("IdentityChallenge")))
            throw unavailable();
        return jdbc.query(
                "SELECT \"id\" FROM \"org.vernac.saas.identity.persistence\".\""
                        + table
                        + "\" WHERE \"id\" > :after ORDER BY \"id\" LIMIT :batch",
                Map.of("after", cursor.after().value(), "batch", batch),
                (rs, n) -> rs.getObject(1, UUID.class));
    }

    private IdentityMailJob owned(Claim claim) {
        var row = jobs.existing(claim.job.id());
        return row.isPresent()
                        && row.get().state() != IdentityMailState.READY
                        && row.get().leaseToken().value().equals(claim.token)
                ? row.get()
                : null;
    }

    private boolean live(IdentityMailJob row, Instant now) {
        if (!now.isBefore(row.expiresAt().instant())) return false;
        var challenge = challenges.existing(row.challenge());
        var identity = identities.existing(row.subject());
        if (challenge.isEmpty()
                || identity.isEmpty()
                || !challenge.get().subject().equals(row.subject())
                || challenge.get().purpose() != row.purpose()
                || !challenge.get().expiresAt().equals(row.expiresAt())) return false;
        if (row.purpose() == IdentityChallengePurpose.RECOVERY)
            return identity.get().status() == IdentityStatus.ACTIVE;
        if (identity.get().status() != IdentityStatus.PENDING_VERIFICATION) return false;
        var pending = registrations.existing(row.subject());
        return pending.isPresent() && now.isBefore(pending.get().deadline().instant());
    }

    private IdentityMailSweep cursor(IdentityMailSweepId id) {
        return cursors.existing(id).orElseThrow(JdbcIdentityMailWorker::unavailable);
    }

    private List<IdentityMailJobId> scan(IdentityMailSweep cursor, int batch) {
        return scanIds("IdentityMailJob", cursor, batch).stream()
                .map(IdentityMailJobId::of)
                .toList();
    }

    private <T> T write(Supplier<T> work) {
        if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null
                || org.vernac.runtime.tenancy.TenantContext.currentTenant().isPresent()
                || org.vernac.runtime.authorization.AuthorizationGuard.currentActor().isPresent()
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())
            throw unavailable();
        try {
            return transaction.execute(s -> capacity.withBudgetLock(work));
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private static IdentityDeliveryCrypto.Context context(IdentityMailJob row) {
        return new IdentityDeliveryCrypto.Context(
                row.id().value(),
                row.subject().value(),
                row.purpose(),
                row.challenge().value(),
                row.expiresAt().instant());
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("IDENTITY_DELIVERY_UNAVAILABLE");
    }

    @Override
    public String toString() {
        return "JdbcIdentityMailWorker[REDACTED]";
    }
}
