// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Executable expression nodes used by compiler-checked native state rules. */
public final class NativeValidation {
    private NativeValidation() { }

    @FunctionalInterface
    public interface Expression<S> {
        Object evaluate(S self);
    }

    /** Per-call inputs for a native domain behavior. Expression nodes remain reusable. */
    public record Invocation<S>(S self, List<Object> arguments) { }

    public record MatchInput<S>(S outer, Object value) { }

    public static <S> Expression<S> match(Expression<S> subject, Expression<MatchInput<S>> present,
                                           Expression<S> absent) {
        return context -> {
            var value = (Optional<?>) subject.evaluate(context);
            return value.isPresent() ? present.evaluate(new MatchInput<>(context, value.orElseThrow()))
                    : absent.evaluate(context);
        };
    }

    public static <S> Expression<S> call(List<Expression<S>> arguments,
                                              BiFunction<S, List<Object>, Object> target) {
        var inputs = List.copyOf(arguments);
        return context -> {
            var values = new java.util.ArrayList<>(inputs.size());
            for (var input : inputs) values.add(input.evaluate(context));
            return target.apply(context, values);
        };
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
