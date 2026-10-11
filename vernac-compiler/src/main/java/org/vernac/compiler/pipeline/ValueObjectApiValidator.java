// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.JavaTypeNames;
import org.vernac.compiler.symbols.ResolvedType;
import org.vernac.compiler.symbols.TypeIdentity;
import org.vernac.language.VernacNames;
import org.vernac.compiler.util.MemberNames;
import java.util.*;
import java.util.stream.Collectors;

/** Checks method declarations against the emitted API, without analyzing Java bodies. */
final class ValueObjectApiValidator {
    private record Member(SourceLocation location, String description) { }

    List<CompilerDiagnostic> validate(ValueObjectNode value, Map<TypeNode, ResolvedType> types, String namespace) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Member> signatures = new LinkedHashMap<>();
        for (String signature : value.isEnum()
                ? List.of("hashCode()", "equals(java.lang.Object)")
                : List.of("toString()", "hashCode()", "equals(java.lang.Object)"))
            signatures.put(signature, new Member(value.location(),
                    (value.isEnum() ? "inherited final method '" : "generated method '") + signature + "'"));
        for (String signature : List.of("getClass()", "notify()", "notifyAll()", "wait()", "wait(long)", "wait(long,int)"))
            signatures.put(signature, new Member(value.location(), "inherited final method '" + signature + "'"));
        if (value.isEnum()) {
            String self = JavaTypeNames.domainPackage(new TypeIdentity(namespace, value.name()))
                    + "." + value.name();
            for (String signature : List.of("values()", "valueOf(java.lang.String)", "name()", "ordinal()",
                    "getDeclaringClass()", "clone()", "finalize()", "describeConstable()",
                    "compareTo(" + self + ")", "compareTo(java.lang.Object)", "compareTo(java.lang.Enum)"))
                signatures.put(signature, new Member(value.location(), "implicit or reserved Java enum method '" + signature + "'"));
            Set<String> constants = new HashSet<>();
            for (var constant : value.enumConstants()) {
                identifier(constant.name(), constant.location(), diagnostics);
                if (!constants.add(constant.name())) diagnostics.add(CompilerDiagnostic.error(constant.location(),
                        "Duplicate enum constant '" + constant.name() + "' in '" + value.name() + "'."));
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
            if (value.isEnum() && signature.equals("toString()") && types.containsKey(method.returnType())) {
                boolean stringReturn = types.get(method.returnType()) instanceof ResolvedType.Builtin builtin
                        && builtin.javaType() == String.class && !method.returnType().isOptional();
                if (!stringReturn || !method.accessModifier().equals("public"))
                    diagnostics.add(CompilerDiagnostic.error(method.location(),
                            "Method 'toString()' conflicts with the enum API: use public String toString() with a non-null result."));
            }
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
