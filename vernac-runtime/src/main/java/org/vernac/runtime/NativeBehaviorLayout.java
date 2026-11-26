// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.vernac.runtime.NativeBehavior.Branch;
import org.vernac.runtime.NativeBehavior.Step;

/** Versioned statement layout, linked to generated JVM bindings by slot. */
public final class NativeBehaviorLayout {
    private static final int VERSION = 2;
    private static final List<Kind> KINDS = List.of(Kind.values());

    private NativeBehaviorLayout() { }

    public enum Kind { BIND, REQUIRE, ASSIGN, SEND, RETURN, BRANCH }

    public record Instruction(Kind kind, NativeBehaviorExpression expression,
                              List<Instruction> onTrue, List<Instruction> onFalse) {
        public Instruction {
            onTrue = List.copyOf(onTrue);
            onFalse = List.copyOf(onFalse);
        }

        public Instruction(Kind kind) { this(kind, null); }

        public Instruction(Kind kind, NativeBehaviorExpression expression) {
            this(kind, expression, List.of(), List.of());
        }
    }

    public static String resource(String implementationClass, int program) {
        return NativeBehaviorSources.PREFIX + implementationClass.replace('.', '/') + "/" + program + ".layout";
    }

    public static void write(OutputStream output, List<Instruction> steps) throws IOException {
        var data = new DataOutputStream(output);
        data.writeInt(VERSION);
        writeBlock(data, steps, 0);
        data.flush();
    }

    private static int writeBlock(DataOutputStream data, List<Instruction> steps, int slot) throws IOException {
        data.writeInt(steps.size());
        for (var step : steps) {
            data.writeByte(step.kind().ordinal());
            data.writeInt(slot++);
            NativeBehaviorExpression.write(data, step.expression());
            if (step.kind() == Kind.BRANCH) {
                slot = writeBlock(data, step.onTrue(), slot);
                slot = writeBlock(data, step.onFalse(), slot);
            }
        }
        return slot;
    }

    static <S> List<Step<S>> load(Class<?> implementation, int program, List<Step<S>> bridge) {
        String resource = resource(implementation.getName(), program);
        try (var input = implementation.getResourceAsStream("/" + resource)) {
            // Direct Java generation clients can use the in-memory program without a resource writer.
            if (input == null) return bridge;
            return link(input, bridge);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot link native behavior layout " + resource, failure);
        }
    }

    static <S> List<Step<S>> link(InputStream input, List<Step<S>> bridge) throws IOException {
        var data = new DataInputStream(input);
        int version = data.readInt();
        if (version != 1 && version != VERSION) throw new IOException("Unsupported layout version");
        var bindings = new ArrayList<Step<S>>();
        flatten(bridge, bindings);
        var used = new BitSet(bindings.size());
        var linked = readBlock(data, bindings, used, version);
        if (used.cardinality() != bindings.size() || data.read() != -1) {
            throw new IOException("Incomplete layout or trailing data");
        }
        return linked;
    }

    private static <S> void flatten(List<Step<S>> steps, List<Step<S>> bindings) {
        for (var step : steps) {
            bindings.add(step);
            if (step instanceof Branch<S> branch) {
                flatten(branch.onTrue(), bindings);
                flatten(branch.onFalse(), bindings);
            }
        }
    }

    private static <S> List<Step<S>> readBlock(DataInputStream data, List<Step<S>> bindings, BitSet used, int version)
            throws IOException {
        int count = data.readInt();
        if (count < 0 || count > bindings.size() - used.cardinality()) {
            throw new IOException("Invalid instruction count");
        }
        var steps = new ArrayList<Step<S>>(count);
        for (int index = 0; index < count; index++) {
            int opcode = data.readUnsignedByte();
            int slot = data.readInt();
            if (opcode >= KINDS.size() || slot < 0 || slot >= bindings.size() || used.get(slot)) {
                throw new IOException("Invalid binding slot or opcode");
            }
            var binding = bindings.get(slot);
            if (KINDS.get(opcode) != kind(binding)) throw new IOException("Incompatible binding at slot " + slot);
            used.set(slot);
            var expression = version >= 2 ? NativeBehaviorExpression.read(data) : null;
            if (binding instanceof Branch<S> branch) {
                var onTrue = readBlock(data, bindings, used, version);
                var onFalse = readBlock(data, bindings, used, version);
                steps.add(new Branch<>(expression == null ? branch.condition() : expression.link(), onTrue, onFalse));
            } else {
                steps.add(expression == null ? binding : linkOperand(binding, expression));
            }
        }
        return steps;
    }

    private static <S> Step<S> linkOperand(Step<S> binding, NativeBehaviorExpression operand) throws IOException {
        return switch (binding) {
            case NativeBehavior.Bind<S> bind -> new NativeBehavior.Bind<>(bind.slot(), operand.link());
            case NativeBehavior.Require<S> require -> new NativeBehavior.Require<>(operand.link(), require.rejection());
            case NativeBehavior.Assign<S> assign -> new NativeBehavior.Assign<>(operand.link(), assign.setter());
            case NativeBehavior.Return<S> ignored -> new NativeBehavior.Return<>(operand.link());
            default -> throw new IOException("Unexpected operand for " + kind(binding));
        };
    }

    private static Kind kind(Step<?> step) {
        return switch (step) {
            case NativeBehavior.Bind<?> ignored -> Kind.BIND;
            case NativeBehavior.Require<?> ignored -> Kind.REQUIRE;
            case NativeBehavior.Assign<?> ignored -> Kind.ASSIGN;
            case NativeBehavior.Send<?> ignored -> Kind.SEND;
            case NativeBehavior.Return<?> ignored -> Kind.RETURN;
            case Branch<?> ignored -> Kind.BRANCH;
        };
    }
}
