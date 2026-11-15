// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.invoicing.adapter.outbound.jdbc.*;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.payments.domain.PaymentProviderEnvironment;
import org.vernac.saas.payments.persistence.PaymentFlow;
import org.vernac.saas.payments.persistence.domain.PaymentOrderId;
import org.vernac.saas.subscriptions.domain.MinorUnits;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Explicit global invoice application boundary. Payment truth is retrieved, never accepted from an
 * HTTP payload.
 */
public final class InvoiceLedger {
    @FunctionalInterface
    public interface AccountAdministration {
        void require(AccountId account);
    }

    public record View(InvoiceId id, InvoiceContent content, InvoiceWorkStatus status) {
        @Override
        public String toString() {
            return "InvoiceView[REDACTED]";
        }
    }

    private record Claim(InvoiceId id, UUID token, InvoiceContent content) {}

    private static final String S = "\"org.vernac.saas.invoicing\".";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate writer;
    private final InvoiceRepository invoices;
    private final InvoiceSequenceRepository sequences;
    private final BillingProfileRepository profiles;
    private final IssuedInvoiceDocumentRepository documents;
    private final PaymentFlow payments;
    private final InvoiceSeller seller;
    private final InvoiceSeries series;
    private final InvoiceText description;
    private final InvoiceRenderer renderer;
    private final AccountAdministration authority;
    private final Clock clock;

    public InvoiceLedger(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            PaymentFlow payments,
            InvoiceSeller seller,
            InvoiceSeries series,
            InvoiceText description,
            InvoiceRenderer renderer,
            AccountAdministration authority,
            Clock clock) {
        unscoped();
        this.jdbc = Objects.requireNonNull(jdbc);
        this.payments = Objects.requireNonNull(payments);
        this.seller = Objects.requireNonNull(seller);
        this.series = Objects.requireNonNull(series);
        this.description = Objects.requireNonNull(description);
        this.renderer = Objects.requireNonNull(renderer);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
        if (series.text().startsWith("TEST")
                || !(manager instanceof DataSourceTransactionManager tx)
                || tx.getDataSource() != jdbc.getJdbcTemplate().getDataSource())
            throw fail(InvoiceFailure.Code.CONFIGURATION);
        writer = new TransactionTemplate(manager);
        writer.setTimeout(5);
        writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        invoices = new JdbcInvoiceRepository(jdbc);
        sequences = new JdbcInvoiceSequenceRepository(jdbc);
        profiles = new JdbcBillingProfileRepository(jdbc);
        documents = new JdbcIssuedInvoiceDocumentRepository(jdbc);
    }

    public void billingProfile(AccountId account, InvoiceBuyer buyer) {
        administrator(account);
        Objects.requireNonNull(buyer);
        write(
                () -> {
                    lock("account/" + account.asString());
                    var existing = profile(account);
                    if (existing.isPresent()) {
                        var row = existing.get();
                        if (!row.buyer().customer().equals(buyer.customer()))
                            throw fail(InvoiceFailure.Code.CONFLICT);
                        row.replace(buyer);
                        profiles.save(row);
                    } else {
                        var time = now();
                        profiles.save(BillingProfile.reconstitute(account, buyer, time, time, 0));
                    }
                    return Boolean.TRUE;
                });
    }

    public Optional<InvoiceBuyer> billingProfile(AccountId account) {
        administrator(account);
        return write(() -> profile(account).map(BillingProfile::buyer));
    }

