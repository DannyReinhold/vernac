package org.vernac.compiler.analyzer;

import org.vernac.compiler.ast.*;
import org.vernac.compiler.util.TypeUtils;

import java.util.*;

public class SemanticAnalyzer {

    private static final Set<String> BUILTIN_JDK_TYPES = Set.of(
            "String", "Boolean", "Integer", "Long", "Double", "Float",
            "BigDecimal", "BigInteger", "UUID", "Currency",
            "Instant", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime", "Duration",
            "List", "Set", "Map", "Optional", "void", "Void"
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

            // A. Value-Object Collections registrieren & auf Kollision prüfen
            if (def instanceof ValueObjectNode vo && vo.collection().isPresent()) {
                String collName = vo.collection().get().customName().orElse(vo.name() + "s");
                validateIdentifier(collName, vo.collection().get().location(), "collection type", diagnostics);
                if (!declaredTypes.add(collName)) {
                    diagnostics.add(CompilerDiagnostic.error(
                            vo.collection().get().location(),
                            "Collection type name '" + collName + "' conflicts with an existing type declaration."
                    ));
                }
            }

            // B. Entity Collections registrieren & auf Kollision prüfen
            if (def instanceof EntityNode entity && entity.collection().isPresent()) {
                String collName = entity.collection().get().customName().orElse(entity.name() + "s");
                validateIdentifier(collName, entity.collection().get().location(), "collection type", diagnostics);
                if (!declaredTypes.add(collName)) {
                    diagnostics.add(CompilerDiagnostic.error(
                            entity.collection().get().location(),
                            "Collection type name '" + collName + "' conflicts with an existing type declaration."
                    ));
                }
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

        Set<String> declaredAggregates = unit.definitions().stream()
                .filter(d -> d instanceof AggregateNode)
                .map(this::getDefinitionName)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> declaredEvents = unit.definitions().stream()
                .filter(d -> d instanceof EventNode)
                .map(this::getDefinitionName)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> declaredIds = new HashSet<>();
        for (TopLevelDefinition def : unit.definitions()) {
            if (def instanceof IdDeclarationNode idDef) {
                declaredIds.add(idDef.name());
            }
        }
        for (TopLevelDefinition def : unit.definitions()) {
            if (def instanceof IdDeclarationNode idDef) {
                validateIdDeclaration(idDef, diagnostics);
            } else if (def instanceof ValueObjectNode vo) {
                validateValueObject(vo, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof AggregateNode agg) {
                validateAggregate(agg, declaredIds, declaredAggregates, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof EntityNode entity) {
                validateEntity(entity, declaredIds, declaredAggregates, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof EventNode event) {
                validateEvent(event, availableSymbols, unit.imports(), diagnostics);
            } else if (def instanceof RepositoryNode repo) {
                validateRepository(repo, unit, availableSymbols, diagnostics);
            } else if (def instanceof PortNode port) {
                validatePort(port, unit, availableSymbols, diagnostics);
            } else if (def instanceof UseCaseNode useCase) {
                validateUseCase(useCase, unit, declaredAggregates, availableSymbols, diagnostics);
            } else if (def instanceof DomainServiceNode service) {
                validateDomainService(service, unit, availableSymbols, diagnostics);
            } else if (def instanceof ListenerNode listener) {
                validateListener(listener, unit, declaredEvents, diagnostics);
            }
        }

        return diagnostics;
    }

    private void validateIdDeclaration(IdDeclarationNode idDef, List<CompilerDiagnostic> diagnostics) {
        if (idDef.customPackage().isPresent()) {
            validatePackageName(idDef.customPackage().get(), idDef.location(), diagnostics);
        }
    }

    private void validatePort(PortNode port, CompilationUnitNode unit, Set<String> availableSymbols, List<CompilerDiagnostic> diagnostics) {
        if (port.customPackage().isPresent()) {
            validatePackageName(port.customPackage().get(), port.location(), diagnostics);
        }

        // Schemas parsen und validieren (stehen dem Port lokal als Typ zur Verfügung)
        Set<String> localSymbols = new HashSet<>(availableSymbols);
        for (SchemaNode schema : port.schemas()) {
            if (!localSymbols.add(schema.name())) {
                diagnostics.add(CompilerDiagnostic.error(schema.location(), "Duplicate schema name '" + schema.name() + "' in port '" + port.name() + "'"));
            }
            validateIdentifier(schema.name(), schema.location(), "schema", diagnostics);
            checkDuplicateFields(schema.fields(), schema.name(), diagnostics);

            for (FieldNode field : schema.fields()) {
                validateIdentifier(field.name(), field.location(), "schema field", diagnostics);
                validateTypeResolvable(field.type(), localSymbols, unit.imports(), diagnostics);
            }
        }

        Set<String> methodNames = new HashSet<>();
        for (PortMethodNode method : port.methods()) {
            validateIdentifier(method.name(), method.location(), "port method", diagnostics);
            if (!methodNames.add(method.name())) {
                diagnostics.add(CompilerDiagnostic.error(method.location(), "Duplicate port method '" + method.name() + "' in '" + port.name() + "'"));
            }

            validateTypeResolvable(method.returnType(), localSymbols, unit.imports(), diagnostics);
            for (FieldNode param : method.parameters()) {
                if (param == null) {
                    continue;
                }
                if (param.name() != null) {
                    validateIdentifier(param.name(), param.location(), "parameter", diagnostics);
                }
                validateTypeResolvable(param.type(), localSymbols, unit.imports(), diagnostics);
            }

            validateAdapter(method.adapter(), method, diagnostics);

            if (method.mapping().isPresent()) {
                validateMapping(method.mapping().get(), port, unit, diagnostics);
            }
        }
    }

    private void validateAdapter(AdapterNode adapter, PortMethodNode method, List<CompilerDiagnostic> diagnostics) {
        if (adapter instanceof RestAdapterNode rest) {
            rest.customPackage().ifPresent(pkg -> validatePackageName(pkg, rest.location(), diagnostics));

            for (RestErrorRuleNode rule : rest.errorRules()) {
                String code = rule.statusCode();
                if (!code.matches("^[1-5]([0-9]{2}|[xX]{2})$")) {
                    diagnostics.add(CompilerDiagnostic.error(rule.location(), "Invalid HTTP status code or family '" + code + "'. Must be e.g., 404 or 5xx."));
                }

                if (rule.returnExpression().isPresent() && rule.returnExpression().get().contains("empty")) {
                    if (!method.returnType().name().equals("Optional")) {
                        diagnostics.add(CompilerDiagnostic.error(rule.location(), "Cannot 'return empty' on status '" + code + "' because method '" + method.name() + "' does not return an Optional."));
                    }
                }

                if (rule.throwExceptionType().isPresent()) {
                    String exceptionName = rule.throwExceptionType().get();
                    if (!method.thrownExceptions().contains(exceptionName)) {
                        diagnostics.add(CompilerDiagnostic.error(rule.location(), "Thrown exception '" + exceptionName + "' on status '" + code + "' is not declared in method signature's throws clause."));
                    }
                }
            }
        } else if (adapter instanceof CustomAdapterNode custom) {
            custom.customPackage().ifPresent(pkg -> validatePackageName(pkg, custom.location(), diagnostics));
            custom.delegateName().ifPresent(name -> validateIdentifier(name, custom.location(), "delegate name", diagnostics));
        }
    }

    private void validateMapping(MappingBlockNode mapping, PortNode port, CompilationUnitNode unit, List<CompilerDiagnostic> diagnostics) {
        for (MappingStatementNode stmt : mapping.statements()) {
            // Bei `->` ist das Ziel der Domain-Typ. Bei `<-` ist das Ziel das Schema/DTO.
            String pathToCheck = stmt.direction().equals("->") ? stmt.targetPath() : stmt.sourcePath();
            String[] parts = pathToCheck.split("\\.");

            if (parts.length >= 2) {
                String typeName = parts[0];
                String fieldName = parts[1];

                // 1. Zuerst prüfen, ob der Typ ein Schema ist
                Optional<SchemaNode> schemaDef = port.schemas().stream()
                        .filter(s -> s.name().equals(typeName))
                        .findFirst();

                if (schemaDef.isPresent()) {
                    boolean fieldExists = schemaDef.get().fields().stream().anyMatch(f -> f.name().equals(fieldName));
                    if (!fieldExists) {
                        diagnostics.add(CompilerDiagnostic.error(stmt.location(), "Field '" + fieldName + "' does not exist in schema '" + typeName + "'"));
                    }
                    continue;
                }

                // 2. Falls kein Schema, prüfen, ob es ein Domänen-Typ (Aggregate, Entity, VO, Event) ist
                Optional<TopLevelDefinition> domainDef = unit.definitions().stream()
                        .filter(d -> getDefinitionName(d).equals(typeName))
                        .findFirst();

                if (domainDef.isPresent()) {
                    TopLevelDefinition def = domainDef.get();
                    boolean fieldExists = false;

                    if (def instanceof AggregateNode agg) {
                        fieldExists = agg.idDefinition().fieldName().equals(fieldName) || agg.fields().stream().anyMatch(f -> f.name().equals(fieldName));
                    } else if (def instanceof EntityNode ent) {
                        fieldExists = ent.idDefinition().fieldName().equals(fieldName) || ent.fields().stream().anyMatch(f -> f.name().equals(fieldName));
                    } else if (def instanceof ValueObjectNode vo) {
                        fieldExists = vo.fields().stream().anyMatch(f -> f.name().equals(fieldName));
                    } else if (def instanceof EventNode ev) {
                        fieldExists = ev.fields().stream().anyMatch(f -> f.name().equals(fieldName));
                    }

                    if (!fieldExists) {
                        diagnostics.add(CompilerDiagnostic.error(stmt.location(), "Field '" + fieldName + "' does not exist in domain type '" + typeName + "'"));
                    }
                }
                // Wenn `typeName` weder im Schema noch in der Domäne gefunden wird,
                // lassen wir es vorerst durch (es könnte sich um einen Variablennamen handeln).
                // Die tiefere Typprüfung für Variablen ist extrem komplex, aber das deckt 90% der Fälle ab!
            }
        }
    }

    private void validateValueObject(ValueObjectNode vo, Set<String> availableSymbols, List<String> imports, List<CompilerDiagnostic> diagnostics) {
        if (vo.customPackage().isPresent()) validatePackageName(vo.customPackage().get(), vo.location(), diagnostics);

        // A. Enum-Spezifische Validierung
        if (vo.isEnum()) {
            if (vo.enumConstants().isEmpty()) {
                diagnostics.add(CompilerDiagnostic.error(vo.location(), "Enum Value Object '" + vo.name() + "' must declare at least one constant."));
            }

            if (vo.collection().isPresent()) {
                diagnostics.add(CompilerDiagnostic.error(
                        vo.collection().get().location(),
                        "Enum Value Object '" + vo.name() + "' cannot define a first-class collection."
                ));
            }

            Set<String> constantNames = new HashSet<>();
            Set<String> dbValues = new HashSet<>();

            for (EnumConstantNode ec : vo.enumConstants()) {
                validateIdentifier(ec.name(), ec.location(), "enum constant", diagnostics);

                if (!constantNames.add(ec.name())) {
                    diagnostics.add(CompilerDiagnostic.error(ec.location(), "Duplicate enum constant '" + ec.name() + "' in '" + vo.name() + "'"));
                }

                String dbVal = ec.effectiveDbValue();
                if (!dbValues.add(dbVal)) {
                    diagnostics.add(CompilerDiagnostic.error(
                            ec.location(),
                            "Duplicate database persistence value '" + dbVal + "' in enum '" + vo.name() + "'"
                    ));
                }
            }

            // B. Reguläre Value Objects (Felder-Prüfung)
        } else {
            checkDuplicateFields(vo.fields(), vo.name(), diagnostics);

            if (vo.collection().isPresent()) {
                vo.collection().get().customPackage().ifPresent(pkg ->
                        validatePackageName(pkg, vo.collection().get().location(), diagnostics)
                );
            }

            for (FieldNode field : vo.fields()) {
                validateIdentifier(field.name(), field.location(), "field", diagnostics);
                if (field.isMutable()) {
                    diagnostics.add(CompilerDiagnostic.error(field.location(), "Value Object '" + vo.name() + "' cannot have mutable field '" + field.name() + "'. Value Objects must be strictly immutable."));
                }
                validateTypeResolvable(field.type(), availableSymbols, imports, diagnostics);
            }
        }

        // C. Methoden (gelten für beide Varianten)
        for (MethodNode method : vo.methods()) {
            validateIdentifier(method.name(), method.location(), "method", diagnostics);
            for (FieldNode p : method.parameters())
                validateIdentifier(p.name(), p.location(), "parameter", diagnostics);
        }
    }

    private void validateAggregate(
            AggregateNode agg,
            Set<String> declaredIds,
            Set<String> declaredAggregates,
            Set<String> availableSymbols,
            List<String> imports,
            List<CompilerDiagnostic> diagnostics
    ) {
        if (agg.customPackage().isPresent())
            validatePackageName(agg.customPackage().get(), agg.location(), diagnostics);

        String idTypeName = agg.idDefinition().type().name();
        if (!declaredIds.contains(idTypeName)) {
            diagnostics.add(CompilerDiagnostic.error(
                    agg.idDefinition().location(),
                    "Aggregate '" + agg.name() + "' references ID type '" + idTypeName + "' which is not declared with 'id " + idTypeName + ";'."
            ));
        }

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
            checkNoDirectAggregateReference(field, agg.name(), declaredAggregates, diagnostics);
            validateFieldCollectionConstraints(field, agg.name(), diagnostics);
        }
        for (MethodNode method : agg.methods()) {
            validateIdentifier(method.name(), method.location(), "method", diagnostics);
            for (FieldNode p : method.parameters())
                validateIdentifier(p.name(), p.location(), "parameter", diagnostics);
        }
    }

    private void validateEntity(
            EntityNode entity,
            Set<String> declaredIds,
            Set<String> declaredAggregates,
            Set<String> availableSymbols,
            List<String> imports,
            List<CompilerDiagnostic> diagnostics
    ) {
        if (entity.customPackage().isPresent()) {
            validatePackageName(entity.customPackage().get(), entity.location(), diagnostics);
        }

        if (entity.collection().isPresent()) {
            entity.collection().get().customPackage().ifPresent(pkg ->
                    validatePackageName(pkg, entity.collection().get().location(), diagnostics)
            );
        }

        String idTypeName = entity.idDefinition().type().name();
        if (!declaredIds.contains(idTypeName)) {
            diagnostics.add(CompilerDiagnostic.error(
                    entity.idDefinition().location(),
                    "Entity '" + entity.name() + "' references ID type '" + idTypeName + "' which is not declared with 'id " + idTypeName + ";'."
            ));
        }

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
            checkNoDirectAggregateReference(field, entity.name(), declaredAggregates, diagnostics);
            validateFieldCollectionConstraints(field, entity.name(), diagnostics);
        }
        for (MethodNode method : entity.methods()) {
            validateIdentifier(method.name(), method.location(), "method", diagnostics);
            for (FieldNode p : method.parameters())
                validateIdentifier(p.name(), p.location(), "parameter", diagnostics);
        }
    }

    private void checkNoDirectAggregateReference(
            FieldNode field,
            String parentTypeName,
            Set<String> declaredAggregates,
            List<CompilerDiagnostic> diagnostics
    ) {
        String typeToCheck = field.type().name();
        if (declaredAggregates.contains(typeToCheck)) {
            diagnostics.add(CompilerDiagnostic.error(
                    field.location(),
                    "Direct reference to aggregate root '" + typeToCheck + "' inside '" + parentTypeName +
                            "' is forbidden. Reference external aggregates by their ID type instead."
            ));
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
            if (method.name().equals("findById") || method.name().equals("getById")) {
                diagnostics.add(CompilerDiagnostic.warning(method.location(),
                        "Method '" + method.name() + "' is redundant. The repository automatically provides 'byId(id)'."));
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

    private void validateFieldCollectionConstraints(FieldNode field, String parentName, List<CompilerDiagnostic> diagnostics) {
        String typeName = field.type().name();
        if (typeName.equals("List") || typeName.equals("Set") || typeName.equals("Map") || typeName.equals("Collection")) {
            diagnostics.add(CompilerDiagnostic.error(
                    field.location(),
                    "Raw collection '" + typeName + "' is not permitted in '" + parentName +
                            "'. Define a first-class value object collection or an explicit child entity relation instead."
            ));
        }
    }

    private String getDefinitionName(TopLevelDefinition def) {
        if (def instanceof IdDeclarationNode idDef) return idDef.name();
        if (def instanceof ValueObjectNode vo) return vo.name();
        if (def instanceof AggregateNode agg) return agg.name();
        if (def instanceof EntityNode entity) return entity.name();
        if (def instanceof EventNode event) return event.name();
        if (def instanceof PortNode port) return port.name();
        if (def instanceof RepositoryNode repo) return repo.name();
        if (def instanceof UseCaseNode useCase) return useCase.name();
        if (def instanceof DomainServiceNode service) return service.name();
        if (def instanceof ListenerNode listener) return listener.listenerName();
        throw new IllegalArgumentException("Unknown definition: " + def);
    }

    private void validateUseCase(
            UseCaseNode useCase,
            CompilationUnitNode unit,
            Set<String> declaredAggregates,
            Set<String> availableSymbols,
            List<CompilerDiagnostic> diagnostics
    ) {
        if (useCase.customPackage().isPresent()) {
            validatePackageName(useCase.customPackage().get(), useCase.location(), diagnostics);
        }

        // 1. Parameter prüfen
        checkDuplicateFields(useCase.parameters(), useCase.name(), diagnostics);
        for (FieldNode param : useCase.parameters()) {
            validateIdentifier(param.name(), param.location(), "parameter", diagnostics);
            validateTypeResolvable(param.type(), availableSymbols, unit.imports(), diagnostics);

            // DDD-Regel: Keine Aggregates als Eingabeparameter
            if (declaredAggregates.contains(param.type().name())) {
                diagnostics.add(CompilerDiagnostic.error(
                        param.location(),
                        "Direct passing of aggregate root '" + param.type().name() +
                                "' into usecase '" + useCase.name() + "' is forbidden. Pass IDs or Value Objects instead."
                ));
            }
        }

        // 2. Dependencies (use ...) prüfen
        Set<String> depTypes = new HashSet<>();
        Set<String> depInstances = new HashSet<>();
        // Speichert pro Aggregat-Name die Liste aller dafür passenden Repo-Instanznamen in den use-Klauseln
        Map<String, List<String>> aggregateToRepoInstances = new HashMap<>();

        for (UseDependencyNode dep : useCase.dependencies()) {
            validateIdentifier(dep.typeName(), dep.location(), "dependency type", diagnostics);

            // Regel 4: Generell keine zwei identischen Dependency-Typen
            if (!depTypes.add(dep.typeName())) {
                diagnostics.add(CompilerDiagnostic.error(
                        dep.location(),
                        "Duplicate dependency type '" + dep.typeName() + "' in usecase '" + useCase.name() + "'."
                ));
            }

            // Instanzname ermitteln (explizit oder per Konvention: camelCase des Typnamens)
            String instanceName = dep.instanceName().orElseGet(() -> {
                String type = dep.typeName();
                return Character.toLowerCase(type.charAt(0)) + type.substring(1);
            });

            if (!depInstances.add(instanceName)) {
                diagnostics.add(CompilerDiagnostic.error(
                        dep.location(),
                        "Duplicate dependency instance variable '" + instanceName + "' in usecase '" + useCase.name() + "'."
                ));
            }

            // Prüfen, ob Dependency ein bekanntes Repository für ein Aggregat ist
            unit.definitions().stream()
                    .filter(d -> d instanceof RepositoryNode)
                    .map(d -> (RepositoryNode) d)
                    .filter(r -> r.name().equals(dep.typeName()))
                    .findFirst()
                    .ifPresent(r -> aggregateToRepoInstances
                            .computeIfAbsent(r.aggregateName(), k -> new ArrayList<>())
                            .add(instanceName));
        }

        // 3. Statements prüfen
        for (UseCaseStatementNode stmt : useCase.statements()) {
            if (stmt instanceof LoadStatementNode load) {
                // Prüfen, ob aggregateType überhaupt ein Aggregat ist
                if (!declaredAggregates.contains(load.aggregateType())) {
                    diagnostics.add(CompilerDiagnostic.error(
                            load.location(),
                            "Cannot load non-aggregate type '" + load.aggregateType() + "' in usecase '" + useCase.name() + "'."
                    ));
                    continue;
                }

                if (load.repositoryName().isPresent()) {
                    // Regel 5.2: repositoryName muss der Variablenname (Instanzname) sein!
                    String repoVar = load.repositoryName().get();
                    if (!depInstances.contains(repoVar)) {
                        diagnostics.add(CompilerDiagnostic.error(
                                load.location(),
                                "Repository variable '" + repoVar + "' used in 'from' clause is not declared with 'use'."
                        ));
                    }
                } else {
                    // Regel 5.3: Es muss exakt EIN passendes Repository in den use-Klauseln dieses UseCases geben
                    List<String> matchingRepos = aggregateToRepoInstances.getOrDefault(load.aggregateType(), Collections.emptyList());
                    if (matchingRepos.isEmpty()) {
                        diagnostics.add(CompilerDiagnostic.error(
                                load.location(),
                                "No repository in 'use' manages aggregate '" + load.aggregateType() + "'."
                        ));
                    } else if (matchingRepos.size() > 1) {
                        diagnostics.add(CompilerDiagnostic.error(
                                load.location(),
                                "Ambiguous repositories for aggregate '" + load.aggregateType() +
                                        "'. Explicitly specify 'from <repositoryVariable>'."
                        ));
                    }
                }

            } else if (stmt instanceof SaveStatementNode save) {
                if (save.repositoryName().isPresent()) {
                    String repoVar = save.repositoryName().get();
                    if (!depInstances.contains(repoVar)) {
                        diagnostics.add(CompilerDiagnostic.error(
                                save.location(),
                                "Repository variable '" + repoVar + "' used in 'to' clause is not declared with 'use'."
                        ));
                    }
                }
            }
        }

        // 4. Return-Statement & Tupel prüfen (Regel 6: Keine Aggregates im Result)
        if (useCase.returnStatement().isPresent()) {
            ReturnStatementNode ret = useCase.returnStatement().get();

            if (ret instanceof SingleReturnNode single) {
                single.expressionCode().ifPresent(expr -> {
                    // Falls direkt ein Aggregat-Name als Singleton zurückgegeben wird
                    if (declaredAggregates.contains(expr)) {
                        diagnostics.add(CompilerDiagnostic.error(
                                single.location(),
                                "Returning aggregate root '" + expr + "' from usecase is forbidden. Return DTOs, IDs or Value Objects."
                        ));
                    }
                });
            } else if (ret instanceof TupleReturnNode tuple) {
                Set<String> tupleFieldNames = new HashSet<>();
                for (TupleElementNode elem : tuple.elements()) {
                    String code = elem.expressionCode().trim();

                    // Regel 6: Keine Aggregates im Tupel
                    if (declaredAggregates.contains(code)) {
                        diagnostics.add(CompilerDiagnostic.error(
                                elem.location(),
                                "Direct return of aggregate root '" + code + "' in tuple is forbidden. Projiziere Properties oder IDs."
                        ));
                    }

                    // Eindeutige Komponentennamen sicherstellen
                    String fieldName = elem.alias().orElseGet(() -> {
                        int lastDot = code.lastIndexOf('.');
                        if (lastDot >= 0) {
                            String afterDot = code.substring(lastDot + 1).replaceAll("[^a-zA-Z0-9_]", "");
                            return afterDot.isEmpty() ? "value" : afterDot;
                        }
                        return "value";
                    });

                    if (!tupleFieldNames.add(fieldName)) {
                        diagnostics.add(CompilerDiagnostic.error(
                                elem.location(),
                                "Duplicate component name '" + fieldName + "' in tuple return of usecase '" + useCase.name() +
                                        "'. Use explicit aliases with 'as <name>'."
                        ));
                    }
                }
            }
        }
    }

    private void validateDomainService(
            DomainServiceNode service,
            CompilationUnitNode unit,
            Set<String> availableSymbols,
            List<CompilerDiagnostic> diagnostics
    ) {
        if (service.customPackage().isPresent()) {
            validatePackageName(service.customPackage().get(), service.location(), diagnostics);
        }

        // 1. Parameter prüfen
        checkDuplicateFields(service.parameters(), service.name(), diagnostics);
        for (FieldNode param : service.parameters()) {
            validateIdentifier(param.name(), param.location(), "parameter", diagnostics);
            if (param.isMutable()) {
                diagnostics.add(CompilerDiagnostic.error(
                        param.location(),
                        "Domain service parameter '" + param.name() + "' cannot be mutable. Services operate on immutable inputs."
                ));
            }
            validateTypeResolvable(param.type(), availableSymbols, unit.imports(), diagnostics);
        }

        // 2. Return-Type prüfen
        service.returnType().ifPresent(retType ->
                validateTypeResolvable(retType, availableSymbols, unit.imports(), diagnostics)
        );
    }

    private void validateListener(
            ListenerNode listener,
            CompilationUnitNode unit,
            Set<String> declaredEvents,
            List<CompilerDiagnostic> diagnostics
    ) {
        if (listener.customPackage().isPresent()) {
            validatePackageName(listener.customPackage().get(), listener.location(), diagnostics);
        }

        // 1. Prüfen, ob das Event deklariert ist
        if (!declaredEvents.contains(listener.eventName())) {
            diagnostics.add(CompilerDiagnostic.error(
                    listener.location(),
                    "Listener target '" + listener.eventName() + "' must be an existing event declaration."
            ));
        }

        // 2. Dependencies (use ...) prüfen
        Set<String> depTypes = new HashSet<>();
        Set<String> depInstances = new HashSet<>();

        for (UseDependencyNode dep : listener.dependencies()) {
            validateIdentifier(dep.typeName(), dep.location(), "dependency type", diagnostics);

            if (!depTypes.add(dep.typeName())) {
                diagnostics.add(CompilerDiagnostic.error(
                        dep.location(),
                        "Duplicate dependency type '" + dep.typeName() + "' in listener '" + listener.listenerName() + "'."
                ));
            }

            String instanceName = dep.instanceName().orElseGet(() -> {
                String type = dep.typeName();
                return Character.toLowerCase(type.charAt(0)) + type.substring(1);
            });

            if (!depInstances.add(instanceName)) {
                diagnostics.add(CompilerDiagnostic.error(
                        dep.location(),
                        "Duplicate dependency instance variable '" + instanceName + "' in listener '" + listener.listenerName() + "'."
                ));
            }
        }
    }
}