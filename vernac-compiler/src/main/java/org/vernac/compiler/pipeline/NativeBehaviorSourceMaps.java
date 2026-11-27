// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.ClassName;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.EntityNode;
import org.vernac.compiler.ast.FieldNode;
import org.vernac.compiler.ast.KeywordExpression;
import org.vernac.compiler.ast.ListenerNode;
import org.vernac.compiler.ast.MethodNode;
import org.vernac.compiler.ast.NativeStatement;
import org.vernac.compiler.ast.UseCaseNode;
import org.vernac.compiler.generator.BehaviorGenerator;
import org.vernac.runtime.NativeBehaviorExpression;
import org.vernac.runtime.NativeBehaviorSources;
import org.vernac.runtime.NativeBehaviorLayout;
import org.vernac.runtime.NativeBehaviorLayout.Instruction;
import org.vernac.runtime.NativeBehaviorLayout.Kind;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Writes revision-specific statement positions and control flow alongside generated Java. */
public final class NativeBehaviorSourceMaps {
    private NativeBehaviorSourceMaps() { }

    public static void write(ResolvedProject project, Path output) throws IOException {
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            String relative = project.project().sourceRoot().relativize(source.path()).toString().replace('\\', '/');
            String namespace = source.unit().namespace();
            for (var definition : source.unit().definitions()) {
                switch (definition) {
                    case EntityNode entity -> writeMethods(output, namespace, entity.name(), relative, entity.methods());
                    case AggregateNode aggregate -> writeMethods(output, namespace, aggregate.name(), relative, aggregate.methods());
                    case UseCaseNode useCase -> writeOperation(output, namespace, ".usecase", relative, useCase);
                    case ListenerNode listener -> writeOperation(output, namespace, ".adapter.inbound.event", relative, listener.operation());
                    default -> { }
                }
            }
        }
    }

    private static void writeMethods(Path output, String namespace, String owner, String source, List<MethodNode> methods)
            throws IOException {
        var companion = BehaviorGenerator.companion(ClassName.get(namespace + ".domain", owner));
        int program = 0;
        for (var method : methods) {
            if (method.nativeBody().isEmpty()) continue;
            writeProgram(output, companion, program++, namespace + "." + owner + "." + method.name(),
                    source, method.parameters(), method.nativeBody());
        }
    }

    private static void writeOperation(Path output, String namespace, String subpackage, String source, UseCaseNode operation)
            throws IOException {
        if (operation.nativeBody().isEmpty()) return;
        var companion = BehaviorGenerator.companion(ClassName.get(namespace + subpackage, operation.name()));
        writeProgram(output, companion, 0, namespace + "." + operation.name() + ".execute",
                source, operation.parameters(), operation.nativeBody());
    }

    private static void writeProgram(Path output, ClassName companion, int program, String declaration, String source,
                                     List<FieldNode> parameters, List<NativeStatement> statements) throws IOException {
        var positions = new ArrayList<NativeBehaviorSources.Position>();
        collect(statements, "", positions);
        var file = output.resolve(NativeBehaviorSources.resource(companion.canonicalName(), program));
        Files.createDirectories(file.getParent());
        try (var stream = Files.newOutputStream(file)) {
            NativeBehaviorSources.write(stream, declaration, source, positions);
        }
        var layout = output.resolve(NativeBehaviorLayout.resource(companion.canonicalName(), program));
        var arguments = new HashMap<String, NativeBehaviorExpression>();
        for (int slot = 0; slot < parameters.size(); slot++) {
            arguments.put(parameters.get(slot).name(), new NativeBehaviorExpression.Argument(slot));
        }
        try (var stream = Files.newOutputStream(layout)) {
            NativeBehaviorLayout.write(stream, instructions(statements, arguments, 0));
        }
    }

    private static List<Instruction> instructions(List<NativeStatement> statements,
                                                   Map<String, NativeBehaviorExpression> enclosing, int nextLocal) {
        var bindings = new HashMap<>(enclosing);
        var steps = new ArrayList<Instruction>(statements.size());
        for (var statement : statements) {
            switch (statement) {
                case NativeStatement.Let let -> {
                    steps.add(new Instruction(Kind.BIND, operand(let.value(), bindings)));
                    bindings.put(let.name().name(), new NativeBehaviorExpression.Local(nextLocal++));
                }
                case NativeStatement.Require require -> steps.add(new Instruction(Kind.REQUIRE, operand(require.predicate(), bindings)));
                case NativeStatement.Assign assign -> steps.add(new Instruction(Kind.ASSIGN, operand(assign.value(), bindings)));
                case NativeStatement.Send ignored -> steps.add(new Instruction(Kind.SEND));
                case NativeStatement.Return result -> steps.add(new Instruction(Kind.RETURN, operand(result.value(), bindings)));
                case NativeStatement.Branch branch -> steps.add(new Instruction(Kind.BRANCH,
                        operand(branch.condition(), bindings), instructions(branch.onTrue(), bindings, nextLocal),
                        instructions(branch.onFalse(), bindings, nextLocal)));
            }
        }
        return steps;
    }

    private static NativeBehaviorExpression operand(KeywordExpression expression,
                                                    Map<String, NativeBehaviorExpression> bindings) {
        return switch (expression) {
            case KeywordExpression.Name name -> bindings.get(name.name());
            case KeywordExpression.Literal literal -> new NativeBehaviorExpression.Literal(switch (literal.kind()) {
                case BOOLEAN -> Boolean.parseBoolean(literal.spelling());
                case INTEGER -> Integer.parseInt(literal.spelling());
                case DECIMAL -> new BigDecimal(literal.spelling());
                case STRING -> literal.spelling().substring(1, literal.spelling().length() - 1).translateEscapes();
            });
            case KeywordExpression.Prefix prefix -> new NativeBehaviorExpression.Prefix(prefix.operator(),
                    childOperand(prefix.operand(), bindings));
            case KeywordExpression.Binary binary -> new NativeBehaviorExpression.Binary(binary.operator(),
                    childOperand(binary.left(), bindings), childOperand(binary.right(), bindings));
            default -> null;
        };
    }

    private static NativeBehaviorExpression childOperand(KeywordExpression expression,
                                                         Map<String, NativeBehaviorExpression> bindings) {
        var child = operand(expression, bindings);
        return child == null ? new NativeBehaviorExpression.Host() : child;
    }

    private static void collect(List<NativeStatement> statements, String prefix,
                                List<NativeBehaviorSources.Position> positions) {
        for (int index = 0; index < statements.size(); index++) {
            var statement = statements.get(index);
            String instruction = prefix + index;
            var at = statement.location();
            positions.add(new NativeBehaviorSources.Position(instruction, at.line(), at.column()));
            if (statement instanceof NativeStatement.Branch branch) {
                collect(branch.onTrue(), instruction + "/t/", positions);
                collect(branch.onFalse(), instruction + "/f/", positions);
            }
        }
    }
}
