// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.VernacParser;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.tooling.BehaviorJavaProjection.*;

/** Projects service Java bodies and each operation's separate validation receiver. */
final class ServiceJavaProjection {
    List<Block> build(VernacProject project, FileTypeScope scope, CompilationUnitNode unit,
            DomainServiceNode service, VernacParser.DomainServiceDefinitionContext syntax, String text) {
        try {
            var helper = new UseCaseJavaProjection();
            String pkg = unit.namespace() + ".domain", access = pkg + ".access." + service.name() + "Access";
            var imports = new TreeMap<>(BehaviorImports.visible(project, scope));
            service.javaImports().forEach(i -> imports.put(i.target().substring(i.target().lastIndexOf('.') + 1), i.target()));
            var header = new StringBuilder("package " + pkg + ";\n");
            imports.values().stream().filter(n -> n.contains(".")).distinct().forEach(n -> header.append("import ").append(n).append(";\n"));
            String classHeader = header + "@org.jspecify.annotations.NullMarked final class __VernacEditorBehavior_" + service.name() + " {\n";
            List<Block> blocks = new ArrayList<>();
            List<Fragment> bodies = new ArrayList<>();
            for (int i = 0; i < service.methods().size(); i++) {
                var operation = service.methods().get(i); var method = operation.method(); var node = syntax.serviceMethod(i);
                if (node.rawJavaBlock() != null) {
                    List<String> parameters = new ArrayList<>();
                    if (method.accessModifier().equals("public")) parameters.add(access + " self");
                    for (var p : method.parameters()) parameters.add(type(p.type(), scope) + " " + p.name());
                    helper.add(bodies, node.rawJavaBlock(), text, "static " + type(method.returnType(), scope) + " " + method.name()
                            + "(" + String.join(", ", parameters) + ") {\n", "}\n");
                }
                if (node.validationBlock() != null) {
                    List<Fragment> rules = new ArrayList<>();
                    for (var rule : node.validationBlock().validationStatement()) helper.add(rules, rule.condition, text, "if (", ") {}\n");
                    helper.close(blocks, service.name() + "Validation" + i, rules,
                            classHeader.replace("EditorBehavior", "EditorValidation") + "static void validate(" + access + ".Input" + i + " self) {\n", "}\n}\n");
                }
            }
            helper.close(blocks, service.name(), bodies, classHeader);
            return blocks;
        } catch (IllegalArgumentException | NoSuchElementException incomplete) { return List.of(); }
    }
    private String type(TypeNode type, FileTypeScope scope) {
        String name = type.name().equals("void") ? "void" : JavaTypeNames.canonicalName(scope.resolve(type.name(), type.location()).type().orElseThrow());
        return type.isOptional() ? "java.util.Optional<" + name + ">" : name;
    }
}
