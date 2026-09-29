package org.vernac.compiler.analyzer;

import org.vernac.compiler.ast.*;
import org.vernac.compiler.util.TypeUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SemanticAnalyzer {

    private static final Set<String> BUILTIN_JDK_TYPES = Set.of(
            "String", "Boolean", "Integer", "Long", "Double", "Float",
            "BigDecimal", "BigInteger", "UUID", "Currency",
            "Instant", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime", "Duration",
            "List", "Set", "Map", "Optional"
    );

    // Alle relevanten reservierten Java-Wörter
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "yield", "record"
    );

    public List<CompilerDiagnostic> analyze(CompilationUnitNode unit) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();

        if (unit.packageName().isPresent()) {
            validatePackageName(unit.packageName().get(), unit.location(), diagnostics);
        }

        Set<String> declaredTypes = new HashSet<>();
        for (TopLevelDefinition def : unit.definitions()) {
            String name = getDefinitionName(def);
            validateIdentifier(name, def.location(), "type", diagnostics);

            if (!declaredTypes.add(name)) {
                diagnostics.add(CompilerDiagnostic.error(def.location(), "Duplicate type declaration '" + name + "'"));
            }

            if (def instanceof ValueObjectNode vo && vo.collection().isPresent()) {
                String collName = vo.collection().get().customName().orElse(vo.name() + "s");
                validateIdentifier(collName, vo.collection().get().location(), "collection type", diagnostics);
                declaredTypes.add(collName);
            }
        }

        Set<String> availableSymbols = new HashSet<>();
        for (String primitive : TypeUtils.getAllPrimitives()) {
            availableSymbols.add(primitive);
        }
        availableSymbols.addAll(BUILTIN_JDK_TYPES);
        availableSymbols.addAll(declaredTypes);

        for (String imp : unit.imports()) {
            if (imp.endsWith(".*")) continue;
            int lastDot = imp.lastIndexOf('.');
            if (lastDot >= 0) {
                availableSymbols.add(imp.substring(lastDot + 1));
            }
        }

        for (TopLevelDefinition def : unit.definitions()) {
            if (def instanceof ValueObjectNode vo) {
                validateValueObject(vo, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof AggregateNode agg) {
                validateAggregate(agg, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof EntityNode entity) {
                validateEntity(entity, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof EventNode event) {
                validateEvent(event, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof RepositoryNode repo) {
                validateRepository(repo, unit, availableSymbols, diagnostics);
            }
        }

        return diagnostics;
    }

    private void validateValueObject(ValueObjectNode vo, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        if (vo.customPackage().isPresent()) validatePackageName(vo.customPackage().get(), vo.location(), diagnostics);
        checkDuplicateFields(vo.fields(), vo.name(), diagnostics);

        for (FieldNode field : vo.fields()) {
            validateIdentifier(field.name(), field.location(), "field", diagnostics);
            if (field.isMutable()) {
                diagnostics.add(CompilerDiagnostic.error(field.location(), "Value Object '" + vo.name() + "' cannot have mutable field '" + field.name() + "'. Value Objects must be strictly immutable."));
            }
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
        for (MethodNode method : vo.methods()) {
            validateIdentifier(method.name(), method.location(), "method", diagnostics);
            for (FieldNode p : method.parameters())
                validateIdentifier(p.name(), p.location(), "parameter", diagnostics);
        }
    }

    private void validateAggregate(AggregateNode agg, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        if (agg.customPackage().isPresent())
            validatePackageName(agg.customPackage().get(), agg.location(), diagnostics);

        validateTypeResolvable(agg.idDefinition().type(), availableSymbols, imports, diagnostics);
        validateIdentifier(agg.idDefinition().fieldName(), agg.idDefinition().location(), "id field", diagnostics);

        String idName = agg.idDefinition().fieldName();
        if (agg.fields().stream().anyMatch(f -> f.name().equals(idName))) {
            diagnostics.add(CompilerDiagnostic.error(agg.idDefinition().location(), "Aggregate '" + agg.name() + "' defines field '" + idName + "' twice (as id and payload field)."));
        }

        checkDuplicateFields(agg.fields(), agg.name(), diagnostics);
        for (FieldNode field : agg.fields()) {
            validateIdentifier(field.name(), field.location(), "field", diagnostics);
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
        for (MethodNode method : agg.methods()) {
            validateIdentifier(method.name(), method.location(), "method", diagnostics);
            for (FieldNode p : method.parameters())
                validateIdentifier(p.name(), p.location(), "parameter", diagnostics);
        }
    }

    private void validateEntity(EntityNode entity, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        if (entity.customPackage().isPresent())
            validatePackageName(entity.customPackage().get(), entity.location(), diagnostics);

        validateTypeResolvable(entity.idDefinition().type(), availableSymbols, imports, diagnostics);
        validateIdentifier(entity.idDefinition().fieldName(), entity.idDefinition().location(), "id field", diagnostics);

        String idName = entity.idDefinition().fieldName();
        if (entity.fields().stream().anyMatch(f -> f.name().equals(idName))) {
            diagnostics.add(CompilerDiagnostic.error(entity.idDefinition().location(), "Entity '" + entity.name() + "' defines field '" + idName + "' twice (as id and payload field)."));
        }

        checkDuplicateFields(entity.fields(), entity.name(), diagnostics);
        for (FieldNode field : entity.fields()) {
            validateIdentifier(field.name(), field.location(), "field", diagnostics);
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
        for (MethodNode method : entity.methods()) {
            validateIdentifier(method.name(), method.location(), "method", diagnostics);
            for (FieldNode p : method.parameters())
                validateIdentifier(p.name(), p.location(), "parameter", diagnostics);
        }
    }

    private void validateEvent(EventNode event, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        if (event.customPackage().isPresent())
            validatePackageName(event.customPackage().get(), event.location(), diagnostics);

        checkDuplicateFields(event.fields(), event.name(), diagnostics);
        for (FieldNode field : event.fields()) {
            validateIdentifier(field.name(), field.location(), "field", diagnostics);
            if (field.isMutable()) {
                diagnostics.add(CompilerDiagnostic.error(field.location(), "Domain Event '" + event.name() + "' cannot have mutable field '" + field.name() + "'. Events must represent past facts and be strictly immutable."));
            }
            validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
        }
    }

    private void validateRepository(RepositoryNode repo, CompilationUnitNode unit, Set<String> availableSymbols, List<CompilerDiagnostic> diagnostics) {
        if (repo.customPackage().isPresent())
            validatePackageName(repo.customPackage().get(), repo.location(), diagnostics);

        boolean aggregateExists = unit.definitions().stream()
                .anyMatch(d -> d instanceof AggregateNode agg && agg.name().equals(repo.aggregateName()));

        if (!aggregateExists) {
            diagnostics.add(CompilerDiagnostic.error(repo.location(), "Repository target '" + repo.aggregateName() + "' must be an existing aggregate root."));
        }

        Set<String> methodNames = new HashSet<>(Set.of("byId", "save", "delete"));
        for (RepositoryMethodNode method : repo.methods()) {
            validateIdentifier(method.name(), method.location(), "repository method", diagnostics);
            if (!methodNames.add(method.name())) {
                diagnostics.add(CompilerDiagnostic.error(method.location(), "Duplicate or reserved repository method '" + method.name() + "' in '" + repo.name() + "'"));
            }

            validateTypeResolvable(method.returnType(), availableSymbols, unit.imports(), diagnostics);
            for (FieldNode param : method.parameters()) {
                validateIdentifier(param.name(), param.location(), "parameter", diagnostics);
                validateTypeResolvable(param.type(), availableSymbols, unit.imports(), diagnostics);
            }
        }
    }

    private void checkDuplicateFields(List<FieldNode> fields, String parentName, List<CompilerDiagnostic> diagnostics) {
        Set<String> seen = new HashSet<>();
        for (FieldNode field : fields) {
            if (!seen.add(field.name())) {
                diagnostics.add(CompilerDiagnostic.error(field.location(), "Duplicate field name '" + field.name() + "' in '" + parentName + "'"));
            }
        }
    }

    private void validateTypeResolvable(TypeNode type, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        String typeName = type.name();

        if (type.isOptional() && TypeUtils.isPrimitive(typeName)) {
            String wrapper = TypeUtils.getWrapperType(typeName);
            diagnostics.add(CompilerDiagnostic.error(type.location(), "Primitive type '" + typeName + "' cannot be optional. Use the wrapper type '" + wrapper + "?' instead."));
        }

        boolean hasWildcardImport = imports.stream().anyMatch(i -> i.endsWith(".*"));
        boolean isFullyQualified = typeName.contains(".");

        if (!isFullyQualified && !hasWildcardImport && !availableSymbols.contains(typeName)) {
            diagnostics.add(CompilerDiagnostic.error(type.location(), "Cannot resolve type '" + typeName + "'. Did you forget an import?"));
        }

        for (TypeNode typeArg : type.typeArguments()) {
            validateTypeResolvable(typeArg, availableSymbols, imports, diagnostics);
        }
    }

    private void validateIdentifier(String name, SourceLocation loc, String context, List<CompilerDiagnostic> diagnostics) {
        if (JAVA_KEYWORDS.contains(name)) {
            diagnostics.add(CompilerDiagnostic.error(loc, "Java keyword '" + name + "' cannot be used as " + context + " name."));
        }
    }

    private void validatePackageName(String pkgName, SourceLocation loc, List<CompilerDiagnostic> diagnostics) {
        for (String part : pkgName.split("\\.")) {
            if (JAVA_KEYWORDS.contains(part)) {
                diagnostics.add(CompilerDiagnostic.error(loc, "Java keyword '" + part + "' cannot be used in package name '" + pkgName + "'."));
            }
        }
    }

    private String getDefinitionName(TopLevelDefinition def) {
        if (def instanceof ValueObjectNode vo) return vo.name();
        if (def instanceof AggregateNode agg) return agg.name();
        if (def instanceof EntityNode entity) return entity.name();
        if (def instanceof EventNode event) return event.name();
        if (def instanceof ServiceNode service) return service.name();
        if (def instanceof RepositoryNode repo) return repo.name();
        throw new IllegalArgumentException("Unknown definition: " + def);
    }
}