// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.util.MemberNames;
import org.vernac.language.VernacNames;
import java.util.*;
import java.util.stream.Collectors;

/** Signature checks for the generated collection API; Java bodies remain javac's responsibility. */
final class CollectionApiValidator {
    List<CompilerDiagnostic> validate(CollectionDeclaration collection, Map<TypeNode, ResolvedType> types, String namespace) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        String item = JavaTypeNames.domainPackage(new TypeIdentity(namespace, collection.elementName())) + "." + collection.elementName();
        Set<String> signatures = new HashSet<>(List.of("empty()", "of(java.lang.Iterable)", "of(" + item + "[])",
                "size()", "isEmpty()", "stream()", "iterator()", "spliterator()", "forEach(java.util.function.Consumer)",
                "filter(java.util.function.Predicate)", "equals(java.lang.Object)", "hashCode()", "toString()",
                "getClass()", "notify()", "notifyAll()", "wait()", "wait(long)", "wait(long,int)"));
        for (String name : List.of("contains", "count", "find", "plus", "minus", "minusAll", "matching"))
            signatures.add(name + "(" + item + ")");
        for (String name : List.of("plusAll", "minusAll", "matching")) signatures.add(name + "(java.lang.Iterable)");
        if (collection.definition().kind() == CollectionDefinitionNode.Kind.LIST)
            signatures.addAll(List.of("asList()", "get(int)", "first()", "last()", "distinct()", "duplicates()"));
        else signatures.add("asSet()");
        collection.idType().filter(types::containsKey).ifPresent(id -> {
            String type = JavaTypeNames.canonicalName(types.get(id));
            for (String name : List.of("by", "contains", "minusId", "minusAllId")) signatures.add(name + "(" + type + ")");
        });
        for (var method : collection.definition().customMethods()) {
            if (!VernacNames.isIdentifier(method.name())) diagnostics.add(CompilerDiagnostic.error(method.location(),
                    "Invalid Java member name '" + method.name() + "'."));
            for (var parameter : method.parameters()) if (!VernacNames.isIdentifier(parameter.name()))
                diagnostics.add(CompilerDiagnostic.error(parameter.location(), "Invalid Java parameter name '" + parameter.name() + "'."));
            diagnostics.addAll(MemberNames.duplicates(method.parameters(), "parameter", collection.name()));
            if (!method.parameters().stream().allMatch(p -> types.containsKey(p.type()))) continue;
            String signature = method.name() + "(" + method.parameters().stream()
                    .map(p -> JavaTypeNames.canonicalName(types.get(p.type()))).collect(Collectors.joining(",")) + ")";
            if (!signatures.add(signature)) diagnostics.add(CompilerDiagnostic.error(method.location(),
                    "Collection method '" + signature + "' conflicts with a generated, inherited, or previously declared method."));
            if (method.parameters().isEmpty() && Set.of("clone", "finalize").contains(method.name()) && types.containsKey(method.returnType())) {
                ResolvedType result = types.get(method.returnType());
                boolean reference = method.returnType().isOptional() || result instanceof ResolvedType.Declared
                        || result instanceof ResolvedType.Builtin builtin && !builtin.javaType().isPrimitive();
                boolean compatible = method.name().equals("clone") ? reference : result instanceof ResolvedType.VoidReturn;
                if (!compatible || !method.accessModifier().equals("public")) diagnostics.add(CompilerDiagnostic.error(method.location(),
                        "Collection method '" + signature + "' conflicts with its inherited Object method."));
            }
        }
        return diagnostics;
    }
}
