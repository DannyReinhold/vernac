// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.events;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.*;
import org.vernac.runtime.tenancy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.runtime.tenancy.TenancyException.Code.*;

class EventOriginTest {
    private final TransactionTemplate transaction = new TransactionTemplate(new EventContextTest.Manager());
    private final TenantId a = TenantId.of(UUID.randomUUID());
    private final TenantId b = TenantId.of(UUID.randomUUID());

    @Test void captureRequiresASynchronizedTransaction() {
        assertEquals(TENANT_TRANSACTION_REQUIRED, assertThrows(TenancyException.class, EventOrigin::capture).code());
    }

    @Test void capturesGlobalOrNominalTenantWithoutExposingIdentityInDiagnostics() {
        transaction.executeWithoutResult(status -> {
            var global = EventOrigin.capture();
            assertTrue(global.isGlobal()); assertTrue(global.tenant().isEmpty()); global.requireGlobal();
            assertEquals("EventOrigin[GLOBAL]", global.toString());
        });
        try (var selection = TenantContext.openVerified(a)) {
            transaction.executeWithoutResult(status -> {
                var origin = EventOrigin.capture();
                assertFalse(origin.isGlobal()); assertEquals(Optional.of(a), origin.tenant());
                assertEquals(Optional.of(a), TenantContext.transactionTenant());
                assertEquals(origin, EventOrigin.capture()); assertEquals(origin.hashCode(), EventOrigin.capture().hashCode());
                assertEquals("EventOrigin[TENANT]", origin.toString());
                assertEquals(TENANT_EVENT_DELIVERY_UNSUPPORTED, assertThrows(TenancyException.class, origin::requireGlobal).code());
            });
        }
        assertTrue(TenantContext.currentTenant().isEmpty()); assertTrue(TenantContext.transactionTenant().isEmpty());
    }

    @Test void closingSelectionDoesNotTurnAPinnedTransactionIntoGlobalExecution() {
        transaction.executeWithoutResult(status -> {
            EventOrigin origin;
            try (var selection = TenantContext.openVerified(a)) { origin = EventOrigin.capture(); }
            assertEquals(TENANT_CONTEXT_MISMATCH, assertThrows(TenancyException.class, EventOrigin::capture).code());
            assertEquals(TENANT_CONTEXT_MISMATCH, assertThrows(TenancyException.class, origin::requireCurrent).code());
        });
        assertTrue(TenantContext.transactionTenant().isEmpty());
    }

    @Test void savedOriginCannotAuthorizeAnotherSelectionOrAGlobalTransaction() {
        EventOrigin origin;
        try (var selection = TenantContext.openVerified(a)) { origin = transaction.execute(status -> EventOrigin.capture()); }
        assertNotNull(origin);
        transaction.executeWithoutResult(status -> assertEquals(TENANT_CONTEXT_MISMATCH,
                assertThrows(TenancyException.class, origin::requireCurrent).code()));
        try (var selection = TenantContext.openVerified(b)) {
            transaction.executeWithoutResult(status -> assertEquals(TENANT_CONTEXT_MISMATCH,
                    assertThrows(TenancyException.class, origin::requireCurrent).code()));
        }
    }

    @Test void legacyAdapterBridgesAcceptGlobalAndRejectTenantBeforeInvokingOldMethods() {
        var calls = new ArrayList<String>();
        var event = new EventContextTest.Ping();
        EventStorage legacy = new EventStorage() {
            public String eventType() { return "test.Ping"; }
            public String listenerId() { return "@storage:Ping"; }
            public Class<? extends DomainEvent> eventClass() { return event.getClass(); }
            public void persist(DomainEvent value, List<String> recipients) { calls.add("persist"); }
            public DomainEvent load(EventId id) { calls.add("load"); return event; }
        };
        transaction.executeWithoutResult(status -> {
            var origin = EventOrigin.capture();
            legacy.persist(event, List.of(), origin); assertSame(event, legacy.load(event.eventId(), origin));
        });
        assertEquals(List.of("persist", "load"), calls);
        try (var selection = TenantContext.openVerified(a)) {
            transaction.executeWithoutResult(status -> {
                var origin = EventOrigin.capture();
                assertEquals(TENANT_EVENT_DELIVERY_UNSUPPORTED, assertThrows(TenancyException.class,
                        () -> legacy.persist(event, List.of(), origin)).code());
                assertEquals(TENANT_EVENT_DELIVERY_UNSUPPORTED, assertThrows(TenancyException.class,
                        () -> legacy.load(event.eventId(), origin)).code());
            });
        }
        assertEquals(List.of("persist", "load"), calls);
    }

    @Test void emittedGlobalPublicationCarriesTheCapturedOriginThroughTheBridge() {
        var seen = new ArrayList<EventOrigin>();
        var event = new EventContextTest.RecordedPing(EventId.create(), Instant.now());
        EventStorage storage = new EventStorage() {
            public String eventType() { return "test.RecordedPing"; }
            public String listenerId() { return "@storage:RecordedPing"; }
            public Class<? extends DomainEvent> eventClass() { return event.getClass(); }
            public void persist(DomainEvent value, List<String> recipients) { fail("Origin was discarded"); }
            public void persist(DomainEvent value, List<String> recipients, EventOrigin origin) {
                origin.requireCurrent(); seen.add(origin);
            }
            public DomainEvent load(EventId id) { throw new UnsupportedOperationException(); }
        };
        transaction.executeWithoutResult(status -> {
            try (var context = EventContext.open(() -> List.of(storage))) { EventContext.emit(event); }
        });
        assertEquals(1, seen.size()); assertTrue(seen.getFirst().isGlobal());
    }
}