    /**
     * Idempotently snapshots one original invoice and newly verified cumulative refund corrections.
     */
    public InvoiceId synchronize(PaymentOrderId payment) {
        unscoped();
        var proof = payments.verifiedPayment(payment);
        return write(
                () -> {
                    lock("payment/" + payment.asString());
                    var original =
                            invoices.forSource(source(payment, 0))
                                    .orElseGet(
                                            () -> {
                                                var buyer =
                                                        profile(proof.account())
                                                                .orElseThrow(
                                                                        () ->
                                                                                fail(
                                                                                        InvoiceFailure
                                                                                                .Code
                                                                                                .BILLING_PROFILE_REQUIRED))
                                                                .buyer();
                                                var environment =
                                                        proof.environment()
                                                                        == PaymentProviderEnvironment
                                                                                .PRODUCTION
                                                                ? InvoiceEnvironment.LIVE
                                                                : InvoiceEnvironment.TEST;
                                                var issued = issueDate();
                                                var number = nextNumber(environment, issued);
                                                var content =
                                                        InvoiceContent.of(
                                                                number,
                                                                issued,
                                                                InvoiceKind.INVOICE,
                                                                environment,
                                                                seller,
                                                                buyer,
                                                                description,
                                                                proof.terms(),
                                                                proof.period(),
                                                                proof.postedAt(),
                                                                DomesticTax.gross(
                                                                        proof.terms().price(),
                                                                        seller.treatment()),
                                                                null);
                                                return invoices.save(
                                                        Invoice.create(
                                                                proof.account(),
                                                                payment,
                                                                source(payment, 0),
                                                                number,
                                                                content,
                                                                MinorUnits.of(0),
                                                                InvoiceWorkStatus.PENDING,
                                                                due(now()),
                                                                null));
                                            });
                    if (!original.account().equals(proof.account())
                            || !original.content().terms().equals(proof.terms())
                            || !original.content().period().equals(proof.period())
                            || !original.content().paymentPostedAt().equals(proof.postedAt()))
                        throw fail(InvoiceFailure.Code.CONFLICT);
                    var latest =
                            jdbc.queryForList(
                                    "SELECT \"id\" FROM "
                                            + S
                                            + "\"Invoice\" WHERE \"payment\"=:payment ORDER BY"
                                            + " \"cumulativeRefund\" DESC LIMIT 1",
                                    Map.of("payment", payment.value()),
                                    UUID.class);
                    var previous = invoices.byId(InvoiceId.of(latest.getFirst()));
                    if (proof.refunded().value() < previous.cumulativeRefund().value())
                        throw fail(InvoiceFailure.Code.UNAVAILABLE);
                    if (proof.refunded().value() > previous.cumulativeRefund().value()) {
                        var content = original.content();
                        var issued = issueDate();
                        var number = nextNumber(content.environment(), issued);
                        var correction =
                                InvoiceContent.of(
                                        number,
                                        issued,
                                        InvoiceKind.CORRECTION,
                                        content.environment(),
                                        content.seller(),
                                        content.buyer(),
                                        content.description(),
                                        content.terms(),
                                        content.period(),
                                        content.paymentPostedAt(),
                                        DomesticTax.credit(
                                                content.amounts(),
                                                previous.cumulativeRefund(),
                                                proof.refunded()),
                                        InvoiceReference.of(content.number(), content.issued()));
                        invoices.save(
                                Invoice.create(
                                        proof.account(),
                                        payment,
                                        source(payment, proof.refunded().value()),
                                        number,
                                        correction,
                                        proof.refunded(),
                                        InvoiceWorkStatus.PENDING,
                                        due(now()),
                                        null));
                    }
                    return original.id();
                });
    }

    public View invoice(AccountId account, InvoiceId id) {
        administrator(account);
        return write(() -> view(owned(account, id)));
    }

    public List<View> invoices(AccountId account, Optional<InvoiceId> after, int limit) {
        administrator(account);
        Objects.requireNonNull(after);
        if (limit < 1 || limit > 100) throw fail(InvoiceFailure.Code.INVALID_STATE);
        return write(
                () -> {
                    var parameters = new HashMap<String, Object>();
                    parameters.put("account", account.value());
                    parameters.put("limit", limit);
                    after.ifPresent(id -> parameters.put("after", id.value()));
                    return jdbc
                            .queryForList(
                                    "SELECT \"id\" FROM "
                                            + S
                                            + "\"Invoice\" WHERE \"account\"=:account"
                                            + (after.isPresent() ? " AND \"id\">:after" : "")
                                            + " ORDER BY \"id\" LIMIT :limit",
                                    parameters,
                                    UUID.class)
                            .stream()
                            .map(id -> view(invoices.byId(InvoiceId.of(id))))
                            .toList();
                });
    }

    public void retryRendering(AccountId account, InvoiceId id) {
        administrator(account);
        write(
                () -> {
                    lock("document/" + id.asString());
                    var row = owned(account, id);
                    row.retry(due(now()));
                    invoices.save(row);
                    return Boolean.TRUE;
                });
    }

    public RenderedInvoice download(AccountId account, InvoiceId id) {
        administrator(account);
        return write(
                () -> {
                    var row = owned(account, id);
                    if (row.status() != InvoiceWorkStatus.READY)
                        throw fail(InvoiceFailure.Code.INVALID_STATE);
                    return documents
                            .forInvoice(id)
                            .orElseThrow(() -> fail(InvoiceFailure.Code.INVALID_STATE))
                            .document();
                });
    }

