// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.util.TypeUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Resolves imports and VO field references without recursively following declarations. */
public final class ProjectTypeResolver {
    public ResolvedProject resolve(VernacProject project) {
        Set<String> namespaces = project.sources().stream().map(source -> source.unit().namespace())
                .collect(Collectors.toCollection(TreeSet::new));
        Map<Path, FileTypeScope> scopes = new LinkedHashMap<>();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (var source : project.sources()) {
            var scope = new FileTypeScope(source.unit(), project.symbols(), namespaces);
            scopes.put(source.path(), scope);
            diagnostics.addAll(scope.diagnostics());
        }
        // Import errors only prevent lookups in the affected file.

        Map<TypeNode, ResolvedType> fieldTypes = new LinkedHashMap<>();
        for (var source : project.sources()) {
            FileTypeScope scope = scopes.get(source.path());
            boolean validScope = scope.diagnostics().stream()
                    .noneMatch(d -> d.severity() == CompilerDiagnostic.Severity.ERROR);
            for (var value : source.unit().valueObjects()) {
                if (validScope) diagnostics.addAll(BehaviorImports.validate(project, scope, source.unit().namespace(),
                        value.name(), value.javaImports(), value.methods()));

                if (!value.isEnum() && value.fields().isEmpty()) {
                    diagnostics.add(CompilerDiagnostic.error(value.location(),
                            "Value Object '" + value.name() + "' must declare at least one field."));
                }
                for (var field : value.fields()) {
                    if (field.isMutable()) {
                        diagnostics.add(CompilerDiagnostic.error(field.location(),
                                "Value Object '" + value.name() + "' cannot have mutable field '" + field.name() + "'."));
                    }
                    if (validScope) resolveField(field, scope, fieldTypes, diagnostics, project, false);
                }
                for (var method : value.methods()) {
                    TypeNode returnType = method.returnType();
                    if (returnType.name().equals("void") && !returnType.isOptional() && returnType.typeArguments().isEmpty()) {
                        fieldTypes.put(returnType, new ResolvedType.VoidReturn());
                    } else if (validScope) {
                        resolveField(new FieldNode(method.location(), returnType, method.name()), scope, fieldTypes, diagnostics, project, false);
                    }
                    if (validScope) for (var parameter : method.parameters()) resolveField(parameter, scope, fieldTypes, diagnostics, project, false);
                }
            }
            for (var definition : source.unit().definitions()) {
                var requested = CollectionDeclaration.of(definition);
                if (requested.isEmpty()) continue;
                var collection = requested.get();
                if (validScope) diagnostics.addAll(BehaviorImports.validate(project, scope, source.unit().namespace(),
                        collection.name(), collection.definition().javaImports(), collection.definition().customMethods()));
                if (definition instanceof EntityNode entity && entity.customPackage().isPresent()
                        || definition instanceof AggregateNode aggregate && aggregate.customPackage().isPresent())
                    diagnostics.add(CompilerDiagnostic.error(definition.location(),
                            "Collection elements with custom packages are not supported. Use the namespace domain package."));
                if (validScope) collection.idType().ifPresent(id -> {
                    var lookup = scope.resolve(id.name(), id.location());
                    diagnostics.addAll(lookup.diagnostics());
                    if (lookup.type().orElse(null) instanceof ResolvedType.Declared declared
                            && declared.symbol().kind() == TypeSymbol.Kind.ID && !id.isOptional() && id.typeArguments().isEmpty())
                        fieldTypes.put(id, declared);
                    else if (lookup.type().isPresent()) diagnostics.add(CompilerDiagnostic.error(id.location(),
                            "Collection ID helpers require a non-optional Vernac id type."));
                });
                for (var method : collection.definition().customMethods()) {
                    if (method.returnType().name().equals("void") && !method.returnType().isOptional()
                            && method.returnType().typeArguments().isEmpty())
                        fieldTypes.put(method.returnType(), new ResolvedType.VoidReturn());
                    else if (validScope) resolveField(new FieldNode(method.location(), method.returnType(), method.name()),
                            scope, fieldTypes, diagnostics, project, true);
                    if (validScope) for (var parameter : method.parameters())
                        resolveField(parameter, scope, fieldTypes, diagnostics, project, true);
                }
                diagnostics.addAll(new CollectionApiValidator().validate(collection, fieldTypes, source.unit().namespace()));
                diagnostics.addAll(BehaviorImports.validateLowered(source.unit().namespace(), collection.name(),
                        collection.definition().customMethods(), fieldTypes));
            }
        }
        for (var source : project.sources()) {
            for (var value : source.unit().valueObjects()) {

                diagnostics.addAll(new ValueObjectApiValidator().validate(value, fieldTypes, source.unit().namespace()));
                diagnostics.addAll(BehaviorImports.validateLowered(source.unit().namespace(), value.name(), value.methods(), fieldTypes));
            }
        }
        failOnErrors(diagnostics);
        List<TypeSymbol> deferred = namespaces.stream().flatMap(namespace -> project.symbols().inNamespace(namespace).stream())
                .filter(symbol -> switch (symbol.kind()) {
                    case ID, VALUE_OBJECT, ENUM, COLLECTION -> false;
                    default -> true;
                }).toList();
        return new ResolvedProject(project, scopes, fieldTypes, diagnostics, deferred);
    }

