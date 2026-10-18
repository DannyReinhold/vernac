// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import com.palantir.javapoet.JavaFile;
import com.sun.source.tree.*;
import com.sun.source.util.*;

import org.vernac.compiler.ast.*;
import org.vernac.compiler.mapping.ResolvedMapping;
import org.vernac.compiler.pipeline.ResolvedProject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.tools.*;

/** Maps adapter-local Java bodies and explicit declarative assignments, never implicit defaults. */
final class MappingSourceMaps {
    static void add(
            List<BehaviorSourceMap.Entry> output,
            Map<String, JavaFile> generated,
            CompilationUnitNode unit,
            ResolvedProject project,
            String source,
            String original) {
        for (var definition : unit.definitions()) {
            String name, pkg;
            List<ResolvedMapping> mappings;
            if (definition instanceof OutboundAdapterNode a) {
                name = a.name();
                pkg = unit.namespace() + ".adapter.outbound.mapping";
                mappings = project.mappings().getOrDefault(a, List.of());
            } else if (definition instanceof InboundHttpAdapterNode a) {
                name = a.name();
                pkg = unit.namespace() + ".adapter.inbound.http.mapping";
                var plan = project.inboundAdapters().get(a);
                mappings = plan == null ? List.of() : plan.mappings();
                if (plan != null)
                    inboundBindings(
                            output,
                            generated.get(unit.namespace() + ".adapter.inbound.http." + a.name()),
                            plan,
                            source,
                            original);
            } else continue;
            for (var mapping : mappings) {
                var node = mapping.source();
                if (node.javaBody().isPresent())
                    BehaviorSourceMap.add(
                            output,
                            generated.get(pkg + ".__VernacMapping_" + name + "_" + node.name()),
                            List.of(
                                    new BehaviorSourceMap.Body(
                                            node.location(),
                                            node.name(),
                                            node.javaBody().orElseThrow(),
                                            false)),
                            source,
                            original);
                else if (node.implementation().isEmpty())
                    declarative(
                            output,
                            generated.get(pkg + "." + name + "Mappings"),
                            mapping,
                            unit.namespace() + "." + name + "." + node.name(),
                            source,
                            original);
            }
        }
    }

    private static void inboundBindings(
            List<BehaviorSourceMap.Entry> output,
            JavaFile file,
            org.vernac.compiler.inbound.ResolvedInbound plan,
            String source,
            String original) {
        if (file == null) return;
        String quoted =
                file.typeSpec().fieldSpecs().stream()
                        .filter(f -> f.name().equals(BehaviorSourceMap.HASH_FIELD))
                        .map(f -> f.initializer().toString())
                        .findFirst()
                        .orElse("");
        if (!quoted.matches("\"[0-9a-f]{64}\"")) return;
        String java = file.toString(), identity = file.packageName() + "." + file.typeSpec().name();
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("Inbound debug information requires a JDK");
        var input =
                new SimpleJavaFileObject(
                        URI.create("string:///Controller.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignored) {
                        return java;
                    }
                };
        try (var manager =
                compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    d -> {},
                                    List.of("-proc:none"),
                                    null,
                                    List.of(input));
            var tree = task.parse().iterator().next();
            var positions = Trees.instance(task).getSourcePositions();
            var methods = new HashMap<String, MethodTree>();
            for (var declaration : tree.getTypeDecls())
                if (declaration instanceof ClassTree c)
                    for (var member : c.getMembers())
                        if (member instanceof MethodTree m) methods.put(m.getName().toString(), m);
            for (var endpoint : plan.endpoints()) {
                String methodName = endpoint.source().view().isPresent()
                        ? "__" + endpoint.source().name() : endpoint.source().name();
                var method = methods.get(methodName);
                if (method == null || method.getBody() == null) continue;
                var statements = method.getBody().getStatements();
                if (!endpoint.source().view().isPresent()) {
                    if (statements.size() != 1 || !(statements.getFirst() instanceof TryTree block)) continue;
                    statements = block.getBlock().getStatements();
                }
                var variables = statements.stream()
                        .filter(s -> s instanceof VariableTree v && v.getName().toString().startsWith("__arg"))
                        .map(s -> (VariableTree) s).toList();
                var arguments =
                        endpoint.arguments().stream().filter(a -> a.source() != null).toList();
                if (variables.size() != arguments.size()) continue;
                List<BehaviorSourceMap.Entry> entries = new ArrayList<>();
                boolean valid = true;
                for (int i = 0; i < arguments.size(); i++) {
                    var variable = variables.get(i);
                    String target = arguments.get(i).target().name();
                    var declarations =
                            endpoint.source().arguments().stream()
                                    .filter(a -> a.target().equals(target))
                                    .toList();
                    if (!variable.getName().contentEquals("__arg" + i)
                            || variable.getInitializer() == null
                            || declarations.size() != 1) {
                        valid = false;
                        break;
                    }
                    long offset = positions.getStartPosition(tree, variable);
                    if (offset < 0 || declarations.getFirst().location().line() < 1) {
                        valid = false;
                        break;
                    }
                    entries.add(
                            new BehaviorSourceMap.Entry(
                                    identity,
                                    methodName,
                                    identity.replace('.', '/') + ".java",
                                    (int) tree.getLineMap().getLineNumber(offset),
                                    source,
                                    declarations.getFirst().location().line(),
                                    BehaviorSourceMap.hash(original),
                                    BehaviorSourceMap.hash(java),
                                    quoted.substring(1, quoted.length() - 1)));
                }
                if (valid) output.addAll(entries);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Cannot parse generated inbound binding for debug information", e);
        }
    }

