// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.parser.*;
import java.util.*;

/** Structural diagnostics for the explicit validation receiver, not a Java type checker. */
final class ValidationContracts {
    static List<CompilerDiagnostic> validate(TopLevelDefinition definition, String namespace, VernacProject project) {
        List<FieldNode> fields; List<MethodNode> methods; List<ValidationRuleNode> rules;
        List<JavaImportNode> imports; String name; boolean value;
        if (definition instanceof ValueObjectNode v) {
            fields=v.fields(); methods=v.methods(); rules=v.validations(); imports=v.javaImports(); name=v.name(); value=true;
        } else {
            var mutable = MutableDomain.of(definition);
            if (mutable.isEmpty()) return List.of();
            var m=mutable.get(); fields=m.fields(); methods=m.methods(); imports=m.imports(); name=m.name(); value=false;
            rules=definition instanceof EntityNode e ? e.validations() : ((AggregateNode)definition).validations();
        }
        if (rules.isEmpty()) return List.of();
        var errors = new ArrayList<CompilerDiagnostic>();
        Set<String> generated = new HashSet<>(List.of("__VernacValidation_" + name));

        for (String type : generated) {
            project.symbols().find(namespace + "." + type).ifPresent(symbol -> errors.add(CompilerDiagnostic.error(symbol.location(),
                    "Type '" + type + "' conflicts with a generated validation type.")));
            for (var imported : imports) if (imported.target().endsWith("." + type))
                errors.add(CompilerDiagnostic.error(imported.location(), "Java import conflicts with generated validation type '" + type + "'. Use its fully qualified name in Java code."));
        }
        Set<String> members = new HashSet<>();
        fields.forEach(f -> members.add(f.name())); methods.forEach(m -> members.add(m.name()));
        if (!value) members.addAll(List.of("id", "createdAt", "updatedAt", "version"));
        for (var rule : rules) {
            var lexer = new VernacLexer(CharStreams.fromString(rule.condition())); lexer.removeErrorListeners();
            var parser = new VernacParser(new CommonTokenStream(lexer)); parser.removeErrorListeners();
            Set<String> bare = new LinkedHashSet<>(); collect(parser.expression(), members, bare);
            for (String member : bare) errors.add(CompilerDiagnostic.error(rule.location(),
                    "Validation cannot access '" + member + "' directly. Use self." + member
                            + "(...) through the read interface; private helpers are not exposed."));
        }
        return errors;
    }
    private static void collect(ParseTree tree, Set<String> members, Set<String> bare) {
        if (tree instanceof VernacParser.PrimaryExpressionContext primary && primary.variableName() != null) {
            String name = primary.variableName().getText();
            if (!name.equals("self") && members.contains(name)) bare.add(name);
        }
        for (int i=0; i<tree.getChildCount(); i++) collect(tree.getChild(i), members, bare);
    }
}
