// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.restrictions;

import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;
import org.vernac.saas.restrictions.domain.*;
import org.vernac.saas.restrictions.adapter.outbound.jdbc.*;

/** Explicit trusted metering boundary; does not authenticate accounts or infer product permissions. */
public final class RestrictionLedger {
    @FunctionalInterface public interface AllowanceFacts { Allowance current(AccountId verifiedAccount, BenefitKey benefit); }
    public record QuotaScope(AccountId account, BenefitKey benefit, UsageWindow window) {
        public QuotaScope { Objects.requireNonNull(account); Objects.requireNonNull(benefit); Objects.requireNonNull(window); }
        @Override public String toString() { return "QuotaScope[REDACTED]"; }
    }
    public record Reservation(ReservationId id, ReservationStatus status, Units amount, boolean created) {
        @Override public String toString() { return "Reservation[REDACTED]"; }
    }
    public enum ExternalState { CONFIRMED, IN_DOUBT, ALREADY_RECORDED }
    public record ExternalResult<T>(ExternalState state, ReservationId reservation, Optional<T> value) {
        @Override public String toString() { return "ExternalResult[" + state + "]"; }
    }
    private static final Object ACCOUNT_PIN = new Object();
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate writer;
    private final QuotaBudgetRepository budgets;
    private final QuotaReservationRepository reservations;
    private final AllowanceFacts facts;
    private final Clock clock;

