// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.debug;

import com.palantir.javapoet.JavaFile;
import com.sun.source.tree.*;
import com.sun.source.util.*;

import org.vernac.compiler.ast.*;
import org.vernac.compiler.generator.ValidationConditions;
import org.vernac.compiler.util.ConditionUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.tools.*;

/** Maps one entry point per require; generated throw scaffolding is not a second breakpoint. */
final class ValidationSourceMaps {
    private record Method(String name, List<ValidationRuleNode> rules, boolean domain) {}

    static void add(
            List<BehaviorSourceMap.Entry> output,
            Map<String, JavaFile> generated,
            CompilationUnitNode unit,
            String source,
            String original) {
        String domain = unit.namespace() + ".domain.__VernacValidation_";
        for (var definition : unit.definitions()) {
            if (definition instanceof ValueObjectNode v)
                add(
                        output,
                        generated.get(domain + v.name()),
                        List.of(new Method("validate", v.validations(), true)),
                        source,
                        original);
            else if (definition instanceof EventNode e)
                add(
                        output,
                        generated.get(domain + e.name()),
                        List.of(new Method("validate", e.validations(), true)),
                        source,
                        original);
            else if (definition instanceof EntityNode e)
                add(
                        output,
                        generated.get(domain + e.name()),
                        List.of(new Method("validate", e.validations(), true)),
                        source,
                        original);
            else if (definition instanceof AggregateNode a)
                add(
                        output,
                        generated.get(domain + a.name()),
                        List.of(new Method("validate", a.validations(), true)),
                        source,
                        original);
            else if (definition instanceof UseCaseNode u)
                add(
                        output,
                        generated.get(unit.namespace() + ".usecase.__VernacValidation_" + u.name()),
                        List.of(new Method("validate", u.validations(), false)),
                        source,
                        original);
            else if (definition instanceof DomainServiceNode s)
                addService(output, generated.get(domain + s.name()), s.methods(), source, original);
        }
    }

    private static void addService(
            List<BehaviorSourceMap.Entry> output,
            JavaFile file,
            List<ServiceMethodNode> operations,
            String source,
            String original) {
        List<Method> methods = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            var rules = operations.get(i).validations();
            if (!rules.isEmpty()) methods.add(new Method("validate" + i, rules, false));
        }
        add(output, file, methods, source, original);
    }

    private static void add(
            List<BehaviorSourceMap.Entry> output,
            JavaFile file,
            List<Method> methods,
            String source,
            String original) {
        if (file == null
                || methods.isEmpty()
                || methods.stream().anyMatch(m -> m.rules().isEmpty())) return;
        String quoted =
                file.typeSpec().fieldSpecs().stream()
                        .filter(f -> f.name().equals(BehaviorSourceMap.HASH_FIELD))
                        .map(f -> f.initializer().toString())
                        .findFirst()
                        .orElse("");
        if (!quoted.matches("\"[0-9a-f]{64}\"")) return;
        String java = file.toString(), identity = file.packageName() + "." + file.typeSpec().name();
        StringBuilder expected = new StringBuilder("class Expected {");
        for (var method : methods) {
            expected.append("void ").append(method.name()).append("() {");
            for (var rule : method.rules())
                expected.append("if (")
                        .append(
                                method.domain()
                                        ? ValidationConditions.failure(rule.condition())
                                        : ConditionUtils.negate(rule.condition()))
                        .append(") {}\n");
            expected.append("}\n");
        }
        expected.append("}");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("Validation debug mapping requires a JDK");
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
                                    List.of(
                                            input("Actual", java),
                                            input("Expected", expected.toString())));
            var parsed = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(parsed::add);
            if (parsed.size() != 2) return;
            var positions = Trees.instance(task).getSourcePositions();
            var actual = methods(parsed.get(0));
            var wanted = methods(parsed.get(1));
            if (actual.size() != methods.size() || wanted.size() != methods.size()) return;
            List<BehaviorSourceMap.Entry> entries = new ArrayList<>();
            for (var method : methods) {
                var body = actual.get(method.name());
                var check = wanted.get(method.name());
                if (body == null
                        || body.getBody() == null
                        || check == null
                        || check.getBody() == null) return;
                var statements = body.getBody().getStatements();
                var expectedStatements = check.getBody().getStatements();
                if (statements.size() != method.rules().size()
                        || expectedStatements.size() != method.rules().size()) return;
                for (int i = 0; i < statements.size(); i++) {
                    if (!(statements.get(i) instanceof IfTree condition)
                            || !(expectedStatements.get(i) instanceof IfTree expectedCondition)
                            || !condition
                                    .getCondition()
                                    .toString()
                                    .equals(expectedCondition.getCondition().toString())
                            || condition.getElseStatement() != null
                            || !(condition.getThenStatement() instanceof BlockTree block)
                            || block.getStatements().size() != 1
                            || !(block.getStatements().getFirst() instanceof ThrowTree)) return;
                    long offset =
                            positions.getStartPosition(
                                    parsed.get(0), firstExpression(condition.getCondition()));
                    var rule = method.rules().get(i);
                    if (offset < 0 || rule.location().line() < 1) return;
                    entries.add(
                            new BehaviorSourceMap.Entry(
                                    identity,
                                    method.name(),
                                    identity.replace('.', '/') + ".java",
                                    (int) parsed.get(0).getLineMap().getLineNumber(offset),
                                    source,
                                    rule.location().line(),
                                    BehaviorSourceMap.hash(original),
                                    BehaviorSourceMap.hash(java),
                                    quoted.substring(1, quoted.length() - 1)));
                }
            }
            output.addAll(entries);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Cannot parse generated validation for debug mapping", e);
        }
    }

    private static ExpressionTree firstExpression(ExpressionTree expression) {
        return switch (expression) {
            case ParenthesizedTree p -> firstExpression(p.getExpression());
            case UnaryTree u -> firstExpression(u.getExpression());
            case TypeCastTree c -> firstExpression(c.getExpression());
            case BinaryTree b -> firstExpression(b.getLeftOperand());
            case ConditionalExpressionTree c -> firstExpression(c.getCondition());
            default -> expression;
        };
    }

    private static Map<String, MethodTree> methods(CompilationUnitTree unit) {
        Map<String, MethodTree> result = new LinkedHashMap<>();
        for (var declaration : unit.getTypeDecls())
            if (declaration instanceof ClassTree type)
                for (var member : type.getMembers())
                    if (member instanceof MethodTree method && method.getReturnType() != null)
                        result.put(method.getName().toString(), method);
        return result;
    }

    private static JavaFileObject input(String name, String text) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return text;
            }
        };
    }
}