    private static void declarative(
            List<BehaviorSourceMap.Entry> output,
            JavaFile file,
            ResolvedMapping mapping,
            String label,
            String source,
            String original) {
        if (file == null || mapping.source().assignments().isEmpty()) return;
        String quoted =
                file.typeSpec().fieldSpecs().stream()
                        .filter(f -> f.name().equals(BehaviorSourceMap.HASH_FIELD))
                        .map(f -> f.initializer().toString())
                        .findFirst()
                        .orElse("");
        if (!quoted.matches("\"[0-9a-f]{64}\"")) return;
        String java = file.toString(), identity = file.packageName() + "." + file.typeSpec().name();
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("Mapping debug information requires a JDK");
        var input =
                new SimpleJavaFileObject(
                        URI.create("string:///Mappings.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignored) {
                        return java;
                    }
                };
        try (var manager =
                compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    d -> {},
                                    List.of("-proc:none"),
                                    null,
                                    List.of(input));
            var tree = task.parse().iterator().next();
            var positions = Trees.instance(task).getSourcePositions();
            var methods = new ArrayList<MethodTree>();
            for (var type : tree.getTypeDecls())
                if (type instanceof ClassTree c)
                    for (var member : c.getMembers())
                        if (member instanceof MethodTree m
                                && m.getName().contentEquals(mapping.source().name()))
                            methods.add(m);
            if (methods.size() != 1 || methods.getFirst().getBody() == null) return;
            var statements = methods.getFirst().getBody().getStatements();
            // Generated body: non-null source check, one local per target field, factory return.
            if (statements.size() != mapping.assignments().size() + 2
                    || !(statements.getFirst() instanceof ExpressionStatementTree)
                    || !(statements.getLast() instanceof ReturnTree)) return;
            List<BehaviorSourceMap.Entry> entries = new ArrayList<>();
            for (int i = 0; i < mapping.assignments().size(); i++) {
                var assignment = mapping.assignments().get(i);
                if (!(statements.get(i + 1) instanceof VariableTree variable)
                        || !variable.getName().contentEquals("__mapped" + i)) return;
                // Bind the generated target-path literal to the resolved target field. This also
                // handles declarations whose order differs from the target's constructor order.
                if (assignment.path() == null) continue;
                if (!(variable.getInitializer() instanceof MethodInvocationTree call)
                        || call.getArguments().size() != 3
                        || !(call.getArguments().get(0) instanceof LiteralTree context)
                        || !label.equals(context.getValue())
                        || !(call.getArguments().get(1) instanceof LiteralTree path)
                        || !("$." + assignment.target().name()).equals(path.getValue())) return;
                var declarations =
                        mapping.source().assignments().stream()
                                .filter(a -> a.target().equals(assignment.target().name()))
                                .toList();
                if (declarations.size() != 1) return;
                int line = declarations.getFirst().location().line();
                long offset = positions.getStartPosition(tree, variable);
                if (offset < 0 || line < 1) return;
                entries.add(
                        new BehaviorSourceMap.Entry(
                                identity,
                                mapping.source().name(),
                                identity.replace('.', '/') + ".java",
                                (int) tree.getLineMap().getLineNumber(offset),
                                source,
                                line,
                                BehaviorSourceMap.hash(original),
                                BehaviorSourceMap.hash(java),
                                quoted.substring(1, quoted.length() - 1)));
            }
            output.addAll(entries);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Cannot parse generated mapping for debug information", e);
        }
    }
}