    private void resolveField(FieldNode field, FileTypeScope scope, Map<TypeNode, ResolvedType> resolved,
                              List<CompilerDiagnostic> diagnostics, VernacProject project, boolean collectionMethod) {
        TypeNode reference = field.type();
        if (!reference.typeArguments().isEmpty()) {
            diagnostics.add(CompilerDiagnostic.error(reference.location(),
                    "Generic field type '" + reference.name() + "' is not supported in a Value Object."
                            + " Use an approved scalar or a Vernac value type."));
            return;
        }
        var lookup = scope.resolve(reference.name(), reference.location());
        diagnostics.addAll(lookup.diagnostics());
        if (lookup.type().isEmpty()) return;
        ResolvedType type = lookup.type().get();
        if (type instanceof ResolvedType.Builtin builtin && reference.isOptional() && builtin.javaType().isPrimitive()) {
            String wrapper = TypeUtils.getWrapperType(builtin.javaType().getName());
            diagnostics.add(CompilerDiagnostic.error(reference.location(),
                    "Primitive type '" + reference.name() + "' cannot be optional. Use '" + wrapper + "?' instead."));
            return;
        }
        if (type instanceof ResolvedType.Declared declared) {
            switch (declared.symbol().kind()) {
                case ID, VALUE_OBJECT, ENUM -> { }
                case COLLECTION -> {
                    var declaration = CollectionTypes.find(project, declared.symbol().identity());
                    if (!collectionMethod && (declaration.isEmpty() || !declaration.get().valueElements())) {
                        diagnostics.add(CompilerDiagnostic.error(reference.location(),
                                "Value Objects cannot contain collections of Entities or Aggregates."));
                        return;
                    }
                }
                case ENTITY, AGGREGATE -> {
                    if (!collectionMethod) {
                        diagnostics.add(CompilerDiagnostic.error(reference.location(),
                                "Value Object field '" + field.name() + "' cannot use "
                                        + declared.symbol().kind().name().toLowerCase(Locale.ROOT) + " type '"
                                        + declared.symbol().identity().qualifiedName() + "'. Use a value object, enum, or identifier."));
                        return;
                    }
                }
                default -> {
                    diagnostics.add(CompilerDiagnostic.error(reference.location(),
                            "Value Object field '" + field.name() + "' cannot use "
                                    + declared.symbol().kind().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                    + " type '" + declared.symbol().identity().qualifiedName()
                                    + "'. Use a value object, enum, or identifier."));
                    return;
                }
            }
        }
        resolved.put(reference, type);
    }

    private void failOnErrors(List<CompilerDiagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(d -> d.severity() == CompilerDiagnostic.Severity.ERROR)) {
            throw new SemanticValidationException(diagnostics);
        }
    }
}
