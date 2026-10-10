// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.events;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.vernac.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class EventContextTest {
    record Ping(EventId eventId, Instant occurredAt) implements DomainEvent {
        Ping() { this(EventId.create(), Instant.now()); }
    }
    private EventHandler handler(String name, java.util.function.Consumer<DomainEvent> body) {
        return new EventHandler() {
            public String listenerId() { return name; }
            public Class<? extends DomainEvent> eventClass() { return Ping.class; }
            public void handle(DomainEvent event) { body.accept(event); }
        };
    }
    @Test void dispatchesToEveryMatchingListenerWithinCurrentTransactionAndClosesContext() {
        var manager=new Manager(); var observed=new ArrayList<DomainEvent>(); var ping=new Ping();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            try(var context=EventContext.open(()->List.of(handler("a",observed::add),handler("b",observed::add)))) {
                try(var nested=EventContext.open(List::of)) { EventContext.emit(ping); }
            }
        });
        assertEquals(List.of(ping,ping),observed); assertEquals(1,manager.commits);
        assertThrows(IllegalStateException.class,()->EventContext.emit(ping));
    }
    @Test void caughtListenerFailureStillPreventsCommit() {
        var manager=new Manager(); var failure=new IllegalArgumentException("listener failed");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx -> {
            try(var context=EventContext.open(()->List.of(handler("a",e->{throw failure;})))) {
                assertSame(failure,assertThrows(IllegalArgumentException.class,()->EventContext.emit(new Ping())));
            }
        }));
        assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
        assertThrows(IllegalStateException.class,()->EventContext.emit(new Ping()));
    }
    @Test void requiresTransactionAndDetectsRecursionAndDuplicateListenerNames() {
        try(var context=EventContext.open(List::of)) {
            assertThrows(IllegalStateException.class,()->EventContext.emit(new Ping()));
        }
        for(var handlers:List.of(List.of(handler("recursive",EventContext::emit)),List.of(handler("same",e->{}),handler("same",e->{})))) {
            var manager=new Manager();
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->{
                try(var context=EventContext.open(()->handlers)) { EventContext.emit(new Ping()); }
            }));
            assertEquals(0,manager.commits); assertEquals(1,manager.rollbacks);
        }
    }
    @Test void doesNotPropagateContextToOtherThreads() throws Exception {
        try(var context=EventContext.open(List::of)) {
            var task=new java.util.concurrent.FutureTask<>(()->assertThrows(IllegalStateException.class,()->EventContext.emit(new Ping())));
            new Thread(task).start(); task.get();
        }
    }
    static final class Manager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx,TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
