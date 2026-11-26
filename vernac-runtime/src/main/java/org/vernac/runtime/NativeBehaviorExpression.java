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

/** Expressions that can be linked using only a native behavior's invocation frame. */
public sealed interface NativeBehaviorExpression {
    Set<String> BINARY_OPERATORS = Set.of("&&", "||", "==", "!=", "+", "-", "*", "/", "%", "<", "<=", ">", ">=");

    <S> Expression<Invocation<S>> link();

    record Literal(Object value) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link() {
            return NativeValidation.literal(value);
        }
    }

    record Argument(int slot) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link() {
            return invocation -> invocation.arguments().get(slot);
        }
    }

    record Local(int slot) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link() {
            return invocation -> invocation.local(slot);
        }
    }

    record Prefix(String operator, NativeBehaviorExpression operand) implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link() {
            return NativeValidation.prefix(operator, operand.link());
        }
    }

    record Binary(String operator, NativeBehaviorExpression left, NativeBehaviorExpression right)
            implements NativeBehaviorExpression {
        @Override public <S> Expression<Invocation<S>> link() {
            return NativeValidation.binary(operator, left.link(), right.link());
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
        }
    }

    static NativeBehaviorExpression read(DataInputStream input) throws IOException {
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
                yield new Prefix(operator, required(input));
            }
            case 8 -> {
                String operator = input.readUTF();
                if (!BINARY_OPERATORS.contains(operator)) throw new IOException("Invalid binary operator");
                yield new Binary(operator, required(input), required(input));
            }
            default -> throw new IOException("Unsupported behavior expression opcode");
        };
    }

    private static int readSlot(DataInputStream input) throws IOException {
        int slot = input.readInt();
        if (slot < 0) throw new IOException("Invalid behavior expression slot");
        return slot;
    }

    private static NativeBehaviorExpression required(DataInputStream input) throws IOException {
        var expression = read(input);
        if (expression == null) throw new IOException("Missing behavior operand");
        return expression;
    }
}