    public RestrictionLedger(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, AllowanceFacts facts, Clock clock) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw fail(RestrictionFailure.Code.CONFIGURATION);
        this.jdbc = Objects.requireNonNull(jdbc); this.facts = Objects.requireNonNull(facts); this.clock = Objects.requireNonNull(clock);
        if (!(manager instanceof DataSourceTransactionManager tx) || tx.getDataSource() != jdbc.getJdbcTemplate().getDataSource()) throw fail(RestrictionFailure.Code.CONFIGURATION);
        writer = new TransactionTemplate(manager); writer.setTimeout(5); writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        budgets = new JdbcQuotaBudgetRepository(jdbc); reservations = new JdbcQuotaReservationRepository(jdbc);
        try {
            var restricted = jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT has_schema_privilege(current_user,:schema,'CREATE') FROM pg_roles WHERE rolname=current_user",
                    Map.of("schema", "org.vernac.saas.restrictions"), Boolean.class);
            if (!Boolean.TRUE.equals(restricted)) throw fail(RestrictionFailure.Code.CONFIGURATION);
            jdbc.queryForList("SELECT 1 FROM \"org.vernac.saas.restrictions\".\"QuotaBudget\" LIMIT 0", Map.of());
            jdbc.queryForList("SELECT 1 FROM \"org.vernac.saas.restrictions\".\"QuotaReservation\" LIMIT 0", Map.of());
        } catch (RuntimeException invalid) { throw fail(RestrictionFailure.Code.CONFIGURATION); }
    }
    /** Side-effect-free operation-size check. A zero amount does not prove authorization. */
    public void requireOperation(AccountId account, BenefitKey benefit, Units actual) {
        MeteringRules.requireRoom(0, 0, actual.value(), allowance(account, benefit).maximum());
    }
    /** Exactly one invocation; quota and local business writes join the same physical transaction. */
    public <T> T local(QuotaScope scope, Units amount, Supplier<T> action) {
        Objects.requireNonNull(amount); Objects.requireNonNull(action);
        return inWriter(scope.account(), () -> {
            currentWindow(scope.window()); var budget = budget(scope); budget.consume(amount, allowance(scope.account(), scope.benefit())); budgets.save(budget);
            return callback(action);
        });
    }
    public void local(QuotaScope scope, Units amount, Runnable action) {
        local(scope, amount, () -> { action.run(); return Boolean.TRUE; });
    }
    /** Facts come from the actual product repository inside this writer; never infer a count from a model name. */
    public <T> T capacity(AccountId account, BenefitKey benefit, Units allocation, LongSupplier currentUsage, Supplier<T> action) {
        Objects.requireNonNull(allocation); Objects.requireNonNull(currentUsage); Objects.requireNonNull(action);
        return inWriter(account, () -> {
            long before = count(currentUsage); var maximum = allowance(account, benefit);
            MeteringRules.requireRoom(before, 0, allocation.value(), maximum.maximum());
            T result = callback(action);
            MeteringRules.requireRoom(count(currentUsage), 0, 0, maximum.maximum());
            return result;
        });
    }
    /** Deletion/deactivation remains possible after downgrade, but this path may not increase occupancy. */
    public <T> T reduceCapacity(AccountId account, LongSupplier currentUsage, Supplier<T> action) {
        return inWriter(account, () -> {
            long before = count(currentUsage); T result = callback(action);
            if (count(currentUsage) > before) throw fail(RestrictionFailure.Code.INVALID_FACT);
            return result;
        });
    }
    /** Durable reservation before external work. Logical operation identity is account-wide across periods. */
    public Reservation reserve(QuotaScope scope, UsageOperationKey operation, Units amount) {
        independent(); Objects.requireNonNull(operation); Objects.requireNonNull(amount);
        return inWriter(scope.account(), () -> {
            var key = MeteringKeys.reservation(scope.account(), operation); var found = reservations.forKey(key);
            if (found.isPresent()) {
                var row = found.get();
                if (!row.account().equals(scope.account()) || !row.benefit().equals(scope.benefit()) || !row.window().equals(scope.window()) || !row.amount().equals(amount))
                    throw fail(RestrictionFailure.Code.OPERATION_CONFLICT);
                return view(row, false);
            }
            currentWindow(scope.window()); var budget = budget(scope);
            budget.reserve(amount, allowance(scope.account(), scope.benefit())); budget = budgets.save(budget);
            return view(reservations.save(QuotaReservation.create(key, budget.id(), scope.account(), scope.benefit(), scope.window(), amount, ReservationStatus.RESERVED)), true);
        });
    }
    /** Absolute adjustment is allowed only before any durable dispatch authorization. */
    public Reservation resizeBeforeDispatch(AccountId account, UsageOperationKey operation, Units amount) {
        independent(); Objects.requireNonNull(amount);
        return inWriter(account, () -> {
            var row = reservations.forKey(MeteringKeys.reservation(account, operation)).orElseThrow(() -> fail(RestrictionFailure.Code.OPERATION_CONFLICT));
            if (row.status() != ReservationStatus.RESERVED) throw fail(RestrictionFailure.Code.INVALID_TRANSITION);
            currentWindow(row.window());
            var budget = budgets.byId(row.budget());
            long before = row.amount().value(), after = amount.value();
            if (after > before) budget.reserve(Units.of(after - before), allowance(account, row.benefit()));
            else budget.release(Units.of(before - after));
            row.resize(amount); budgets.save(budget); return view(reservations.save(row), false);
        });
    }
    /** A true return grants the one dispatch attempt; a crash afterwards requires reconciliation. */
    public boolean beginDispatch(AccountId account, UsageOperationKey operation) {
        independent();
        return inWriter(account, () -> {
            var row = reservations.forKey(MeteringKeys.reservation(account, operation)).orElseThrow(() -> fail(RestrictionFailure.Code.OPERATION_CONFLICT));
            if (row.status() != ReservationStatus.RESERVED) return false;
            currentWindow(row.window());
            row.dispatch(); reservations.save(row); return true;
        });
    }
    /** Bounded internal reconciliation inventory. Caller must authorize the account/operator surface. */
    public List<Reservation> pending(AccountId account, Optional<ReservationId> after, int limit) {
        independent(); Objects.requireNonNull(after);
        if (limit < 1 || limit > 100) throw fail(RestrictionFailure.Code.CONFIGURATION);
        return inWriter(account, () -> {
            var parameters = new HashMap<String, Object>(); parameters.put("account", account.value()); parameters.put("limit", limit);
            after.ifPresent(id -> parameters.put("after", id.value()));
            String cursor = after.isPresent() ? " AND \"id\">:after" : "";
            return jdbc.queryForList("SELECT \"id\" FROM \"org.vernac.saas.restrictions\".\"QuotaReservation\" WHERE \"account\"=:account AND \"status\" IN ('RESERVED','IN_DOUBT')" + cursor + " ORDER BY \"id\" LIMIT :limit", parameters, UUID.class)
                    .stream().map(id -> view(reservations.byId(ReservationId.of(id)), false)).toList();
        });
    }
    public Reservation confirm(AccountId account, UsageOperationKey operation) { return resolve(account, operation, true); }
    /** Call only with authoritative evidence that no external effect occurred; a timeout/expiry is not evidence. */
    public Reservation releaseKnownUnused(AccountId account, UsageOperationKey operation) { return resolve(account, operation, false); }
    private Reservation resolve(AccountId account, UsageOperationKey operation, boolean confirm) {
        independent();
        return inWriter(account, () -> {
            var row = reservations.forKey(MeteringKeys.reservation(account, operation)).orElseThrow(() -> fail(RestrictionFailure.Code.OPERATION_CONFLICT));
            ReservationStatus desired = confirm ? ReservationStatus.CONFIRMED : ReservationStatus.RELEASED;
            if (row.status() == desired) return view(row, false);
            if (row.status() != ReservationStatus.RESERVED && row.status() != ReservationStatus.IN_DOUBT) throw fail(RestrictionFailure.Code.INVALID_TRANSITION);
            var budget = budgets.byId(row.budget());
            if (confirm) { budget.confirm(row.amount()); row.confirm(); }
            else { budget.release(row.amount()); row.release(); }
            budgets.save(budget); return view(reservations.save(row), false);
        });
    }
    /** No hidden retry. Any callback failure leaves the durable reservation in doubt for reconciliation. */
    public <T> ExternalResult<T> external(QuotaScope scope, UsageOperationKey operation, Units amount, Supplier<T> action) {
        Objects.requireNonNull(action);
        Reservation reservation = reserve(scope, operation, amount);
        if (!reservation.created()) return new ExternalResult<>(ExternalState.ALREADY_RECORDED, reservation.id(), Optional.empty());
        try {
            if (!beginDispatch(scope.account(), operation)) return new ExternalResult<>(ExternalState.ALREADY_RECORDED, reservation.id(), Optional.empty());
        } catch (RestrictionFailure uncertainDispatch) { return new ExternalResult<>(ExternalState.IN_DOUBT, reservation.id(), Optional.empty()); }
        T result;
        try { result = action.get(); }
        catch (RuntimeException unknown) { return new ExternalResult<>(ExternalState.IN_DOUBT, reservation.id(), Optional.empty()); }
        try { confirm(scope.account(), operation); }
        catch (RestrictionFailure unknown) { return new ExternalResult<>(ExternalState.IN_DOUBT, reservation.id(), Optional.empty()); }
        return new ExternalResult<>(ExternalState.CONFIRMED, reservation.id(), Optional.ofNullable(result));
    }
    private QuotaBudget budget(QuotaScope scope) {
        var key = MeteringKeys.budget(scope.account(), scope.benefit(), scope.window());
        return budgets.forKey(key).orElseGet(() -> QuotaBudget.create(key, scope.account(), scope.benefit(), scope.window(), Units.of(0), Units.of(0)));
    }
    private Allowance allowance(AccountId account, BenefitKey benefit) {
        Objects.requireNonNull(account); Objects.requireNonNull(benefit);
        try { return Objects.requireNonNull(facts.current(account, benefit)); }
        catch (RuntimeException unavailable) { throw fail(RestrictionFailure.Code.UNAVAILABLE); }
    }
    private void currentWindow(UsageWindow window) {
        Instant now = clock.instant();
        if (now.isBefore(window.start()) || !now.isBefore(window.end())) throw fail(RestrictionFailure.Code.INVALID_WINDOW);
    }
    private static long count(LongSupplier source) {
        long value = source.getAsLong(); if (value < 0) throw fail(RestrictionFailure.Code.INVALID_FACT); return value;
    }
    private <T> T inWriter(AccountId account, Supplier<T> action) {
        Objects.requireNonNull(account);
        try { return writer.execute(status -> { pin(account); jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", "vernac.restrictions/" + account.asString())); return action.get(); }); }
        catch (CallbackFailure application) { throw application.original; }
        catch (RestrictionFailure safe) { throw safe; }
        catch (RuntimeException unavailable) { throw fail(RestrictionFailure.Code.UNAVAILABLE); }
    }
    private static void pin(AccountId account) {
        var previous = TransactionSynchronizationManager.getResource(ACCOUNT_PIN);
        if (previous != null) { if (!previous.equals(account)) throw fail(RestrictionFailure.Code.CONFIGURATION); return; }
        TransactionSynchronizationManager.bindResource(ACCOUNT_PIN, account);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void suspend() { TransactionSynchronizationManager.unbindResource(ACCOUNT_PIN); }
            @Override public void resume() { TransactionSynchronizationManager.bindResource(ACCOUNT_PIN, account); }
            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(ACCOUNT_PIN); }
        });
    }
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) throw fail(RestrictionFailure.Code.CONFIGURATION);
    }
    private static <T> T callback(Supplier<T> action) {
        try { return action.get(); } catch (RuntimeException application) { throw new CallbackFailure(application); }
    }
    private static final class CallbackFailure extends RuntimeException {
        final RuntimeException original;
        CallbackFailure(RuntimeException original) { super(null, null, false, false); this.original = original; }
    }
    private static Reservation view(QuotaReservation row, boolean created) { return new Reservation(row.id(), row.status(), row.amount(), created); }
    private static RestrictionFailure fail(RestrictionFailure.Code code) { return new RestrictionFailure(code); }
    @Override public String toString() { return "RestrictionLedger[REDACTED]"; }
}
