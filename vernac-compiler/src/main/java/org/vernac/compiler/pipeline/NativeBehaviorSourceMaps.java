// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.ClassName;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.EntityNode;
import org.vernac.compiler.ast.ListenerNode;
import org.vernac.compiler.ast.MethodNode;
import org.vernac.compiler.ast.NativeStatement;
import org.vernac.compiler.ast.UseCaseNode;
import org.vernac.compiler.generator.BehaviorGenerator;
import org.vernac.runtime.NativeBehaviorSources;
import org.vernac.runtime.NativeBehaviorLayout;
import org.vernac.runtime.NativeBehaviorLayout.Instruction;
import org.vernac.runtime.NativeBehaviorLayout.Kind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                    source, method.nativeBody());
        }
    }

    private static void writeOperation(Path output, String namespace, String subpackage, String source, UseCaseNode operation)
            throws IOException {
        if (operation.nativeBody().isEmpty()) return;
        var companion = BehaviorGenerator.companion(ClassName.get(namespace + subpackage, operation.name()));
        writeProgram(output, companion, 0, namespace + "." + operation.name() + ".execute", source, operation.nativeBody());
    }

    private static void writeProgram(Path output, ClassName companion, int program, String declaration, String source,
                                     List<NativeStatement> statements) throws IOException {
        var positions = new ArrayList<NativeBehaviorSources.Position>();
        collect(statements, "", positions);
        var file = output.resolve(NativeBehaviorSources.resource(companion.canonicalName(), program));
        Files.createDirectories(file.getParent());
        try (var stream = Files.newOutputStream(file)) {
            NativeBehaviorSources.write(stream, declaration, source, positions);
        }
        var layout = output.resolve(NativeBehaviorLayout.resource(companion.canonicalName(), program));
        try (var stream = Files.newOutputStream(layout)) {
            NativeBehaviorLayout.write(stream, statements.stream().map(NativeBehaviorSourceMaps::instruction).toList());
        }
    }

    private static Instruction instruction(NativeStatement statement) {
        return switch (statement) {
            case NativeStatement.Let ignored -> new Instruction(Kind.BIND);
            case NativeStatement.Require ignored -> new Instruction(Kind.REQUIRE);
            case NativeStatement.Assign ignored -> new Instruction(Kind.ASSIGN);
            case NativeStatement.Send ignored -> new Instruction(Kind.SEND);
            case NativeStatement.Return ignored -> new Instruction(Kind.RETURN);
            case NativeStatement.Branch branch -> new Instruction(Kind.BRANCH,
                    branch.onTrue().stream().map(NativeBehaviorSourceMaps::instruction).toList(),
                    branch.onFalse().stream().map(NativeBehaviorSourceMaps::instruction).toList());
        };
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
