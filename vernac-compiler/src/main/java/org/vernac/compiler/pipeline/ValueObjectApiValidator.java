// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.language.VernacNames;
import java.util.*;
import java.util.stream.Collectors;

/** Detects collisions with the concrete generated API before javac is invoked. */
final class ValueObjectApiValidator {
    List<CompilerDiagnostic> validate(ValueObjectNode value, Map<TypeNode, ResolvedType> types) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        Map<String, SourceLocation> signatures = new LinkedHashMap<>();
        for (String signature : List.of("toString()", "hashCode()", "equals(java.lang.Object)",
                "getClass()", "notify()", "notifyAll()", "wait()", "wait(long)", "wait(long,int)")) {
            signatures.put(signature, value.location());
        }
        if (value.isEnum()) {
            for (String signature : List.of("of(java.lang.String)", "dbValue()", "values()", "valueOf(java.lang.String)",
                    "name()", "ordinal()", "getDeclaringClass()")) signatures.put(signature, value.location());
            Set<String> constants = new HashSet<>();
            Set<String> databaseValues = new HashSet<>();
            for (var constant : value.enumConstants()) {
                identifier(constant.name(), constant.location(), diagnostics);
                if (!databaseValues.add(constant.effectiveDbValue())) {
                    diagnostics.add(CompilerDiagnostic.error(constant.location(),
                            "Duplicate database persistence value '" + constant.effectiveDbValue() + "' in enum '" + value.name() + "'."));
                }
                if (!constants.add(constant.name()) || constant.name().equals("dbValue")) {
                    diagnostics.add(CompilerDiagnostic.error(constant.location(),
                            "Enum constant '" + constant.name() + "' conflicts with another or generated member."));
                }
            }
        } else {
            add("of(" + parameters(value.fields(), types) + ")", value.location(), signatures, diagnostics);
            var required = value.fields().stream().filter(f -> !f.type().isOptional()).toList();
            if (!required.isEmpty() && required.size() != value.fields().size()) {
                add("of(" + parameters(required, types) + ")", value.location(), signatures, diagnostics);
            }
            if (!value.validations().isEmpty()) add("validate()", value.location(), signatures, diagnostics);
            if (value.fields().size() == 1 && types.get(value.fields().getFirst().type()) instanceof ResolvedType.Builtin builtin) {
                if (builtin.javaType() == UUID.class) {
                    for (String signature : List.of("create()", "of(java.lang.String)")) {
                        add(signature, value.location(), signatures, diagnostics);
                    }
                }
            }
            Set<String> fields = new HashSet<>();
            for (var field : value.fields()) {
                identifier(field.name(), field.location(), diagnostics);
                if (!fields.add(field.name())) {
                    diagnostics.add(CompilerDiagnostic.error(field.location(), "Duplicate field name '" + field.name() + "'."));
                }
                add(field.name() + "()", field.location(), signatures, diagnostics);
            }
        }
        for (var method : value.methods()) {
            identifier(method.name(), method.location(), diagnostics);
            Set<String> parameters = new HashSet<>();
            for (var parameter : method.parameters()) {
                identifier(parameter.name(), parameter.location(), diagnostics);
                if (!parameters.add(parameter.name())) {
                    diagnostics.add(CompilerDiagnostic.error(parameter.location(), "Duplicate parameter name '" + parameter.name() + "'."));
                }
            }
            add(method.name() + "(" + parameters(method.parameters(), types) + ")", method.location(), signatures, diagnostics);
        }
        return diagnostics;
    }

    private String parameters(List<FieldNode> parameters, Map<TypeNode, ResolvedType> types) {
        return parameters.stream().map(field -> switch (types.get(field.type())) {
            case ResolvedType.Builtin builtin -> builtin.javaType().getName();
            case ResolvedType.Declared declared -> declared.symbol().identity().qualifiedName();
            case ResolvedType.VoidReturn ignored -> throw new IllegalStateException("void parameter");
        }).collect(Collectors.joining(","));
    }

    private void add(String signature, SourceLocation location, Map<String, SourceLocation> signatures,
                     List<CompilerDiagnostic> diagnostics) {
        SourceLocation previous = signatures.putIfAbsent(signature, location);
        if (previous != null) diagnostics.add(CompilerDiagnostic.error(location,
                "Method signature '" + signature + "' conflicts with a generated, inherited, or declared method at " + previous + "."));
    }

    private void identifier(String name, SourceLocation location, List<CompilerDiagnostic> diagnostics) {
        if (!VernacNames.isIdentifier(name)) {
            diagnostics.add(CompilerDiagnostic.error(location, "Invalid Java member name '" + name + "'. Choose an explicit valid name."));
        }
    }
}
