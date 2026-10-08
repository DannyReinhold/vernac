// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

import java.util.*;

/** Editor-only Java fragments. Offsets refer to UTF-16 in the original Vernac document. */
public final class BehaviorJavaProjection {
    public record Fragment(int start, int end, String prefix, String suffix) { }
    public record Block(String owner, List<Fragment> fragments) {
        public Block { fragments = List.copyOf(fragments); }
    }

    public List<Block> build(VernacProject project, VernacSourceFile source, String text) {
        var unit = source.unit();
        var namespaces = new HashSet<String>();
        project.sources().forEach(s -> namespaces.add(s.unit().namespace()));
        var scope = new FileTypeScope(unit, project.symbols(), namespaces);
        if (scope.hasErrors()) return List.of();
        var lexer = new VernacLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        var tree = parser.compilationUnit();
        if (parser.getNumberOfSyntaxErrors() != 0 || tree.topLevelDeclaration().size() != unit.definitions().size())
            return List.of();
        List<Block> result = new ArrayList<>();
        for (int i = 0; i < unit.definitions().size(); i++) {
            var definition = unit.definitions().get(i);
            var syntax = tree.topLevelDeclaration(i);
            if (definition instanceof ValueObjectNode value) {
                add(result, project, scope, unit, value.name(), value.methods(), value.javaImports(),
                        syntax.valueDefinition().behaviorBlock(), text);
            }
            MutableDomain.of(definition).ifPresent(model -> add(result, project, scope, unit, model.name(), model.methods(), model.imports(),
                    syntax.entityDefinition() != null ? syntax.entityDefinition().behaviorBlock() : syntax.aggregateDefinition().behaviorBlock(), text));
            VernacParser.ValidationBlockContext validations = syntax.valueDefinition() != null ? syntax.valueDefinition().validationBlock()
                    : syntax.entityDefinition() != null ? syntax.entityDefinition().validationBlock()
                    : syntax.aggregateDefinition() != null ? syntax.aggregateDefinition().validationBlock() : null;
            if (validations != null) {
                if (definition instanceof ValueObjectNode value) addValidation(result, project, scope, unit, value.name(), value.javaImports(), validations, text);
                else MutableDomain.of(definition).ifPresent(model -> addValidation(result, project, scope, unit, model.name(), model.imports(), validations, text));
            }
            var collection = CollectionDeclaration.of(definition);
            if (collection.isPresent()) {
                var c = collection.get();
                VernacParser.CollectionDefinitionContext context = null;
                if (syntax.valueDefinition() != null) context = syntax.valueDefinition().collectionDefinition();
                else if (syntax.idDeclaration() != null) context = syntax.idDeclaration().collectionDefinition();
                else if (syntax.entityDefinition() != null) context = syntax.entityDefinition().collectionDefinition();
                else if (syntax.aggregateDefinition() != null) context = syntax.aggregateDefinition().collectionDefinition();
                if (context != null) add(result, project, scope, unit, c.name(), c.definition().customMethods(),
                        c.definition().javaImports(), context.behaviorBlock(), text);
            }
        }
        return List.copyOf(result);
    }

    private void addValidation(List<Block> result, VernacProject project, FileTypeScope scope,
                               CompilationUnitNode unit, String owner, List<JavaImportNode> imports,
                               VernacParser.ValidationBlockContext context, String text) {
        if (context.validationStatement().isEmpty()) return;
        Map<String, String> visible = new TreeMap<>(BehaviorImports.visible(project, scope));
        imports.forEach(i -> visible.put(i.target().substring(i.target().lastIndexOf('.') + 1), i.target()));
        StringBuilder header = new StringBuilder("package " + unit.namespace() + ".domain;\n");
        visible.values().stream().filter(n -> n.contains(".")).distinct()
                .forEach(n -> header.append("import ").append(n).append(";\n"));
        header.append("@org.jspecify.annotations.NullMarked final class __VernacEditorValidation_")
                .append(owner).append(" { static void validate(").append(unit.namespace()).append(".domain.")
                .append(owner).append("Read self) {\n");
        List<Fragment> fragments = new ArrayList<>();
        for (var rule : context.validationStatement()) {
            var expression = rule.condition;
            fragments.add(new Fragment(offset(text, expression.getStart().getStartIndex()),
                    offset(text, expression.getStop().getStopIndex() + 1),
                    (fragments.isEmpty() ? header.toString() : "") + "if (!(", ")) {}\n"));
        }
        var last = fragments.removeLast();
        fragments.add(new Fragment(last.start(), last.end(), last.prefix(), last.suffix() + "}\n}\n"));
        result.add(new Block(owner + "Validation", fragments));
    }

