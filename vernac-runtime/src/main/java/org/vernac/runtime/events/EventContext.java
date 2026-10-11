// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.events;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.DomainEvent;
import org.vernac.runtime.EventId;

/** Scoped synchronous publication; does not propagate across threads or independent transactions. */
@NullMarked
public final class EventContext implements AutoCloseable {
    private static final int MAX_DISPATCH_DEPTH = 64;
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private final State state;
    private final Thread thread = Thread.currentThread();
    private final int depth;
    private boolean closed;

    private EventContext(State state) {
        this.state = state;
        this.depth = ++state.scopes;
    }

    /** Nested scopes reuse the outer handler supplier. Close scopes in reverse order. */
    public static EventContext open(Supplier<List<EventHandler>> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        State current = CURRENT.get();
        if (current != null) {
            if (current.synchronization != null || TransactionSynchronizationManager.isActualTransactionActive()) {
                requireTransaction(current);
            }
            return new EventContext(current);
        }
        State state = new State(handlers);
        if (TransactionSynchronizationManager.isActualTransactionActive()) requireTransaction(state);
        CURRENT.set(state);
        return new EventContext(state);
    }

    /** Publishes immediately; any delivery failure prevents the surrounding transaction committing. */
    public static void emit(DomainEvent event) {
        State state = CURRENT.get();
        if (state == null) throw new IllegalStateException("emit requires an active Vernac event context.");
        try {
            Objects.requireNonNull(event, "event");
            requireTransaction(state);
            if (state.failed) throw new IllegalStateException("Event context has already failed.");
            if (state.active.size() >= MAX_DISPATCH_DEPTH || !state.active.add(event.eventId())) {
                throw new IllegalStateException("Recursive event publication detected for " + event.eventId());
            }
            try {
                List<EventHandler> selected = state.handlers.get().stream()
                        .filter(handler -> handler.eventClass().equals(event.getClass())).toList();
                Set<String> identities = new HashSet<>();
                for (var handler : selected) {
                    if (!identities.add(handler.listenerId())) {
                        throw new IllegalStateException("Duplicate event listener identity: " + handler.listenerId());
                    }
                }
                var listeners = selected.stream().filter(handler -> !(handler instanceof EventStorage)).toList();
                var deferred = listeners.stream().filter(EventHandler::deferred).map(EventHandler::listenerId).sorted().toList();
                if (event instanceof RecordedEvent || !deferred.isEmpty()) {
                    var storage = selected.stream().filter(EventStorage.class::isInstance).map(EventStorage.class::cast).toList();
                    if (storage.size() != 1) throw new IllegalStateException("Exactly one event storage adapter is required for " + event.getClass().getName());
                    storage.getFirst().persist(event, deferred);
                }
                for (var handler : listeners) if (!handler.deferred()) handler.handle(event);
            } finally {
                state.active.remove(event.eventId());
            }
        } catch (RuntimeException | Error failure) {
            state.failed = true;
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (NoTransactionException manualTransaction) {
                // Programmatic transactions are covered by the beforeCommit guard.
            }
            throw failure;
        }
    }

    private static void requireTransaction(State state) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("emit requires an active synchronized transaction.");
        }
        if (state.synchronization == null) {
            var synchronization = new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    if (state.failed) throw new IllegalStateException("Event delivery failed; transaction must roll back.");
                }
            };
            state.synchronization = synchronization;
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        } else if (!TransactionSynchronizationManager.getSynchronizations().contains(state.synchronization)) {
            throw new IllegalStateException("An event context cannot cross independent transaction boundaries.");
        }
    }

    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != thread || CURRENT.get() != state || state.scopes != depth) {
            throw new IllegalStateException("Event context must close in reverse order on its owning thread.");
        }
        if (--state.scopes == 0) CURRENT.remove();
        closed = true;
    }

    private static final class State {
        final Supplier<List<EventHandler>> handlers;
        final Set<EventId> active = new HashSet<>();
        @Nullable TransactionSynchronization synchronization;
        int scopes;
        boolean failed;

        State(Supplier<List<EventHandler>> handlers) { this.handlers = handlers; }
    }
}
