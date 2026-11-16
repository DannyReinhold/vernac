// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import example.documentworkspace.domain.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.vernac.runtime.jdbc.*;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.identity.security.IdentityAccountSelectionFilter;
import org.vernac.saas.restrictions.RestrictionLedger;
import org.vernac.saas.restrictions.domain.*;
import org.vernac.saas.subscriptions.domain.BenefitKey;

import java.time.*;
import java.util.*;

/**
 * Product facts and mutations inside generated usecase transactions; no guessed tenant/account
 * conversion.
 */
@Component
public final class DocumentActions {
    private static final JdbcRootQuery ALL =
            JdbcRootQuery.of("SELECT id FROM \"example.documentworkspace\".\"Document\"", Map.of());
    private final JdbcRepositoryAccess<Document, DocumentId> repository;
    private final RestrictionLedger limits;
    private final HttpServletRequest request;
    private final Clock clock;

    public DocumentActions(
            @Qualifier("jdbcDocumentRepository")
                    JdbcRepositoryExtensionSource<Document, DocumentId> source,
            RestrictionLedger limits,
            HttpServletRequest request,
            Clock clock) {
        this.repository = source.jdbcExtensions();
        this.limits = limits;
        this.request = request;
        this.clock = clock;
    }

    private AccountId account() {
        return IdentityAccountSelectionFilter.requireAccount(request);
    }

    public DocumentSummaries list() {
        account();
        return DocumentSummaries.of(
                repository.list(read -> read.roots(ALL)).stream()
                        .map(root -> DocumentSummary.of(root.id(), root.title(), root.text()))
                        .toList());
    }

    public DocumentId create(DocumentTitle title, DocumentText text) {
        var account = account();
        limits.requireOperation(
                account, BenefitKey.of("document-characters"), Units.of(text.text().length()));
        return limits.capacity(
                account,
                BenefitKey.of("documents"),
                Units.of(1),
                () -> repository.count(ALL),
                () -> repository.save(Document.create(title, text)).id());
    }

    public void delete(DocumentId id) {
        var account = account();
        limits.reduceCapacity(
                account,
                () -> repository.count(ALL),
                () -> {
                    repository.delete(repository.single(read -> read.byId(id)));
                    return Boolean.TRUE;
                });
    }

    public DocumentSummary export(DocumentId id) {
        var account = account();
        var start =
                clock.instant()
                        .atZone(ZoneOffset.UTC)
                        .withDayOfMonth(1)
                        .toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC);
        var window = UsageWindow.of(start.toInstant(), start.plusMonths(1).toInstant());
        var root =
                limits.local(
                        new RestrictionLedger.QuotaScope(
                                account, BenefitKey.of("monthly-exports"), window),
                        Units.of(1),
                        () -> repository.single(read -> read.byId(id)));
        return DocumentSummary.of(root.id(), root.title(), root.text());
    }
}
