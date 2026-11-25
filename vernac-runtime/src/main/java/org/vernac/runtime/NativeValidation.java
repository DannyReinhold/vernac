// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.util.Objects;
import java.util.function.Function;

/** Executable expression nodes used by compiler-checked native state rules. */
public final class NativeValidation {
    private NativeValidation() { }

    @FunctionalInterface
    public interface Expression<S> {
        Object evaluate(S self);
    }

    public static <S> Expression<S> literal(Object value) {
        return self -> value;
    }

    public static <S> Expression<S> access(Function<S, Object> getter) {
        return getter::apply;
    }

    public static <S> Expression<S> prefix(String operator, Expression<S> operand) {
        return switch (operator) {
            case "!" -> self -> !(boolean) operand.evaluate(self);
            case "-" -> self -> -(int) operand.evaluate(self);
            default -> throw new IllegalArgumentException("Unknown prefix operator " + operator);
        };
    }

    public static <S> Expression<S> binary(String operator, Expression<S> left, Expression<S> right) {
        return switch (operator) {
            case "&&" -> self -> (boolean) left.evaluate(self) && (boolean) right.evaluate(self);
            case "||" -> self -> (boolean) left.evaluate(self) || (boolean) right.evaluate(self);
            default -> self -> evaluateBinary(operator, left.evaluate(self), right.evaluate(self));
        };
    }

    private static Object evaluateBinary(String operator, Object left, Object right) {
        return switch (operator) {
            case "==" -> Objects.equals(left, right);
            case "!=" -> !Objects.equals(left, right);
            case "+" -> (int) left + (int) right;
            case "-" -> (int) left - (int) right;
            case "*" -> (int) left * (int) right;
            case "/" -> (int) left / (int) right;
            case "%" -> (int) left % (int) right;
            case "<" -> (int) left < (int) right;
            case "<=" -> (int) left <= (int) right;
            case ">" -> (int) left > (int) right;
            case ">=" -> (int) left >= (int) right;
            default -> throw new IllegalArgumentException("Unknown binary operator " + operator);
        };
    }

    /** A rejected native state rule with a stable model-level identifier. */
    public static final class Rejection extends DomainValidationException {
        private final String code;

        public Rejection(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
