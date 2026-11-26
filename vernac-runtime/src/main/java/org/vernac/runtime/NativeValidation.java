// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    public static final class Invocation<S> {
        private static final Object[] NO_LOCALS = {};
        private final S self;
        private final List<Object> arguments;
        private final Object[] locals;

        public Invocation(S self, List<Object> arguments) {
            this(self, arguments, 0);
        }

        public Invocation(S self, List<Object> arguments, int localCount) {
            this.self = self;
            this.arguments = arguments;
            this.locals = localCount == 0 ? NO_LOCALS : new Object[localCount];
        }

        public S self() {
            return self;
        }

        public List<Object> arguments() {
            return arguments;
        }

        public void bind(int index, Object value) {
            locals[index] = Objects.requireNonNull(value);
        }

        public Object local(int index) {
            return Objects.requireNonNull(locals[index]);
        }
    }

    public record MatchInput<S>(S outer, Object value) { }

    public static <S> Expression<S> match(Expression<S> subject, Expression<MatchInput<S>> present,
                                           Expression<S> absent) {
        return new Match<>(subject, present, absent);
    }

    public record Match<S>(Expression<S> subject, Expression<MatchInput<S>> present,
                           Expression<S> absent) implements Expression<S> {
        @Override public Object evaluate(S context) {
            var value = (Optional<?>) subject.evaluate(context);
            return value.isPresent() ? present.evaluate(new MatchInput<>(context, value.orElseThrow()))
                    : absent.evaluate(context);
        }
    }

    public static <S> Expression<S> call(List<Expression<S>> arguments,
                                              BiFunction<S, List<Object>, Object> target) {
        return new Call<>(arguments, target);
    }

    public record Call<S>(List<Expression<S>> arguments, BiFunction<S, List<Object>, Object> target)
            implements Expression<S> {
        public Call {
            arguments = List.copyOf(arguments);
        }

        @Override public Object evaluate(S context) {
            var values = new java.util.ArrayList<>(arguments.size());
            for (var input : arguments) values.add(input.evaluate(context));
            return target.apply(context, values);
        }
    }

    public static <S> Expression<S> literal(Object value) {
        return new Literal<>(value);
    }

    public record Literal<S>(Object value) implements Expression<S> {
        @Override public Object evaluate(S self) { return value; }
    }

    public static <S> Expression<S> access(Function<S, Object> getter) {
        return new Access<>(getter);
    }

    public record Access<S>(Function<S, Object> getter) implements Expression<S> {
        public Access {
            Objects.requireNonNull(getter);
        }

        @Override public Object evaluate(S self) { return getter.apply(self); }
    }

    public static <S> Expression<S> prefix(String operator, Expression<S> operand) {
        return new Prefix<>(operator, operand);
    }

    public static <S> Expression<S> binary(String operator, Expression<S> left, Expression<S> right) {
        return new Binary<>(operator, left, right);
    }

    public record Prefix<S>(String operator, Expression<S> operand) implements Expression<S> {
        public Prefix {
            if (!operator.equals("!") && !operator.equals("-")) {
                throw new IllegalArgumentException("Unknown prefix operator " + operator);
            }
        }

        @Override public Object evaluate(S self) {
            var value = operand.evaluate(self);
            if (operator.equals("!")) return !(boolean) value;
            if (value instanceof BigDecimal decimal) return decimal.negate();
            return -(int) value;
        }
    }

    public record Binary<S>(String operator, Expression<S> left, Expression<S> right) implements Expression<S> {
        @Override public Object evaluate(S self) {
            return switch (operator) {
                case "&&" -> (boolean) left.evaluate(self) && (boolean) right.evaluate(self);
                case "||" -> (boolean) left.evaluate(self) || (boolean) right.evaluate(self);
                default -> evaluateBinary(operator, left.evaluate(self), right.evaluate(self));
            };
        }
    }

    public record Conditional<S>(Expression<S> condition, Expression<S> onTrue, Expression<S> onFalse)
            implements Expression<S> {
        @Override public Object evaluate(S context) {
            return (boolean) condition.evaluate(context) ? onTrue.evaluate(context) : onFalse.evaluate(context);
        }
    }

    public record ToDecimal<S>(Expression<S> operand) implements Expression<S> {
        @Override public Object evaluate(S context) {
            return BigDecimal.valueOf((int) operand.evaluate(context));
        }
    }

    public record RoundDecimal<S>(Expression<S> operand, Expression<S> scale, RoundingMode mode)
            implements Expression<S> {
        @Override public Object evaluate(S context) {
            return ((BigDecimal) operand.evaluate(context)).setScale((int) scale.evaluate(context), mode);
        }
    }

    public record DivideDecimal<S>(Expression<S> dividend, Expression<S> divisor, Expression<S> scale,
                                   RoundingMode mode) implements Expression<S> {
        @Override public Object evaluate(S context) {
            return ((BigDecimal) dividend.evaluate(context)).divide((BigDecimal) divisor.evaluate(context),
                    (int) scale.evaluate(context), mode);
        }
    }

    public enum TemporalOperator {
        DATE_FROM_ISO, DATE_PLUS_DAYS, DATE_DAYS_UNTIL, DURATION_OF_DAYS, DURATION_PLUS, DURATION_MINUS;

        public Object apply(Object first, Object second) {
            return switch (this) {
                case DATE_FROM_ISO -> LocalDate.parse((String) first);
                case DATE_PLUS_DAYS -> ((LocalDate) first).plusDays((int) second);
                case DATE_DAYS_UNTIL -> Math.toIntExact(ChronoUnit.DAYS.between((LocalDate) first, (LocalDate) second));
                case DURATION_OF_DAYS -> Duration.ofDays((int) first);
                case DURATION_PLUS -> ((Duration) first).plus((Duration) second);
                case DURATION_MINUS -> ((Duration) first).minus((Duration) second);
            };
        }
    }

    public record Temporal<S>(TemporalOperator operator, Expression<S> first, Expression<S> second)
            implements Expression<S> {
        @Override public Object evaluate(S context) {
            return operator.apply(first.evaluate(context), second == null ? null : second.evaluate(context));
        }
    }

    public enum CollectionOperator { SELECT, COLLECT, ANY, SUM_INTEGER, SUM_DECIMAL }

    public static <E, T> List<T> collect(Iterable<E> elements, Function<E, T> block) {
        var result = new ArrayList<T>();
        for (E element : elements) result.add(block.apply(element));
        return result;
    }

    public static <E> Object collectionQuery(Iterable<E> elements, Function<E, Object> block,
                                             CollectionOperator operator) {
        return switch (operator) {
            case SELECT -> {
                var selected = new ArrayList<E>();
                for (E element : elements) if ((boolean) block.apply(element)) selected.add(element);
                yield selected;
            }
            case COLLECT -> collect(elements, block);
            case ANY -> {
                for (E element : elements) if ((boolean) block.apply(element)) yield true;
                yield false;
            }
            case SUM_INTEGER -> {
                int total = 0;
                for (E element : elements) total += (int) block.apply(element);
                yield total;
            }
            case SUM_DECIMAL -> {
                var total = BigDecimal.ZERO;
                for (E element : elements) total = total.add((BigDecimal) block.apply(element));
                yield total;
            }
        };
    }

    public record CollectionQuery<S>(CollectionOperator operator, Expression<S> receiver,
                                     Expression<MatchInput<S>> block, Function<List<?>, Object> collectionFactory)
            implements Expression<S> {
        @Override public Object evaluate(S context) {
            var result = collectionQuery((Iterable<?>) receiver.evaluate(context),
                    element -> block.evaluate(new MatchInput<>(context, element)), operator);
            return operator == CollectionOperator.SELECT || operator == CollectionOperator.COLLECT
                    ? collectionFactory.apply((List<?>) result) : result;
        }
    }

    /** Numeric equality for scalar decimal values, including optional operands. */
    public static boolean decimalEquals(Object left, Object right) {
        if (left instanceof Optional<?> a && right instanceof Optional<?> b) {
            return decimalEquals(a.orElse(null), b.orElse(null));
        }
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b) == 0;
        return Objects.equals(left, right);
    }

    public static Object decimalOperation(String operator, BigDecimal left, BigDecimal right) {
        return switch (operator) {
            case "+" -> left.add(right);
            case "-" -> left.subtract(right);
            case "*" -> left.multiply(right);
            case "/" -> left.divide(right);
            case "%" -> left.remainder(right);
            case "<" -> left.compareTo(right) < 0;
            case "<=" -> left.compareTo(right) <= 0;
            case ">" -> left.compareTo(right) > 0;
            case ">=" -> left.compareTo(right) >= 0;
            default -> throw new IllegalArgumentException("Unknown decimal operator " + operator);
        };
    }

    private static Object evaluateBinary(String operator, Object left, Object right) {
        return switch (operator) {
            case "==" -> decimalEquals(left, right);
            case "!=" -> !decimalEquals(left, right);
            default -> left instanceof BigDecimal a && right instanceof BigDecimal b
                    ? decimalOperation(operator, a, b) : integerOperation(operator, left, right);
        };
    }

    private static Object integerOperation(String operator, Object left, Object right) {
        return switch (operator) {
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
