// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.junit.jupiter.api.Test;
import org.vernac.runtime.NativeBehavior.Branch;
import org.vernac.runtime.NativeBehavior.Return;
import org.vernac.runtime.NativeBehavior.Step;
import org.vernac.runtime.NativeBehaviorLayout.Instruction;
import org.vernac.runtime.NativeBehaviorLayout.Kind;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeBehaviorLayoutTest {
    private static final List<Step<Boolean>> BRIDGE = List.of(new Branch<>(
            input -> input.self(), List.of(new Return<>(input -> 3)), List.of(new Return<>(input -> 9))));

    @Test
    void takesTheBranchStructureFromTheLayoutAndTheValuesFromJvmBindings() throws IOException {
        var bytes = layout();
        var linked = NativeBehaviorLayout.link(new ByteArrayInputStream(bytes), BRIDGE);
        var program = new NativeBehavior.Program<>(1, 0, linked);
        assertEquals(3, program.execute(true, List.of()));
        assertEquals(9, program.execute(false, List.of()));

        // Swap branch targets in the artifact while leaving the same JVM bindings installed.
        ByteBuffer.wrap(bytes).putInt(18, 2).putInt(27, 1);
        var swapped = new NativeBehavior.Program<>(1, 0,
                NativeBehaviorLayout.link(new ByteArrayInputStream(bytes), BRIDGE));
        assertEquals(9, swapped.execute(true, List.of()));
        assertEquals(3, swapped.execute(false, List.of()));
    }

    @Test
    void rejectsInvalidVersionsAndIncompatibleBindings() throws IOException {
        var incompatible = layout();
        incompatible[8] = (byte) Kind.RETURN.ordinal();
        assertThrows(IOException.class, () -> NativeBehaviorLayout.link(new ByteArrayInputStream(incompatible), BRIDGE));

        var unknownVersion = layout();
        ByteBuffer.wrap(unknownVersion).putInt(99);
        assertThrows(IOException.class, () -> NativeBehaviorLayout.link(new ByteArrayInputStream(unknownVersion), BRIDGE));
    }

    private static byte[] layout() throws IOException {
        var stream = new ByteArrayOutputStream();
        NativeBehaviorLayout.write(stream, List.of(new Instruction(Kind.BRANCH,
                List.of(new Instruction(Kind.RETURN)), List.of(new Instruction(Kind.RETURN)))));
        return stream.toByteArray();
    }
}
