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
import org.vernac.saas.mail.domain.*;

import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Durable invoice notifications through the ordinary shared MailProvider, separate from fiscal
 * issuance.
 */
public final class InvoiceMail {
    @FunctionalInterface
    public interface DownloadLinks {
        URI authenticatedDownload(InvoiceId invoice);
    }

    public record View(InvoiceNoticeStatus status, InvoiceNoticeReason reason, int attempts) {}

    private record Claim(
            InvoiceNoticeId id,
            UUID token,
            InvoiceContent content,
            InvoiceId invoice,
            MailCorrelationId correlation) {}

    private static final String S = "\"org.vernac.saas.invoicing\".";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate writer;
    private final InvoiceRepository invoices;
    private final InvoiceNoticeRepository notices;
    private final MailProvider provider;
    private final DownloadLinks links;
    private final InvoiceLedger.AccountAdministration authority;
    private final Clock clock;

    public InvoiceMail(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            MailProvider provider,
            DownloadLinks links,
            InvoiceLedger.AccountAdministration authority,
            Clock clock) {
        unscoped();
        this.jdbc = Objects.requireNonNull(jdbc);
        this.provider = Objects.requireNonNull(provider);
        this.links = Objects.requireNonNull(links);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
        if (!(manager instanceof DataSourceTransactionManager tx)
                || tx.getDataSource() != jdbc.getJdbcTemplate().getDataSource())
            throw fail(InvoiceFailure.Code.CONFIGURATION);
        writer = new TransactionTemplate(manager);
        writer.setTimeout(5);
        writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        invoices = new JdbcInvoiceRepository(jdbc);
        notices = new JdbcInvoiceNoticeRepository(jdbc);
    }

    public Optional<View> status(AccountId account, InvoiceId invoice) {
        administrator(account);
        return write(
                () -> {
                    owned(account, invoice);
                    return notices.forInvoice(invoice).map(InvoiceMail::view);
                });
    }

    public void retry(AccountId account, InvoiceId invoice) {
        administrator(account);
        write(
                () -> {
                    owned(account, invoice);
                    var found =
                            notices.forInvoice(invoice)
                                    .orElseThrow(() -> fail(InvoiceFailure.Code.INVALID_STATE));
                    lock(found.id());
                    var row = notices.byId(found.id());
                    row.retry(due(now()));
                    notices.save(row);
                    return Boolean.TRUE;
                });
    }

