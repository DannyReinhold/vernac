package org.vernac.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.vernac.compiler.ast.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VernacSemanticValidator {

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "UUID", "String", "Integer", "Long", "BigDecimal", "Boolean",
            "Instant", "LocalDate", "List", "Set", "Map", "void", "byte", "int", "long", "boolean"
    );

    public List<Diagnostic> validate(CompilationUnitNode cu) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        Set<String> declaredTypes = new HashSet<>(BUILTIN_TYPES);
        Set<String> declaredAggregates = new HashSet<>();

        // 1. Imports erfassen
        for (String imp : cu.imports()) {
            String simpleName = imp.substring(imp.lastIndexOf('.') + 1);
            if (!simpleName.equals("*")) {
                declaredTypes.add(simpleName);
            }
        }

        // 2. Deklarationen einsammeln & Duplikate prüfen
        for (TopLevelDefinition def : cu.definitions()) {
            if (def instanceof ValueObjectNode val) {
                registerType(val.name(), val.location(), declaredTypes, diagnostics);
            } else if (def instanceof EntityNode ent) {
                registerType(ent.name(), ent.location(), declaredTypes, diagnostics);
            } else if (def instanceof AggregateNode agg) {
                registerType(agg.name(), agg.location(), declaredTypes, diagnostics);
                declaredAggregates.add(agg.name());
            } else if (def instanceof EventNode evt) {
                registerType(evt.name(), evt.location(), declaredTypes, diagnostics);
            }
        }

        // 3. Typ-Verwendungen & Repository-Ziele validieren
        for (TopLevelDefinition def : cu.definitions()) {
            if (def instanceof AggregateNode agg) {
                checkType(agg.idDefinition().type(), declaredTypes, diagnostics);
                for (FieldNode field : agg.fields()) {
                    checkType(field.type(), declaredTypes, diagnostics);
                }
            } else if (def instanceof EntityNode ent) {
                checkType(ent.idDefinition().type(), declaredTypes, diagnostics);
                for (FieldNode field : ent.fields()) {
                    checkType(field.type(), declaredTypes, diagnostics);
                }
            } else if (def instanceof ValueObjectNode val) {
                for (FieldNode field : val.fields()) {
                    checkType(field.type(), declaredTypes, diagnostics);
                }
            } else if (def instanceof EventNode evt) {
                for (FieldNode field : evt.fields()) {
                    checkType(field.type(), declaredTypes, diagnostics);
                }
            } else if (def instanceof RepositoryNode repo) {
                if (!declaredAggregates.contains(repo.aggregateName())) {
                    diagnostics.add(createDiagnostic(
                            repo.location(),
                            repo.aggregateName(),
                            "Repository target '" + repo.aggregateName() + "' must be an existing aggregate root.",
                            DiagnosticSeverity.Error
                    ));
                }
            }
        }

        return diagnostics;
    }

    private void checkType(TypeNode type, Set<String> declaredTypes, List<Diagnostic> diagnostics) {
        if (type == null) {
            return;
        }

        if (!declaredTypes.contains(type.name())) {
            diagnostics.add(createDiagnostic(
                    type.location(),
                    type.name(),
                    "Cannot resolve symbol '" + type.name() + "'. Is the type declared or imported?",
                    DiagnosticSeverity.Error
            ));
        }

        for (TypeNode arg : type.typeArguments()) {
            checkType(arg, declaredTypes, diagnostics);
        }
    }

    private void registerType(String name, SourceLocation loc, Set<String> declaredTypes, List<Diagnostic> diagnostics) {
        if (declaredTypes.contains(name) && !BUILTIN_TYPES.contains(name)) {
            diagnostics.add(createDiagnostic(
                    loc,
                    name,
                    "Duplicate declaration of type '" + name + "'.",
                    DiagnosticSeverity.Error
            ));
        }
        declaredTypes.add(name);
    }

    private Diagnostic createDiagnostic(SourceLocation loc, String text, String message, DiagnosticSeverity severity) {
        // SourceLocation ist 1-basiert, LSP Range arbeitet 0-basiert
        int line = Math.max(0, loc.line() - 1);
        int startCol = Math.max(0, loc.column() - 1);
        int endCol = startCol + (text != null ? text.length() : 1);

        Range range = new Range(new Position(line, startCol), new Position(line, endCol));
        Diagnostic d = new Diagnostic(range, message);
        d.setSeverity(severity);
        d.setSource("vernac-semantic");
        return d;
    }
}