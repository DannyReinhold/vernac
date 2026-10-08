// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.Supplier;

/**
 * Synchronous validation boundary for direct domain mutations. This is NOT a transaction:
 * mutations and side effects survive exceptions. Discard affected objects after failure.
 * Nested modify calls validate at the successful outermost return, children before parents.
 */
@NullMarked
public final class BehaviorModification {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private BehaviorModification() { }

    public static void run(Object owner, Runnable validation, Runnable body) {
        execute(owner, validation, () -> { body.run(); return null; });
    }

    public static <T> T execute(Object owner, Runnable validation, Supplier<T> body) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.validating) throw new IllegalStateException("Cannot invoke modify behavior during validation");
        boolean outermost = scope == null;
        if (scope == null) {
            scope = new Scope();
            CURRENT.set(scope);
        }
        if (scope.owners.put(owner, Boolean.TRUE) == null) scope.validations.add(validation);
        try {
            T result = body.get();
            if (outermost) {
                // Disable access writes while validating; read getters still work.
                scope.validating = true;
                for (int i = scope.validations.size() - 1; i >= 0; i--) scope.validations.get(i).run();
            }
            return result;
        } finally {
            if (outermost) {
                scope.active = false;
                CURRENT.remove();
            }
        }
    }

    /** Captures the current invocation; a retained write facade cannot join a later invocation. */
    public static Runnable accessGuard() {
        Scope scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("No active Vernac modify behavior");
        return () -> {
            if (!scope.active || scope.validating || CURRENT.get() != scope)
                throw new IllegalStateException("Vernac behavior access is closed or not writable");
        };
    }

    private static final class Scope {
        final IdentityHashMap<Object, Boolean> owners = new IdentityHashMap<>();
        final ArrayList<Runnable> validations = new ArrayList<>();
        boolean active = true;
        boolean validating;
    }
}