    /** One bounded external delivery step; acceptance is not proof of mailbox delivery. */
    public boolean processOne() {
        unscoped();
        var claim = claim();
        if (claim == null) return false;
        if (claim.token() == null) return true;
        if (claim.content().environment() == InvoiceEnvironment.LIVE
                && provider.mode() == MailProviderMode.SIMULATED) {
            finish(claim, null, InvoiceNoticeReason.CONFIGURATION);
            return true;
        }
        URI url;
        try {
            url = Objects.requireNonNull(links.authenticatedDownload(claim.invoice()));
            if (!"https".equals(url.getScheme())
                    || url.getHost() == null
                    || url.getUserInfo() != null
                    || url.getQuery() != null
                    || url.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalidConfiguration) {
            finish(claim, null, InvoiceNoticeReason.CONFIGURATION);
            return true;
        }
        String label =
                claim.content().kind() == InvoiceKind.INVOICE ? "Rechnung" : "Rechnungskorrektur";
        String message =
                "Ihre "
                        + label
                        + " "
                        + claim.content().number().text()
                        + " ist im Kundenbereich verfügbar.";
        var mail =
                OutboundMail.of(
                        claim.content().buyer().contact(),
                        MailSubject.of("Ihre " + label + " " + claim.content().number().text()),
                        MailBody.of(message + "\n\n" + url.toASCIIString()),
                        MailBody.of(
                                "<p>"
                                        + message
                                        + "</p><p><a href=\""
                                        + html(url.toASCIIString())
                                        + "\">Im Kundenbereich öffnen</a></p>"),
                        claim.correlation());
        try {
            finish(claim, Objects.requireNonNull(provider.submit(mail)), InvoiceNoticeReason.NONE);
        } catch (RuntimeException unknown) {
            finish(claim, null, InvoiceNoticeReason.OUTCOME_UNKNOWN);
        }
        return true;
    }

    private Claim claim() {
        return write(
                () -> {
                    var ids =
                            jdbc.queryForList(
                                    "SELECT \"id\" FROM "
                                            + S
                                            + "\"InvoiceNotice\" WHERE \"status\"='PENDING' AND"
                                            + " \"due\"<=:now AND (\"lease.token\" IS NULL OR"
                                            + " \"lease.until\"<=:now) ORDER BY \"due\",\"id\""
                                            + " LIMIT 1 FOR UPDATE SKIP LOCKED",
                                    Map.of("now", java.sql.Timestamp.from(now())),
                                    UUID.class);
                    if (ids.isEmpty()) return null;
                    var row = notices.byId(InvoiceNoticeId.of(ids.getFirst()));
                    var invoice = invoices.byId(row.invoice());
                    if (row.calls().value() >= 5) {
                        row.review(InvoiceNoticeReason.OUTCOME_UNKNOWN);
                        notices.save(row);
                        return new Claim(
                                row.id(), null, invoice.content(), invoice.id(), row.correlation());
                    }
                    var token = UUID.randomUUID();
                    row.claim(InvoiceWorkLease.of(token, now().plusSeconds(120)));
                    notices.save(row);
                    return new Claim(
                            row.id(), token, invoice.content(), invoice.id(), row.correlation());
                });
    }

    private void finish(Claim claim, MailSubmission submission, InvoiceNoticeReason failure) {
        write(
                () -> {
                    lock(claim.id());
                    var row = notices.byId(claim.id());
                    if (row.lease()
                            .filter(
                                    lease ->
                                            lease.token().equals(claim.token())
                                                    && now().isBefore(lease.until()))
                            .isEmpty()) return Boolean.FALSE;
                    if (failure == InvoiceNoticeReason.CONFIGURATION) row.review(failure);
                    else if (submission != null
                            && submission.acceptance() == MailAcceptance.ACCEPTED)
                        row.accept(provider.mode() == MailProviderMode.SIMULATED);
                    else if (submission != null
                            && submission.acceptance() == MailAcceptance.REJECTED)
                        row.review(InvoiceNoticeReason.PROVIDER_REJECTED);
                    else if (row.calls().value() >= 5)
                        row.review(InvoiceNoticeReason.OUTCOME_UNKNOWN);
                    else
                        row.defer(
                                due(
                                        now().plusSeconds(
                                                        submission == null
                                                                ? 30
                                                                : Math.max(
                                                                        1,
                                                                        submission
                                                                                .retryAfter()
                                                                                .seconds()))));
                    notices.save(row);
                    return Boolean.TRUE;
                });
    }

    private Invoice owned(AccountId account, InvoiceId id) {
        var row = invoices.byId(id);
        if (!row.account().equals(account)) throw fail(InvoiceFailure.Code.ACCESS_DENIED);
        return row;
    }

    private static View view(InvoiceNotice row) {
        return new View(row.status(), row.reason(), row.calls().value());
    }

    private void lock(InvoiceNoticeId id) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                Map.of("key", "vernac.invoice/notice/" + id.asString()));
    }

    private void administrator(AccountId account) {
        unscoped();
        try {
            authority.require(Objects.requireNonNull(account));
        } catch (RuntimeException denied) {
            throw fail(InvoiceFailure.Code.ACCESS_DENIED);
        }
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

    private static String html(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static InvoiceWorkDue due(Instant value) {
        return InvoiceWorkDue.of(value);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
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
        return "InvoiceMail[REDACTED]";
    }
}
