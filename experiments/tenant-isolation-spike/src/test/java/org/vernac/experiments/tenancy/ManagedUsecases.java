// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Handwritten shapes of the proposed generated guards; intentionally test-only. */
final class ManagedUsecases {
    static final class Trace { final List<String> events = new ArrayList<>(); }

    public static class Policy {
        private final InvoiceStore store;
        private final Trace trace;
        Policy(InvoiceStore store, Trace trace) { this.store=store; this.trace=trace; }
        @Transactional(propagation = Propagation.MANDATORY)
        public boolean allowsExport() {
            trace.events.add("policy");
            store.visibleCount(); // A real MANDATORY policy read, in the entry transaction.
            return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("INVOICE_EXPORT"));
        }
    }

    public static class Inner {
        private final TenantBoundary boundary;
        private final InvoiceStore store;
        private final Policy policy;
        private final Trace trace;
        Inner(TenantBoundary boundary, InvoiceStore store, Policy policy, Trace trace) {
            this.boundary=boundary; this.store=store; this.policy=policy; this.trace=trace;
        }
        @Transactional(propagation = Propagation.REQUIRED)
        public void execute(UUID id) {
            boundary.requireBound();
            if (!policy.allowsExport()) throw new AccessDeniedException("ACCESS_DENIED");
            trace.events.add("body");
            var invoice=store.byId(id); invoice.title="inner update"; store.save(invoice);
        }
    }

    public static class Outer {
        private final TenantBoundary boundary;
        private final InvoiceStore store;
        private final Inner inner;
        private final Trace trace;
        Outer(TenantBoundary boundary, InvoiceStore store, Inner inner, Trace trace) {
            this.boundary=boundary; this.store=store; this.inner=inner; this.trace=trace;
        }
        @Transactional(propagation = Propagation.REQUIRED)
        public void execute(UUID id, boolean callInner) {
            boundary.requireBound();
            var invoice=store.byId(id); invoice.title="outer update"; store.save(invoice);
            trace.events.add("outer");
            if (callInner) inner.execute(id);
        }
    }
}
