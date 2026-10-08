// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Synchronous direct-mutation boundary, NOT a transaction. Discard objects after failure.
 * Nested modify calls validate at the successful outermost return. Timestamps are published
 * only after all validations succeed. This does not establish aggregate ownership.
 */
@NullMarked
public final class BehaviorModification {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private BehaviorModification() { }

    public static void run(Object owner, Runnable validation, Runnable body) {
        run(owner, validation, time -> {}, body);
    }
    public static <T> T execute(Object owner, Runnable validation, Supplier<T> body) {
        return execute(owner, validation, time -> {}, body);
    }
    public static void run(Object owner, Runnable validation, Consumer<Instant> afterChange, Runnable body) {
        execute(owner, validation, afterChange, () -> { body.run(); return null; });
    }
    public static <T> T execute(Object owner, Runnable validation, Consumer<Instant> afterChange, Supplier<T> body) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.validating) throw new IllegalStateException("Cannot invoke modify behavior during validation");
        boolean outermost = scope == null;
        if (scope == null) { scope = new Scope(); CURRENT.set(scope); }
        Entry entry = scope.owners.get(owner);
        if (entry == null) {
            entry = new Entry(validation, afterChange);
            scope.owners.put(owner, entry);
            scope.entries.add(entry);
        }
        scope.stack.add(entry);
        try {
            T result = body.get();
            if (outermost) {
                scope.validating = true;
                for (int i = scope.entries.size() - 1; i >= 0; i--) scope.entries.get(i).validation.run();
                // One timestamp shared by all changed participants, after the final validation.
                if (scope.entries.stream().anyMatch(e -> e.changed)) {
                    Instant now = Instant.now();
                    for (Entry e : scope.entries) if (e.changed) e.afterChange.accept(now);
                }
            }
            return result;
        } finally {
            scope.stack.removeLast();
            if (outermost) { scope.active = false; CURRENT.remove(); }
        }
    }

    /** Called after an effective write. Active enclosing modify calls observe descendant changes. */
    public static void changed(Object owner) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.validating || !scope.active || !scope.owners.containsKey(owner))
            throw new IllegalStateException("No writable Vernac modification context for owner");
        scope.owners.get(owner).changed = true;
        for (Entry entry : scope.stack) entry.changed = true;
    }

    /** A retained write facade cannot join a later invocation. */
    public static Runnable accessGuard() {
        Scope scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("No active Vernac modify behavior");
        return () -> {
            if (!scope.active || scope.validating || CURRENT.get() != scope)
                throw new IllegalStateException("Vernac behavior access is closed or not writable");
        };
    }
    private static final class Entry {
        final Runnable validation;
        final Consumer<Instant> afterChange;
        boolean changed;
        Entry(Runnable validation, Consumer<Instant> afterChange) { this.validation = validation; this.afterChange = afterChange; }
    }
    private static final class Scope {
        final IdentityHashMap<Object, Entry> owners = new IdentityHashMap<>();
        final ArrayList<Entry> entries = new ArrayList<>();
        final ArrayList<Entry> stack = new ArrayList<>();
        boolean active = true;
        boolean validating;
    }
}