    private void add(List<Block> result, VernacProject project, FileTypeScope scope, CompilationUnitNode unit,
                     String owner, List<MethodNode> methods, List<JavaImportNode> imports,
                     VernacParser.BehaviorBlockContext context, String text) {
        if (context == null || methods.size() != context.behaviorMethod().size()) return;
        if (BehaviorImports.validate(project, scope, unit.namespace(), owner, imports, methods).stream()
                .anyMatch(d -> d.severity() == org.vernac.compiler.analyzer.CompilerDiagnostic.Severity.ERROR)) return;
        Map<String, String> visible = new TreeMap<>(BehaviorImports.visible(project, scope));
        imports.forEach(i -> visible.put(i.target().substring(i.target().lastIndexOf('.') + 1), i.target()));
        StringBuilder header = new StringBuilder("package " + unit.namespace() + ".domain;\n");
        visible.values().stream().filter(n -> n.contains(".")).distinct()
                .forEach(n -> header.append("import ").append(n).append(";\n"));
        header.append("@org.jspecify.annotations.NullMarked\nfinal class __VernacEditorBehavior_")
                .append(owner).append(" {\n");
        List<Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            var syntax = context.behaviorMethod(i);
            if (method.implementation().isPresent()) continue;
            String returnType = javaType(scope, method.returnType(), true);
            if (returnType == null) return;
            List<String> parameters = new ArrayList<>();
            if (method.accessModifier().equals("public")) parameters.add(unit.namespace() + ".domain." + owner +
                    (method.mode() == MethodNode.Mode.DEFAULT ? "" : method.mode() == MethodNode.Mode.READ ? "Read" : "Access") + " self");
            for (var p : method.parameters()) {
                String type = javaType(scope, p.type(), false);
                if (type == null) return;
                parameters.add(type + " " + p.name());
            }
            Token open = null, close = null;
            for (int child = 0; child < syntax.getChildCount(); child++) {
                if (syntax.getChild(child) instanceof TerminalNode token) {
                    if (token.getText().equals("{")) open = token.getSymbol();
                    if (token.getText().equals("}")) close = token.getSymbol();
                }
            }
            if (open == null || close == null || open.getStartIndex() < 0 || close.getStartIndex() < 0) return;
            String prefix = (fragments.isEmpty() ? header.toString() : "")
                    + (method.accessModifier().equals("private") ? "private " : "") + "static " + returnType + " ";
            fragments.add(new Fragment(offset(text, syntax.name.getStart().getStartIndex()),
                    offset(text, syntax.name.getStop().getStopIndex() + 1), prefix,
                    "(" + String.join(", ", parameters) + ") {"));
            fragments.add(new Fragment(offset(text, open.getStopIndex() + 1), offset(text, close.getStartIndex()),
                    "", "}\n"));
        }
        if (fragments.isEmpty()) return;
        int last = fragments.size() - 1;
        var f = fragments.get(last);
        fragments.set(last, new Fragment(f.start(), f.end(), f.prefix(), f.suffix() + "}\n"));
        result.add(new Block(owner, fragments));
    }

    private String javaType(FileTypeScope scope, TypeNode type, boolean result) {
        if (type.name().equals("void")) return result && !type.isOptional() ? "void" : null;
        var lookup = scope.resolve(type.name(), type.location());
        if (lookup.type().isEmpty()) return null;
        String name;
        try { name = JavaTypeNames.canonicalName(lookup.type().get()); }
        catch (IllegalArgumentException unsupported) { return null; }
        if (!type.isOptional()) return name;
        if (lookup.type().get() instanceof ResolvedType.Builtin builtin && builtin.javaType().isPrimitive()) return null;
        if (result) return "java.util.Optional<" + name + ">";
        int lastDot = name.lastIndexOf('.');
        return name.substring(0, lastDot + 1) + "@org.jspecify.annotations.Nullable " + name.substring(lastDot + 1);
    }

    private int offset(String text, int codePoints) {
        return text.offsetByCodePoints(0, codePoints);
    }
}
