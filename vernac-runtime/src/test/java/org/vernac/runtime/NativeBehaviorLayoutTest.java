// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.junit.jupiter.api.Test;
import org.vernac.runtime.NativeBehavior.Branch;
import org.vernac.runtime.NativeBehavior.Bind;
import org.vernac.runtime.NativeBehavior.Return;
import org.vernac.runtime.NativeBehavior.Step;
import org.vernac.runtime.NativeBehaviorLayout.Instruction;
import org.vernac.runtime.NativeBehaviorLayout.Kind;
import org.vernac.runtime.NativeBehaviorExpression.Argument;
import org.vernac.runtime.NativeBehaviorExpression.Binary;
import org.vernac.runtime.NativeBehaviorExpression.Literal;
import org.vernac.runtime.NativeBehaviorExpression.Local;

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
        ByteBuffer.wrap(bytes).putInt(19, 2).putInt(29, 1);
        var swapped = new NativeBehavior.Program<>(1, 0,
                NativeBehaviorLayout.link(new ByteArrayInputStream(bytes), BRIDGE));
        assertEquals(9, swapped.execute(true, List.of()));
        assertEquals(3, swapped.execute(false, List.of()));
    }

    @Test
    void evaluatesOperandsFromTheResourceAgainstArgumentsAndLocals() throws IOException {
        List<Step<Boolean>> bridge = List.of(new Bind<>(0, input -> -1), new Branch<>(input -> false,
                List.of(new Return<>(input -> -1)), List.of(new Return<>(input -> -1))));
        var stream = new ByteArrayOutputStream();
        NativeBehaviorLayout.write(stream, List.of(
                new Instruction(Kind.BIND, new Binary("+", new Argument(0), new Literal(3))),
                new Instruction(Kind.BRANCH, new Binary(">", new Local(0), new Literal(4)),
                        List.of(new Instruction(Kind.RETURN, new Binary("*", new Local(0), new Literal(2)))),
                        List.of(new Instruction(Kind.RETURN, new Literal(0))))));
        var linked = new NativeBehavior.Program<>(1, 1,
                NativeBehaviorLayout.link(new ByteArrayInputStream(stream.toByteArray()), bridge));
        assertEquals(12, linked.execute(false, List.<Object>of(3)));
        assertEquals(0, linked.execute(true, List.<Object>of(1)));
    }

    @Test
    void loadsVersionOneLayoutsWithTheirOriginalBridgeOperands() throws IOException {
        var bytes = ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte) Kind.RETURN.ordinal()).putInt(0).array();
        List<Step<Boolean>> bridge = List.of(new Return<>(input -> 7));
        var linked = NativeBehaviorLayout.link(new ByteArrayInputStream(bytes), bridge);
        assertEquals(7, new NativeBehavior.Program<>(1, 0, linked).execute(true, List.of()));
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
        NativeBehaviorLayout.write(stream, List.of(new Instruction(Kind.BRANCH, null,
                List.of(new Instruction(Kind.RETURN)), List.of(new Instruction(Kind.RETURN)))));
        return stream.toByteArray();
    }
}
