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
    @Test void storesBeforeSynchronousHandlingAndDoesNotExecuteDeferredHandlers() {
        var manager=new Manager(); var calls=new ArrayList<String>();
        EventStorage storage=new EventStorage() {
            public String eventType(){return "test.Ping";}
            public String listenerId(){return "@storage:Ping";}
            public Class<? extends DomainEvent> eventClass(){return Ping.class;}
            public void persist(DomainEvent event,List<String> recipients){calls.add("store:"+recipients);}
            public DomainEvent load(EventId id){throw new UnsupportedOperationException();}
        };
        EventHandler deferred=new EventHandler() {
            public String listenerId(){return "later";}
            public Class<? extends DomainEvent> eventClass(){return Ping.class;}
            public boolean deferred(){return true;}
            public void handle(DomainEvent event){fail("Deferred handler ran during emit");}
        };
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try(var context=EventContext.open(()->List.of(handler("now",e->calls.add("sync")),deferred,storage))) {
                EventContext.emit(new Ping());
            }
        });
        assertEquals(List.of("store:[later]","sync"),calls);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            try(var context=EventContext.open(()->List.of(deferred))) { EventContext.emit(new Ping()); }
        }));
        assertEquals(1,manager.commits); assertEquals(1,manager.rollbacks);
    }
    @Test void recordedEventRequiresStorageEvenWithoutListeners() {
        record RecordedPing(EventId eventId,Instant occurredAt) implements RecordedEvent {}
        var event=new RecordedPing(EventId.create(),Instant.now());
        var manager=new Manager(); var stored=new ArrayList<DomainEvent>();
        EventStorage storage=new EventStorage() {
            public String eventType(){return "test.Ping";}
            public String listenerId(){return "@storage:RecordedPing";}
            public Class<? extends DomainEvent> eventClass(){return RecordedPing.class;}
            public void persist(DomainEvent e,List<String> recipients){assertTrue(recipients.isEmpty());stored.add(e);}
            public DomainEvent load(EventId id){throw new UnsupportedOperationException();}
        };
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try(var context=EventContext.open(()->List.of(storage))) { EventContext.emit(event); }
        });
        assertEquals(List.of(event),stored);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->{
            try(var context=EventContext.open(List::of)) { EventContext.emit(event); }
        }));
    }
    static final class Manager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx,TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
