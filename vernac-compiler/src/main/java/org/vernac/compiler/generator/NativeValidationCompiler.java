// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.CodeBlock;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.runtime.NativeValidation;

import java.util.List;
import java.util.Set;

/** Resolves state reads and emits an executable predicate against the domain read interface. */
public final class NativeValidationCompiler {
    private static final ResolvedType.Builtin BOOL = new ResolvedType.Builtin(boolean.class);
    private static final ResolvedType.Builtin INT = new ResolvedType.Builtin(int.class);

    private final TopLevelDefinition owner;
    private final ResolvedProject project;

    NativeValidationCompiler(TopLevelDefinition owner, ResolvedProject project) {
        this.owner = owner;
        this.project = project;
    }

    public static List<CompilerDiagnostic> diagnostics(ResolvedProject project) {
        var diagnostics = new java.util.ArrayList<CompilerDiagnostic>();
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            for (var definition : source.unit().definitions()) {
                var rules = switch (definition) {
                    case ValueObjectNode value -> value.validations();
                    case EntityNode entity -> entity.validations();
                    case AggregateNode aggregate -> aggregate.validations();
                    case EventNode event -> event.validations();
                    default -> List.<ValidationRuleNode>of();
                };
                var compiler = new NativeValidationCompiler(definition, project);
                for (var rule : rules) {
                    if (rule.nativeRule().isEmpty()) continue;
                    try {
                        compiler.compile(rule.nativeRule().orElseThrow().predicate());
                    } catch (SemanticValidationException failure) {
                        diagnostics.addAll(failure.diagnostics());
                    }
                }
            }
        }
        return diagnostics;
    }

    CodeBlock compile(KeywordExpression predicate) {
        var result = resolve(predicate);
        require(predicate, result, BOOL);
        return result.program();
    }

    private record Typed(CodeBlock program, ResolvedType type, boolean optional) { }
    private record Read(CodeBlock code, FieldNode field) { }

    private Typed resolve(KeywordExpression expression) {
        return switch (expression) {
            case KeywordExpression.Literal literal -> literal(literal);
            case KeywordExpression.UnaryMessage message -> {
                var read = read(message);
                yield new Typed(CodeBlock.of("$T.access(self -> $L)", NativeValidation.class, read.code()),
                        project.typeOf(read.field().type()), read.field().type().isOptional());
            }
            case KeywordExpression.Prefix prefix -> {
                var result = resolve(prefix.operand());
                var expected = prefix.operator().equals("!") ? BOOL : INT;
                require(prefix.operand(), result, expected);
                yield new Typed(CodeBlock.of("$T.prefix($S, $L)", NativeValidation.class,
                        prefix.operator(), result.program()), expected, false);
            }
            case KeywordExpression.Binary binary -> binary(binary);
            default -> throw error(expression, "Expected a literal, read-only self field or operator");
        };
    }

    private Typed binary(KeywordExpression.Binary expression) {
        var left = resolve(expression.left());
        var right = resolve(expression.right());
        String op = expression.operator();
        ResolvedType resultType;
        switch (op) {
            case "==", "!=" -> {
                if (!left.type().equals(right.type()) || left.optional() != right.optional())
                    throw error(expression.right(), "Equality requires operands of the same type and optionality");
                resultType = BOOL;
            }
            case "&&", "||" -> {
                require(expression.left(), left, BOOL);
                require(expression.right(), right, BOOL);
                resultType = BOOL;
            }
            default -> {
                require(expression.left(), left, INT);
                require(expression.right(), right, INT);
                resultType = Set.of("<", ">", "<=", ">=").contains(op) ? BOOL : INT;
            }
        }
        return new Typed(CodeBlock.of("$T.binary($S, $L, $L)", NativeValidation.class,
                op, left.program(), right.program()), resultType, false);
    }

    private Read read(KeywordExpression.UnaryMessage expression) {
        CodeBlock receiver;
        TopLevelDefinition definition;
        if (expression.receiver() instanceof KeywordExpression.Name name && name.name().equals("self")) {
            receiver = CodeBlock.of("self");
            definition = owner;
        } else if (expression.receiver() instanceof KeywordExpression.UnaryMessage parent) {
            var read = read(parent);
            // Traversing an entity would expose an opaque object rather than its validation view.
            if (read.field().type().isOptional()
                    || !(project.typeOf(read.field().type()) instanceof ResolvedType.Declared declared))
                throw error(expression, "Expected a non-optional value object for a nested field read");
            definition = project.project().sources().stream()
                    .filter(source -> source.unit().namespace().equals(declared.symbol().identity().namespace()))
                    .flatMap(source -> source.unit().valueObjects().stream())
                    .filter(value -> value.name().equals(declared.symbol().identity().name()))
                    .findFirst().orElseThrow(() -> error(expression, "Expected a value object for a nested field read"));
            receiver = read.code();
        } else {
            throw error(expression, "Validation fields must be read through self");
        }
        var field = fields(definition).stream().filter(f -> f.name().equals(expression.selector()))
                .findFirst().orElseThrow(() -> error(expression, "Unknown readable field '" + expression.selector() + "'"));
        return new Read(CodeBlock.of("$L.$N()", receiver, field.name()), field);
    }

    private static List<FieldNode> fields(TopLevelDefinition owner) {
        return switch (owner) {
            case ValueObjectNode value -> value.fields();
            case EntityNode entity -> entity.fields();
            case AggregateNode aggregate -> aggregate.fields();
            case EventNode event -> event.fields();
            default -> throw new IllegalArgumentException("Unsupported native validation owner " + owner);
        };
    }

    private Typed literal(KeywordExpression.Literal literal) {
        var spelling = literal.spelling();
        try {
            return switch (literal.kind()) {
                case BOOLEAN -> new Typed(CodeBlock.of("$T.literal($L)", NativeValidation.class, spelling), BOOL, false);
                case INTEGER -> new Typed(CodeBlock.of("$T.literal($L)", NativeValidation.class,
                        Integer.parseInt(spelling)), INT, false);
                case STRING -> new Typed(CodeBlock.of("$T.literal($S)", NativeValidation.class,
                        spelling.substring(1, spelling.length() - 1).translateEscapes()),
                        new ResolvedType.Builtin(String.class), false);
                case DECIMAL -> throw error(literal, "Decimal literals are not supported in native validation yet");
            };
        } catch (IllegalArgumentException failure) {
            throw error(literal, "Invalid literal '" + spelling + "'");
        }
    }

    private static void require(KeywordExpression at, Typed actual, ResolvedType.Builtin expected) {
        if (actual.optional() || !actual.type().equals(expected))
            throw error(at, "Expected a non-optional " + expected.javaType().getSimpleName());
    }

    private static SemanticValidationException error(KeywordExpression at, String message) {
        return new SemanticValidationException(List.of(CompilerDiagnostic.error(at.location(), message)));
    }
}
