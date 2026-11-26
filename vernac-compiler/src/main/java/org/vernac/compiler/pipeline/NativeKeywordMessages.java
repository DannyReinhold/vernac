// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.KeywordExpression;
import org.vernac.compiler.ast.CollectionDeclaration;
import org.vernac.compiler.symbols.FileTypeScope;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeSymbol;
import org.vernac.runtime.NativeValidation.TemporalOperator;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** Selectors shared by the native expression compiler and scenario predicates. */
public final class NativeKeywordMessages {
    public static final String CONDITIONAL = "ifTrue:ifFalse:";
    public static final String ROUNDED = "roundedTo:mode:";
    public static final String DIVIDED = "dividedBy:scale:mode:";
    public static final String AS_DECIMAL = "asDecimal";
    public static final String SELECT = "select:";
    public static final String COLLECT = "collect:into:";
    public static final String ANY = "anySatisfy:";
    public static final String SUM = "sumBy:";

    public record TemporalMessage(Class<?> owner, String selector, Class<?> argument,
                                  Class<?> result, TemporalOperator operator, boolean factory) { }

    private static final List<TemporalMessage> TEMPORAL = List.of(
            new TemporalMessage(LocalDate.class, "fromIso:", String.class, LocalDate.class,
                    TemporalOperator.DATE_FROM_ISO, true),
            new TemporalMessage(LocalDate.class, "plusDays:", int.class, LocalDate.class,
                    TemporalOperator.DATE_PLUS_DAYS, false),
            new TemporalMessage(LocalDate.class, "daysUntil:", LocalDate.class, int.class,
                    TemporalOperator.DATE_DAYS_UNTIL, false),
            new TemporalMessage(Duration.class, "ofDays:", int.class, Duration.class,
                    TemporalOperator.DURATION_OF_DAYS, true),
            new TemporalMessage(Duration.class, "plus:", Duration.class, Duration.class,
                    TemporalOperator.DURATION_PLUS, false),
            new TemporalMessage(Duration.class, "minus:", Duration.class, Duration.class,
                    TemporalOperator.DURATION_MINUS, false));

    private NativeKeywordMessages() { }

    public static boolean isCollectionSelector(String selector) {
        return selector.equals(SELECT) || selector.equals(COLLECT) || selector.equals(ANY) || selector.equals(SUM);
    }

    public static ResolvedType.Declared collectionTarget(KeywordExpression expression, FileTypeScope scope) {
        if (expression instanceof KeywordExpression.Name name
                && scope.resolve(name.name(), name.location()).type().orElse(null) instanceof ResolvedType.Declared type
                && type.symbol().kind() == TypeSymbol.Kind.COLLECTION) {
            return type;
        }
        throw new SemanticValidationException(List.of(CompilerDiagnostic.error(expression.location(),
                "Expected a declared collection type after into:")));
    }

    public static ResolvedType.Declared collectionElement(ResolvedProject project, ResolvedType collection) {
        if (!(collection instanceof ResolvedType.Declared type) || type.symbol().kind() != TypeSymbol.Kind.COLLECTION) {
            return null;
        }
        var source = project.project().sources().stream()
                .filter(candidate -> candidate.path().equals(type.symbol().sourceFile())).findFirst().orElseThrow();
        var declaration = source.unit().definitions().stream().flatMap(node -> CollectionDeclaration.of(node).stream())
                .filter(candidate -> candidate.name().equals(type.symbol().identity().name())).findFirst().orElseThrow();
        return (ResolvedType.Declared) project.scopes().get(source.path())
                .resolve(declaration.elementName(), type.symbol().location()).type().orElseThrow();
    }

    public static KeywordExpression.Block requireBlock(KeywordExpression argument) {
        if (argument instanceof KeywordExpression.Block block) return block;
        throw new SemanticValidationException(List.of(CompilerDiagnostic.error(argument.location(),
                "Expected a collection block such as [item | item amount]")));
    }

    public static TemporalMessage temporalFactory(String name, String selector) {
        return TEMPORAL.stream().filter(TemporalMessage::factory)
                .filter(message -> message.owner().getSimpleName().equals(name) && message.selector().equals(selector))
                .findFirst().orElse(null);
    }

    public static boolean isTemporalSelector(String selector) {
        return TEMPORAL.stream().anyMatch(message -> !message.factory() && message.selector().equals(selector));
    }

    public static TemporalMessage temporalInstance(Class<?> receiver, String selector) {
        return TEMPORAL.stream().filter(message -> !message.factory())
                .filter(message -> message.owner() == receiver && message.selector().equals(selector))
                .findFirst().orElse(null);
    }

    public static RoundingMode roundingMode(KeywordExpression expression) {
        if (expression instanceof KeywordExpression.UnaryMessage mode
                && mode.receiver() instanceof KeywordExpression.Name type
                && type.name().equals("RoundingMode")) {
            try {
                return RoundingMode.valueOf(mode.selector());
            } catch (IllegalArgumentException ignored) {
                // Report the invalid constant at its Vernac source location.
            }
        }
        throw new SemanticValidationException(List.of(CompilerDiagnostic.error(expression.location(),
                "Expected a RoundingMode constant such as RoundingMode HALF_UP")));
    }
}
