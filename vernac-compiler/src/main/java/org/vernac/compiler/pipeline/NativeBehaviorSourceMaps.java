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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Writes revision-specific statement positions and control flow alongside generated Java. */
public final class NativeBehaviorSourceMaps {
    private NativeBehaviorSourceMaps() { }

    record ProgramKey(String implementation, int index) {
        String sourceResource() { return NativeBehaviorSources.resource(implementation, index); }
        String layoutResource() { return NativeBehaviorLayout.resource(implementation, index); }
    }

    record Program(byte[] layout, byte[] sourceMap) { }

    @FunctionalInterface
    private interface Output {
        void write(ProgramKey key, String declaration, String source, List<NativeBehaviorSources.Position> positions,
                   List<Instruction> instructions) throws IOException;
    }

    public static void write(ResolvedProject project, Path output) throws IOException {
        generate(project, ignored -> { }, (key, declaration, source, positions, instructions) -> {
            var file = output.resolve(key.sourceResource());
            Files.createDirectories(file.getParent());
            try (var stream = Files.newOutputStream(file)) {
                NativeBehaviorSources.write(stream, declaration, source, positions);
            }
            try (var stream = Files.newOutputStream(output.resolve(key.layoutResource()))) {
                NativeBehaviorLayout.write(stream, instructions);
            }
        });
    }

    static Map<ProgramKey, Program> collect(ResolvedProject project, Consumer<KeywordExpression.Literal> literals)
            throws IOException {
        var programs = new HashMap<ProgramKey, Program>();
        generate(project, literals, (key, declaration, source, positions, instructions) -> {
            var map = new ByteArrayOutputStream();
            NativeBehaviorSources.write(map, declaration, source, positions);
            var layout = new ByteArrayOutputStream();
            NativeBehaviorLayout.write(layout, instructions);
            programs.put(key, new Program(layout.toByteArray(), map.toByteArray()));
        });
        return Map.copyOf(programs);
    }

    private static void generate(ResolvedProject project, Consumer<KeywordExpression.Literal> literals, Output output)
            throws IOException {
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            String relative = project.project().sourceRoot().relativize(source.path()).toString().replace('\\', '/');
            String namespace = source.unit().namespace();
            for (var definition : source.unit().definitions()) {
                switch (definition) {
                    case EntityNode entity -> writeMethods(output, literals, namespace, entity.name(), relative, entity.methods());
                    case AggregateNode aggregate -> writeMethods(output, literals, namespace, aggregate.name(), relative, aggregate.methods());
                    case UseCaseNode useCase -> writeOperation(output, literals, namespace, ".usecase", relative, useCase);
                    case ListenerNode listener -> writeOperation(output, literals, namespace, ".adapter.inbound.event", relative, listener.operation());
                    default -> { }
                }
            }
        }
    }

    private static void writeMethods(Output output, Consumer<KeywordExpression.Literal> literals, String namespace,
                                     String owner, String source, List<MethodNode> methods) throws IOException {
        var companion = BehaviorGenerator.companion(ClassName.get(namespace + ".domain", owner));
        int program = 0;
        for (var method : methods) {
            if (method.nativeBody().isEmpty()) continue;
            writeProgram(output, literals, companion, program++, namespace + "." + owner + "." + method.name(),
                    source, method.parameters(), method.nativeBody());
        }
    }

    private static void writeOperation(Output output, Consumer<KeywordExpression.Literal> literals, String namespace,
                                       String subpackage, String source, UseCaseNode operation) throws IOException {
        if (operation.nativeBody().isEmpty()) return;
        var companion = BehaviorGenerator.companion(ClassName.get(namespace + subpackage, operation.name()));
        writeProgram(output, literals, companion, 0, namespace + "." + operation.name() + ".execute",
                source, operation.parameters(), operation.nativeBody());
    }

    private static void writeProgram(Output output, Consumer<KeywordExpression.Literal> literals, ClassName companion,
                                     int program, String declaration, String source,
                                     List<FieldNode> parameters, List<NativeStatement> statements) throws IOException {
        var positions = new ArrayList<NativeBehaviorSources.Position>();
        collect(statements, "", positions);
        var arguments = new HashMap<String, NativeBehaviorExpression>();
        for (int slot = 0; slot < parameters.size(); slot++) {
            arguments.put(parameters.get(slot).name(), new NativeBehaviorExpression.Argument(slot));
        }
        output.write(new ProgramKey(companion.canonicalName(), program), declaration, source, positions,
                instructions(statements, arguments, 0, literals));
    }

    private static List<Instruction> instructions(List<NativeStatement> statements,
                                                   Map<String, NativeBehaviorExpression> enclosing, int nextLocal,
                                                   Consumer<KeywordExpression.Literal> literals) {
        var bindings = new HashMap<>(enclosing);
        var steps = new ArrayList<Instruction>(statements.size());
        for (var statement : statements) {
            switch (statement) {
                case NativeStatement.Let let -> {
                    steps.add(new Instruction(Kind.BIND, operand(let.value(), bindings, literals)));
                    bindings.put(let.name().name(), new NativeBehaviorExpression.Local(nextLocal++));
                }
                case NativeStatement.Require require -> steps.add(new Instruction(Kind.REQUIRE, operand(require.predicate(), bindings, literals)));
                case NativeStatement.Assign assign -> steps.add(new Instruction(Kind.ASSIGN, operand(assign.value(), bindings, literals)));
                case NativeStatement.Send ignored -> steps.add(new Instruction(Kind.SEND));
                case NativeStatement.Return result -> steps.add(new Instruction(Kind.RETURN, operand(result.value(), bindings, literals)));
                case NativeStatement.Branch branch -> steps.add(new Instruction(Kind.BRANCH,
                        operand(branch.condition(), bindings, literals), instructions(branch.onTrue(), bindings, nextLocal, literals),
                        instructions(branch.onFalse(), bindings, nextLocal, literals)));
            }
        }
        return steps;
    }

    private static NativeBehaviorExpression operand(KeywordExpression expression,
                                                    Map<String, NativeBehaviorExpression> bindings,
                                                    Consumer<KeywordExpression.Literal> literals) {
        return switch (expression) {
            case KeywordExpression.Name name -> bindings.get(name.name());
            case KeywordExpression.Literal literal -> {
                literals.accept(literal);
                yield new NativeBehaviorExpression.Literal(switch (literal.kind()) {
                    case BOOLEAN -> Boolean.parseBoolean(literal.spelling());
                    case INTEGER -> Integer.parseInt(literal.spelling());
                    case DECIMAL -> new BigDecimal(literal.spelling());
                    case STRING -> literal.spelling().substring(1, literal.spelling().length() - 1).translateEscapes();
                });
            }
            case KeywordExpression.Prefix prefix -> new NativeBehaviorExpression.Prefix(prefix.operator(),
                    childOperand(prefix.operand(), bindings, literals));
            case KeywordExpression.Binary binary -> new NativeBehaviorExpression.Binary(binary.operator(),
                    childOperand(binary.left(), bindings, literals), childOperand(binary.right(), bindings, literals));
            default -> null;
        };
    }

    private static NativeBehaviorExpression childOperand(KeywordExpression expression,
                                                         Map<String, NativeBehaviorExpression> bindings,
                                                         Consumer<KeywordExpression.Literal> literals) {
        var child = operand(expression, bindings, literals);
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
