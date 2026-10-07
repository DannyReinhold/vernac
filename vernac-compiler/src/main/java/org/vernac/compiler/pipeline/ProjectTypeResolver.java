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
        // Invalid imports must be diagnosed even when unused. Avoid cascading lookup errors.
        failOnErrors(diagnostics);

        Map<TypeNode, ResolvedType> fieldTypes = new LinkedHashMap<>();
        for (var source : project.sources()) {
            FileTypeScope scope = scopes.get(source.path());
            for (var value : source.unit().valueObjects()) {

                if (!value.isEnum() && value.fields().isEmpty()) {
                    diagnostics.add(CompilerDiagnostic.error(value.location(),
                            "Value Object '" + value.name() + "' must declare at least one field."));
                }
                for (var field : value.fields()) {
                    if (field.isMutable()) {
                        diagnostics.add(CompilerDiagnostic.error(field.location(),
                                "Value Object '" + value.name() + "' cannot have mutable field '" + field.name() + "'."));
                    }
                    resolveField(field, scope, fieldTypes, diagnostics);
                }
                for (var method : value.methods()) {
                    TypeNode returnType = method.returnType();
                    if (returnType.name().equals("void") && !returnType.isOptional() && returnType.typeArguments().isEmpty()) {
                        fieldTypes.put(returnType, new ResolvedType.VoidReturn());
                    } else {
                        resolveField(new FieldNode(method.location(), returnType, method.name()), scope, fieldTypes, diagnostics);
                    }
                    for (var parameter : method.parameters()) resolveField(parameter, scope, fieldTypes, diagnostics);
                }
            }
        }
        failOnErrors(diagnostics);
        for (var source : project.sources()) {
            for (var value : source.unit().valueObjects()) {
                diagnostics.addAll(new ValueObjectApiValidator().validate(value, fieldTypes));
            }
        }
        failOnErrors(diagnostics);
        List<TypeSymbol> deferred = namespaces.stream().flatMap(namespace -> project.symbols().inNamespace(namespace).stream())
                .filter(symbol -> switch (symbol.kind()) {
                    case ID, VALUE_OBJECT, ENUM -> false;
                    default -> true;
                }).toList();
        return new ResolvedProject(project, scopes, fieldTypes, diagnostics, deferred);
    }

    private void resolveField(FieldNode field, FileTypeScope scope, Map<TypeNode, ResolvedType> resolved,
                              List<CompilerDiagnostic> diagnostics) {
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
                    diagnostics.add(CompilerDiagnostic.error(reference.location(),
                            "Collection field '" + field.name() + "' is not supported by this analysis stage yet."
                                    + " Value-object collection rules will be implemented separately."));
                    return;
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
