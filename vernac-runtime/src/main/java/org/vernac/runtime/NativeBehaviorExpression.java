// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;

import org.vernac.runtime.NativeValidation.Expression;
import org.vernac.runtime.NativeValidation.Invocation;

/** Resource expressions with explicit references to any remaining JVM operands. */
public sealed interface NativeBehaviorExpression {
    Set<String> BINARY_OPERATORS = Set.of("&&", "||", "==", "!=", "+", "-", "*", "/", "%", "<", "<=", ">", ">=");

    <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) throws IOException;

    record Literal(Object value) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) {
            return NativeValidation.literal(value);
        }
    }

    record Argument(int slot) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) {
            return invocation -> invocation.arguments().get(slot);
        }
    }

    record Local(int slot) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) {
            return invocation -> invocation.local(slot);
        }
    }

    record Host() implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) throws IOException {
            if (bridge == null) throw new IOException("Missing JVM operand for native behavior expression");
            return bridge;
        }
    }

    record Prefix(String operator, NativeBehaviorExpression operand) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) throws IOException {
            var compiled = bridge instanceof NativeValidation.Prefix<Invocation<S>> prefix ? prefix.operand() : null;
            return NativeValidation.prefix(operator, operand.link(compiled));
        }
    }

    record Binary(String operator, NativeBehaviorExpression left, NativeBehaviorExpression right)
            implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link(Expression<Invocation<S>> bridge) throws IOException {
            var compiled = bridge instanceof NativeValidation.Binary<Invocation<S>> binary ? binary : null;
            return NativeValidation.binary(operator, left.link(compiled == null ? null : compiled.left()),
                    right.link(compiled == null ? null : compiled.right()));
        }
    }

    static void write(DataOutputStream output, NativeBehaviorExpression expression) throws IOException {
        switch (expression) {
            case null -> output.writeByte(0);
            case Literal literal -> {
                switch (literal.value()) {
                    case Boolean value -> { output.writeByte(1); output.writeBoolean(value); }
                    case Integer value -> { output.writeByte(2); output.writeInt(value); }
                    case BigDecimal value -> { output.writeByte(3); output.writeUTF(value.toString()); }
                    case String value -> { output.writeByte(4); output.writeUTF(value); }
                    case null, default -> throw new IOException("Unsupported behavior literal");
                }
            }
            case Argument argument -> { output.writeByte(5); output.writeInt(argument.slot()); }
            case Local local -> { output.writeByte(6); output.writeInt(local.slot()); }
            case Prefix prefix -> {
                output.writeByte(7);
                output.writeUTF(prefix.operator());
                write(output, prefix.operand());
            }
            case Binary binary -> {
                output.writeByte(8);
                output.writeUTF(binary.operator());
                write(output, binary.left());
                write(output, binary.right());
            }
            case Host ignored -> output.writeByte(9);
        }
    }

    static NativeBehaviorExpression read(DataInputStream input, int version) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> new Literal(input.readBoolean());
            case 2 -> new Literal(input.readInt());
            case 3 -> {
                try {
                    yield new Literal(new BigDecimal(input.readUTF()));
                } catch (NumberFormatException failure) {
                    throw new IOException("Invalid behavior decimal", failure);
                }
            }
            case 4 -> new Literal(input.readUTF());
            case 5 -> new Argument(readSlot(input));
            case 6 -> new Local(readSlot(input));
            case 7 -> {
                String operator = input.readUTF();
                if (!operator.equals("!") && !operator.equals("-")) throw new IOException("Invalid prefix operator");
                yield new Prefix(operator, required(input, version));
            }
            case 8 -> {
                String operator = input.readUTF();
                if (!BINARY_OPERATORS.contains(operator)) throw new IOException("Invalid binary operator");
                yield new Binary(operator, required(input, version), required(input, version));
            }
            case 9 -> {
                if (version < 3) throw new IOException("JVM operands require behavior layout version 3");
                yield new Host();
            }
            default -> throw new IOException("Unsupported behavior expression opcode");
        };
    }

    private static int readSlot(DataInputStream input) throws IOException {
        int slot = input.readInt();
        if (slot < 0) throw new IOException("Invalid behavior expression slot");
        return slot;
    }

    private static NativeBehaviorExpression required(DataInputStream input, int version) throws IOException {
        var expression = read(input, version);
        if (expression == null) throw new IOException("Missing behavior operand");
        return expression;
    }
}
