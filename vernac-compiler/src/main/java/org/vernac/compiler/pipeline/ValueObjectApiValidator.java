// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.JavaTypeNames;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.language.VernacNames;
import org.vernac.compiler.util.MemberNames;
import java.util.*;
import java.util.stream.Collectors;

/** Checks method declarations against the emitted API, without analyzing Java bodies. */
final class ValueObjectApiValidator {
    private record Member(SourceLocation location, String description) { }

    List<CompilerDiagnostic> validate(ValueObjectNode value, Map<TypeNode, ResolvedType> types) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Member> signatures = new LinkedHashMap<>();
        for (String signature : List.of("toString()", "hashCode()", "equals(java.lang.Object)"))
            signatures.put(signature, new Member(value.location(), "generated method '" + signature + "'"));
        for (String signature : List.of("getClass()", "notify()", "notifyAll()", "wait()", "wait(long)", "wait(long,int)"))
            signatures.put(signature, new Member(value.location(), "inherited final method '" + signature + "'"));
        if (value.isEnum()) {
            // Preserve existing enum checks; full enum design is reviewed separately.
            for (String signature : List.of("of(java.lang.String)", "dbValue()", "values()", "valueOf(java.lang.String)",
                    "name()", "ordinal()", "getDeclaringClass()"))
                signatures.put(signature, new Member(value.location(), "generated or inherited enum method '" + signature + "'"));
            Set<String> constants = new HashSet<>();
            Set<String> databaseValues = new HashSet<>();
            for (var constant : value.enumConstants()) {
                identifier(constant.name(), constant.location(), diagnostics);
                if (!databaseValues.add(constant.effectiveDbValue())) diagnostics.add(CompilerDiagnostic.error(constant.location(),
                        "Duplicate database persistence value '" + constant.effectiveDbValue() + "' in enum '" + value.name() + "'."));
                if (!constants.add(constant.name()) || constant.name().equals("dbValue")) diagnostics.add(CompilerDiagnostic.error(constant.location(),
                        "Enum constant '" + constant.name() + "' conflicts with another or generated member."));
            }
        } else {
            if (resolved(value.fields(), types)) generated("of(" + parameters(value.fields(), types) + ")", value, signatures, diagnostics);
            var required = value.fields().stream().filter(f -> !f.type().isOptional()).toList();
            if (!required.isEmpty() && required.size() != value.fields().size() && resolved(required, types))
                generated("of(" + parameters(required, types) + ")", value, signatures, diagnostics);
            if (!value.validations().isEmpty()) generated("validate()", value, signatures, diagnostics);
            if (value.fields().size() == 1 && types.get(value.fields().get(0).type()) instanceof ResolvedType.Builtin builtin
                    && builtin.javaType() == UUID.class) {
                generated("create()", value, signatures, diagnostics);
                generated("of(java.lang.String)", value, signatures, diagnostics);
            }
            checkNames(value.fields(), "field", diagnostics);
            for (var field : value.fields()) {
                String signature = field.name() + "()";
                add(signature, new Member(field.location(), "Getter for " + MemberNames.describe(field, "field")), signatures, diagnostics,
                        "Specify a different field name.");
                checkObjectOverride(field.name(), List.of(), types.get(field.type()), field.type().isOptional(),
                        "public", field.location(), diagnostics);
            }
        }
        for (var method : value.methods()) {
            identifier(method.name(), method.location(), diagnostics);
            checkNames(method.parameters(), "parameter", diagnostics);
            if (!resolved(method.parameters(), types)) continue;
            String signature = method.name() + "(" + parameters(method.parameters(), types) + ")";
            add(signature, new Member(method.location(), "Declared method '" + signature + "'"), signatures, diagnostics,
                    "Use a distinct method name or parameter signature; changing only the return type does not resolve the conflict.");
            if (!value.isEnum()) checkObjectOverride(method.name(), method.parameters(), types.get(method.returnType()),
                    method.returnType().isOptional(), method.accessModifier(), method.location(), diagnostics);
        }
        return diagnostics;
    }

    /** The two non-final Object methods not replaced by the ordinary VO generator. */
    private void checkObjectOverride(String name, List<FieldNode> parameters, ResolvedType returnType,
                                     boolean optionalReturn, String visibility, SourceLocation location,
                                     List<CompilerDiagnostic> diagnostics) {
        if (returnType == null) return;
        if (!parameters.isEmpty() || (!name.equals("clone") && !name.equals("finalize"))) return;
        boolean reference = optionalReturn || returnType instanceof ResolvedType.Declared
                || returnType instanceof ResolvedType.Builtin builtin && !builtin.javaType().isPrimitive();
        boolean compatible = name.equals("clone") ? reference : returnType instanceof ResolvedType.VoidReturn;
        if (!compatible || !visibility.equals("public")) diagnostics.add(CompilerDiagnostic.error(location,
                "Method '" + name + "()' conflicts with inherited Object." + name + "(): "
                        + (!compatible ? "incompatible return type. " : "visibility cannot be reduced. ")
                        + "Use a distinct name or a Java-compatible override."));
    }

    private boolean resolved(List<FieldNode> fields, Map<TypeNode, ResolvedType> types) {
        return fields.stream().allMatch(field -> types.containsKey(field.type()));
    }

    private String parameters(List<FieldNode> parameters, Map<TypeNode, ResolvedType> types) {
        return parameters.stream().map(field -> JavaTypeNames.canonicalName(types.get(field.type())))
                .collect(Collectors.joining(","));
    }

    private void generated(String signature, ValueObjectNode value, Map<String, Member> signatures,
                           List<CompilerDiagnostic> diagnostics) {
        add(signature, new Member(value.location(), "generated method '" + signature + "'"), signatures, diagnostics,
                "Choose field names that do not conflict with the generated API.");
    }

    private void add(String signature, Member member, Map<String, Member> signatures,
                     List<CompilerDiagnostic> diagnostics, String guidance) {
        Member previous = signatures.putIfAbsent(signature, member);
        if (previous != null) diagnostics.add(CompilerDiagnostic.error(member.location(),
                member.description() + " produces signature '" + signature + "', which conflicts with "
                        + previous.description() + " at " + previous.location() + ". " + guidance));
    }

    private void checkNames(List<FieldNode> fields, String kind, List<CompilerDiagnostic> diagnostics) {
        for (FieldNode field : fields) identifier(field.name(), field.location(), diagnostics);
        diagnostics.addAll(MemberNames.duplicates(fields, kind, ""));
    }

    private void identifier(String name, SourceLocation location, List<CompilerDiagnostic> diagnostics) {
        if (!VernacNames.isIdentifier(name)) diagnostics.add(CompilerDiagnostic.error(location,
                "Invalid Java member name '" + name + "'. Choose an explicit valid name."));
    }
}
