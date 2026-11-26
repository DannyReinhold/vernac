// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBehaviorTest {
    @Test
    void interpretsStatementsWithFreshLocalsAndStopsOnRejection() {
        var effects = new AtomicInteger();
        var program = new NativeBehavior.Program<AtomicInteger>(1, 1, List.of(
                new NativeBehavior.Bind<>(0, input -> (int) input.arguments().getFirst() + input.self().get()),
                new NativeBehavior.Require<>(input -> (int) input.local(0) >= 0, "NegativeValue"),
                new NativeBehavior.Assign<>(input -> input.local(0), (target, value) -> target.set((int) value)),
                new NativeBehavior.Send<>(input -> effects.incrementAndGet()),
                new NativeBehavior.Return<>(input -> input.self().get())));
        var state = new AtomicInteger(2);
        assertEquals(5, program.execute(state, List.of(3)));
        var failure = assertThrows(NativeValidation.Rejection.class, () -> program.execute(state, List.of(-6)));
        assertEquals("NegativeValue", failure.code());
        assertEquals(5, state.get());
        assertEquals(1, effects.get());
        assertEquals(6, program.execute(state, List.of(1)));
        assertEquals(2, effects.get());
    }

    @Test
    void rejectsAnUnsupportedFormatBeforeExecution() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new NativeBehavior.Program<>(99, 0, List.of()));
        assertTrue(failure.getMessage().contains("Unsupported Vernac behavior format 99"));
    }
}
