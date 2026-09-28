package org.vernac.compiler.analyzer;

import org.vernac.compiler.ast.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SemanticAnalyzer {

    private static final Set<String> BUILTIN_PRIMITIVES = Set.of(
            "int", "long", "double", "float", "boolean", "byte", "short", "char", "void"
    );

    private static final Set<String> BUILTIN_JDK_TYPES = Set.of(
            "String", "Boolean", "Integer", "Long", "Double", "Float",
            "BigDecimal", "BigInteger", "UUID", "Currency",
            "Instant", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime", "Duration",
            "List", "Set", "Map", "Optional"
    );

    public List<CompilerDiagnostic> analyze(CompilationUnitNode unit) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();

        // 1. Alle in dieser Unit definierten Typen sammeln
        Set<String> declaredTypes = new HashSet<>();
        for (TopLevelDefinition def : unit.definitions()) {
            String name = getDefinitionName(def);
            if (!declaredTypes.add(name)) {
                diagnostics.add(CompilerDiagnostic.error(def.location(), "Duplicate type declaration '" + name + "'"));
            }
        }

        // 2. Verfügbare Typ-Symbole für Auflösungsprüfung
        Set<String> availableSymbols = new HashSet<>(BUILTIN_PRIMITIVES);
        availableSymbols.addAll(BUILTIN_JDK_TYPES);
        availableSymbols.addAll(declaredTypes);

        for (String imp : unit.imports()) {
            if (imp.endsWith(".*")) {
                continue; // Wildcard-Imports können statisch nicht trivial geprüft werden
            }
            int lastDot = imp.lastIndexOf('.');
            if (lastDot >= 0) {
                availableSymbols.add(imp.substring(lastDot + 1));
            }
        }

        // 3. Typ-spezifische Validierungen
        for (TopLevelDefinition def : unit.definitions()) {
            if (def instanceof ValueObjectNode vo) {
                validateValueObject(vo, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof AggregateNode agg) {
                validateAggregate(agg, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof EntityNode entity) {
                validateEntity(entity, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof EventNode event) {
                validateEvent(event, availableSymbols, unit.imports(), diagnostics);
            }
        }

        return diagnostics;
    }

    private void validateValueObject(ValueObjectNode vo, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        // Value Objects müssen immutable sein -> keine mut-Felder erlaubt
        for (FieldNode field : vo.fields()) {
            if (field.isMutable()) {
                diagnostics.add(CompilerDiagnostic.error(
                        field.location(),
                        "Value Object '" + vo.name() + "' cannot have mutable field '" + field.name() + "'. Value Objects must be strictly immutable."
                ));
            }
        }
        checkDuplicateFields(vo.fields(), vo.name(), diagnostics);
        for (FieldNode field : vo.fields()) {
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
    }

    private void validateAggregate(AggregateNode agg, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        // ID-Typ prüfen
        validateTypeResolvable(agg.idDefinition().type(), availableSymbols, imports, diagnostics);

        // ID-Feldname darf nicht mit Nutzlast-Feldern kollidieren
        String idName = agg.idDefinition().fieldName();
        boolean hasCollidingField = agg.fields().stream().anyMatch(f -> f.name().equals(idName));
        if (hasCollidingField) {
            diagnostics.add(CompilerDiagnostic.error(
                    agg.idDefinition().location(),
                    "Aggregate '" + agg.name() + "' defines field '" + idName + "' twice (as id and payload field)."
            ));
        }

        checkDuplicateFields(agg.fields(), agg.name(), diagnostics);
        for (FieldNode field : agg.fields()) {
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
    }

    private void validateEntity(EntityNode entity, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        validateTypeResolvable(entity.idDefinition().type(), availableSymbols, imports, diagnostics);

        String idName = entity.idDefinition().fieldName();
        boolean hasCollidingField = entity.fields().stream().anyMatch(f -> f.name().equals(idName));
        if (hasCollidingField) {
            diagnostics.add(CompilerDiagnostic.error(
                    entity.idDefinition().location(),
                    "Entity '" + entity.name() + "' defines field '" + idName + "' twice (as id and payload field)."
            ));
        }

        checkDuplicateFields(entity.fields(), entity.name(), diagnostics);
        for (FieldNode field : entity.fields()) {
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
    }

    private void validateEvent(EventNode event, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        for (FieldNode field : event.fields()) {
            if (field.isMutable()) {
                diagnostics.add(CompilerDiagnostic.error(
                        field.location(),
                        "Domain Event '" + event.name() + "' cannot have mutable field '" + field.name() + "'. Events must represent past facts and be immutable."
                ));
            }
        }
        checkDuplicateFields(event.fields(), event.name(), diagnostics);
        for (FieldNode field : event.fields()) {
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
    }

    private void checkDuplicateFields(List<FieldNode> fields, String parentName, List<CompilerDiagnostic> diagnostics) {
        Set<String> seen = new HashSet<>();
        for (FieldNode field : fields) {
            if (!seen.add(field.name())) {
                diagnostics.add(CompilerDiagnostic.error(
                        field.location(),
                        "Duplicate field name '" + field.name() + "' in '" + parentName + "'"
                ));
            }
        }
    }

    private void validateTypeResolvable(TypeNode type, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        String typeName = type.name();

        boolean hasWildcardImport = imports.stream().anyMatch(i -> i.endsWith(".*"));
        boolean isFullyQualified = typeName.contains(".");

        if (!isFullyQualified && !hasWildcardImport && !availableSymbols.contains(typeName)) {
            diagnostics.add(CompilerDiagnostic.error(
                    type.location(),
                    "Cannot resolve type '" + typeName + "'. Did you forget an import?"
            ));
        }

        for (TypeNode typeArg : type.typeArguments()) {
            validateTypeResolvable(typeArg, availableSymbols, imports, diagnostics);
        }
    }

    private String getDefinitionName(TopLevelDefinition def) {
        if (def instanceof ValueObjectNode vo) return vo.name();
        if (def instanceof AggregateNode agg) return agg.name();
        if (def instanceof EntityNode entity) return entity.name();
        if (def instanceof EventNode event) return event.name();
        if (def instanceof ServiceNode service) return service.name();
        throw new IllegalArgumentException("Unknown definition: " + def);
    }
}