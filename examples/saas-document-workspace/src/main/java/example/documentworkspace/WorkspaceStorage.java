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
public final class WorkspaceStorage {
    private static final JdbcRootQuery ALL =
            JdbcRootQuery.of("SELECT id FROM \"example.documentworkspace\".\"Document\"", Map.of());
    private final JdbcRepositoryAccess<Document, DocumentId> repository;
    private final RestrictionLedger limits;
    private final HttpServletRequest request;
    private final Clock clock;

    public WorkspaceStorage(
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

    public void requireOperation(String benefit, long units) {
        limits.requireOperation(account(), BenefitKey.of(benefit), Units.of(units));
    }

    public <T> T allocate(String benefit, java.util.function.Supplier<T> operation) {
        return limits.capacity(account(), BenefitKey.of(benefit), Units.of(1),
                () -> repository.count(ALL), operation);
    }

    public void release(Runnable operation) {
        limits.reduceCapacity(account(), () -> repository.count(ALL), () -> {
            operation.run();
            return Boolean.TRUE;
        });
    }

    public Document save(Document document) { return repository.save(document); }
    public Document load(DocumentId id) { return repository.single(read -> read.byId(id)); }
    public void delete(DocumentId id) { repository.delete(load(id)); }

    public <T> T consumeMonthly(String benefit, java.util.function.Supplier<T> operation) {
        var start = clock.instant().atZone(ZoneOffset.UTC).withDayOfMonth(1)
                .toLocalDate().atStartOfDay(ZoneOffset.UTC);
        var window = UsageWindow.of(start.toInstant(), start.plusMonths(1).toInstant());
        return limits.local(new RestrictionLedger.QuotaScope(account(), BenefitKey.of(benefit), window),
                Units.of(1), operation);
    }
}