    /**
     * Rendering never holds the fiscal writer open. Its acknowledgement and immutable document
     * insert are atomic.
     */
    public boolean renderOne() {
        unscoped();
        var claim =
                write(
                        () -> {
                            var ids =
                                    jdbc.queryForList(
                                            "SELECT \"id\" FROM "
                                                    + S
                                                    + "\"Invoice\" WHERE \"status\"='PENDING' AND"
                                                    + " \"due\"<=:now AND (\"lease.token\" IS NULL"
                                                    + " OR \"lease.until\"<=:now) ORDER BY"
                                                    + " \"due\",\"id\" LIMIT 1 FOR UPDATE SKIP"
                                                    + " LOCKED",
                                            Map.of("now", java.sql.Timestamp.from(now())),
                                            UUID.class);
                            if (ids.isEmpty()) return null;
                            var row = invoices.byId(InvoiceId.of(ids.getFirst()));
                            var token = UUID.randomUUID();
                            row.claim(InvoiceWorkLease.of(token, now().plusSeconds(120)));
                            invoices.save(row);
                            return new Claim(row.id(), token, row.content());
                        });
        if (claim == null) return false;
        RenderedInvoice rendered;
        try {
            rendered = Objects.requireNonNull(renderer.render(claim.content()));
        } catch (RuntimeException invalidDocument) {
            acknowledge(claim, null);
            return true;
        }
        acknowledge(claim, rendered);
        return true;
    }

    private void acknowledge(Claim claim, RenderedInvoice rendered) {
        write(
                () -> {
                    lock("document/" + claim.id().asString());
                    var row = invoices.byId(claim.id());
                    if (row.lease()
                            .filter(
                                    lease ->
                                            lease.token().equals(claim.token())
                                                    && now().isBefore(lease.until()))
                            .isEmpty()) return Boolean.FALSE;
                    if (rendered == null) row.review();
                    else {
                        documents.save(IssuedInvoiceDocument.create(row.id(), rendered));
                        row.ready();
                    }
                    invoices.save(row);
                    return Boolean.TRUE;
                });
    }

    private InvoiceNumber nextNumber(InvoiceEnvironment environment, InvoiceIssueDate issued) {
        var key =
                InvoiceSequenceKey.of(
                        environment.name() + "/" + series.text() + "/" + issued.value().getYear());
        lock("sequence/" + key.text());
        var sequence =
                sequences
                        .forKey(key)
                        .orElseGet(() -> InvoiceSequence.create(key, InvoiceSequenceValue.of(0)));
        long next = sequence.next().value();
        sequences.save(sequence);
        return InvoiceNumber.of(
                (environment == InvoiceEnvironment.TEST ? "TEST-" : "")
                        + series.text()
                        + "-"
                        + issued.value().getYear()
                        + "-"
                        + String.format(Locale.ROOT, "%08d", next));
    }

    private Optional<BillingProfile> profile(AccountId id) {
        try {
            return Optional.of(profiles.byId(id));
        } catch (org.vernac.runtime.AggregateNotFoundException absent) {
            return Optional.empty();
        }
    }

    private Invoice owned(AccountId account, InvoiceId id) {
        var row = invoices.byId(id);
        if (!row.account().equals(account)) throw fail(InvoiceFailure.Code.ACCESS_DENIED);
        return row;
    }

    private static View view(Invoice row) {
        return new View(row.id(), row.content(), row.status());
    }

    private static InvoiceSourceKey source(PaymentOrderId id, long cumulative) {
        return InvoiceSourceKey.of(id.asString() + "/" + cumulative);
    }

    private InvoiceIssueDate issueDate() {
        return InvoiceIssueDate.of(LocalDate.ofInstant(now(), ZoneId.of("Europe/Berlin")));
    }

    private static InvoiceWorkDue due(Instant time) {
        return InvoiceWorkDue.of(time);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void administrator(AccountId account) {
        unscoped();
        try {
            authority.require(Objects.requireNonNull(account));
        } catch (RuntimeException denied) {
            throw fail(InvoiceFailure.Code.ACCESS_DENIED);
        }
    }

    private void lock(String key) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                Map.of("key", "vernac.invoice/" + key));
    }

    private <T> T write(Supplier<T> action) {
        unscoped();
        try {
            return writer.execute(status -> action.get());
        } catch (InvoiceFailure safe) {
            throw safe;
        } catch (RuntimeException unavailable) {
            throw fail(InvoiceFailure.Code.UNAVAILABLE);
        }
    }

    private static void unscoped() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()
                || TenantContext.currentTenant().isPresent()
                || AuthorizationGuard.currentActor().isPresent())
            throw fail(InvoiceFailure.Code.CONFIGURATION);
    }

    private static InvoiceFailure fail(InvoiceFailure.Code code) {
        return new InvoiceFailure(code);
    }

    @Override
    public String toString() {
        return "InvoiceLedger[REDACTED]";
    }
}
