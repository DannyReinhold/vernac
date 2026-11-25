// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import org.antlr.v4.runtime.tree.TerminalNode;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.debug.BehaviorSourceMap;
import org.vernac.compiler.pipeline.EditorSourceParser;
import org.vernac.compiler.symbols.BuiltinTypes;
import org.vernac.language.VernacNames;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Previews structured changes to a single source without writing it. */
public final class VernacModelEdits {
    private VernacModelEdits() { }

    /** The insertion offset is measured in UTF-16 code units, as used by Java strings. */
    public record Insertion(Path source, String expectedSourceHash, int offset, String text) {
        public String applyTo(String currentSource) {
            if (!expectedSourceHash.equals(BehaviorSourceMap.hash(currentSource)))
                throw new IllegalArgumentException("Source changed since the structural edit was prepared");
            return currentSource.substring(0, offset) + text + currentSource.substring(offset);
        }
    }

    public record AggregateField(String name, String type, boolean optional, boolean mutable) { }

    /** Prepares the contents of a new source. The caller supplies its target path and base revision. */
    public static String aggregateSource(String namespace, String name, String idType, boolean declareId,
                                         List<AggregateField> fields) {
        if (!VernacNames.isNamespace(namespace) || !VernacNames.isTypeName(name))
            throw new IllegalArgumentException("Expected a valid aggregate namespace and name");
        if (!VernacNames.isNamespace(idType) || declareId && !VernacNames.isTypeName(idType)
                || name.equals(idType)) {
            throw new IllegalArgumentException("Expected a distinct ID type; a new ID must have a simple name");
        }
        var names = new HashSet<String>();
        var parameters = new ArrayList<String>();
        for (var field : fields) {
            checkField(field.name(), field.type());
            if (!names.add(field.name()))
                throw new IllegalArgumentException("Duplicate aggregate field '" + field.name() + "'");
            parameters.add((field.mutable() ? "mut " : "") + field.type() + (field.optional() ? "? " : " ")
                    + field.name());
        }
        return "namespace " + namespace + ";\n\n" + (declareId ? "id " + idType + ";\n" : "")
                + "aggregate " + name + "[" + idType + "](" + String.join(", ", parameters) + ");\n";
    }

    public static Insertion addValueField(Path source, String text, String valueIdentity,
                                          String fieldName, String fieldType, boolean optional) {
        checkField(fieldName, fieldType);

        var diagnostics = new ArrayList<CompilerDiagnostic>();
        var analysis = new EditorSourceParser().analyze(source.toString(), text, diagnostics);
        if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
        var parsed = analysis.orElseThrow(() -> new IllegalArgumentException("No source declaration to edit"));
        var namespace = parsed.unit().namespace();
        var candidates = parsed.unit().valueObjects().stream()
                .filter(candidate -> (namespace + "." + candidate.name()).equals(valueIdentity)).toList();
        if (candidates.size() != 1)
            throw new IllegalArgumentException("Expected one matching value object in this source");
        var value = candidates.getFirst();
        if (value.isEnum()) throw new IllegalArgumentException("Enum declarations do not have fields");
        if (value.fields().stream().anyMatch(field -> field.name().equals(fieldName)))
            throw new IllegalArgumentException("Field '" + fieldName + "' already exists");

        var definition = parsed.declarations().stream().map(d -> d.valueDefinition())
                .filter(d -> d != null && d.name.getText().equals(value.name())).findFirst().orElseThrow();
        var closing = definition.children.stream().filter(TerminalNode.class::isInstance)
                .map(TerminalNode.class::cast).filter(token -> token.getText().equals(")"))
                .findFirst().orElseThrow().getSymbol();
        String prefix = definition.parameterList() == null ? "" : ", ";
        String addition = prefix + fieldType + (optional ? "? " : " ") + fieldName;
        int offset = text.offsetByCodePoints(0, closing.getStartIndex());
        return new Insertion(source, BehaviorSourceMap.hash(text), offset, addition);
    }

    private static void checkField(String fieldName, String fieldType) {
        if (!VernacNames.isIdentifier(fieldName))
            throw new IllegalArgumentException("Invalid field name '" + fieldName + "'");
        if (!VernacNames.isNamespace(fieldType) && (fieldType == null || BuiltinTypes.find(fieldType).isEmpty()))
            throw new IllegalArgumentException("Expected a simple or qualified field type");
    }
}
