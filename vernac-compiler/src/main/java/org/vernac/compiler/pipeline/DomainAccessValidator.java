// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.util.MemberNames;
import org.vernac.language.VernacNames;
import java.util.*;

/** Validate the combined read/write surface before emitting Java interfaces. */
final class DomainAccessValidator {
    List<CompilerDiagnostic> validate(MutableDomain model, Map<TypeNode, ResolvedType> types, String namespace, VernacProject project) {
        var errors = new ArrayList<CompilerDiagnostic>();
        var signatures = new HashSet<String>(List.of("id()", "validate()", "toString()", "hashCode()", "equals(java.lang.Object)",
                "getClass()", "notify()", "notifyAll()", "wait()", "wait(long)", "wait(long,int)", "clone()", "finalize()"));
        if (model.aggregate()) signatures.addAll(List.of("createdAt()", "updatedAt()", "persistenceState()", "pullDomainEvents()", "markAsUpdated()"));
        if (model.fields().stream().allMatch(f -> types.containsKey(f.type()))) {
            signatures.add("create(" + parameters(model.fields(), types) + ")");
            if (types.containsKey(model.id().type())) {
                String reconstruction = JavaTypeNames.canonicalName(types.get(model.id().type()));
                if (!model.fields().isEmpty()) reconstruction += "," + parameters(model.fields(), types);
                if (model.aggregate()) reconstruction += ",java.time.Instant,java.time.Instant,long";
                signatures.add("reconstitute(" + reconstruction + ")");
            }
            signatures.add("create(" + parameters(model.fields().stream().filter(f -> !f.type().isOptional()).toList(), types) + ")");
        }
        errors.addAll(MemberNames.duplicates(model.fields(), "field", ""));
        for (var f : model.fields()) {
            if (!VernacNames.isIdentifier(f.name()) || f.name().startsWith("__") || Set.of("id", "createdAt", "updatedAt", "version", "persistenceState", "domainEvents", "TABLE_NAME", "SCHEMA_DDL").contains(f.name()))
                errors.add(CompilerDiagnostic.error(f.location(), "Field name '" + f.name() + "' conflicts with the generated entity API or is not a Java identifier."));
            add(f.name() + "()", f.location(), signatures, errors);
            if (f.isMutable() && types.containsKey(f.type())) add(f.name() + "(" + JavaTypeNames.canonicalName(types.get(f.type())) + ")", f.location(), signatures, errors);
        }
        for (var m : model.methods()) {
            if (!VernacNames.isIdentifier(m.name()) || m.name().startsWith("__")) errors.add(CompilerDiagnostic.error(m.location(), "Invalid or reserved behavior method name '" + m.name() + "'."));
            errors.addAll(MemberNames.duplicates(m.parameters(), "parameter", ""));
            for (var p : m.parameters()) if (!VernacNames.isIdentifier(p.name())) errors.add(CompilerDiagnostic.error(p.location(), "Invalid Java parameter name '" + p.name() + "'."));
            if (m.parameters().stream().allMatch(p -> types.containsKey(p.type())) && m.accessModifier().equals("public"))
                add(m.name() + "(" + parameters(m.parameters(), types) + ")", m.location(), signatures, errors);
        }
        return errors;
    }
    private String parameters(List<FieldNode> fields, Map<TypeNode, ResolvedType> types) {
        return String.join(",", fields.stream().map(f -> JavaTypeNames.canonicalName(types.get(f.type()))).toList());
    }
    private void add(String signature, SourceLocation location, Set<String> signatures, List<CompilerDiagnostic> errors) {
        if (!signatures.add(signature)) errors.add(CompilerDiagnostic.error(location, "Signature '" + signature + "' conflicts with a generated or declared member. Choose another explicit name or parameter signature."));
    }
}
