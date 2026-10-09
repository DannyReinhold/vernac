// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.generator.PackageResolver;
import java.util.*;

/** File-scoped Vernac visibility and explicit Java imports never silently shadow each other. */
public final class BehaviorImports {
    private BehaviorImports() {}

    public static Map<String, String> visible(VernacProject project, FileTypeScope scope) {
        Map<String, String> result = new TreeMap<>();
        Set<String> names = new TreeSet<>(BuiltinTypes.names());
        for (var source : project.sources())
            project.symbols().inNamespace(source.unit().namespace()).forEach(s -> names.add(s.identity().name()));
        for (String name : names) {
            var lookup = scope.resolve(name, SourceLocation.UNKNOWN);
            if (lookup.type().isPresent()) {
                var type = lookup.type().get();
                if (type instanceof ResolvedType.Builtin || type instanceof ResolvedType.Declared d && switch (d.symbol().kind()) {
                    case ID, VALUE_OBJECT, ENUM, ENTITY, AGGREGATE, COLLECTION -> true;
                    default -> false;
                }) result.put(name, JavaTypeNames.canonicalName(type));
            }
        }
        // Optional is part of the behavior return contract.
        result.putIfAbsent("Optional", "java.util.Optional");
        return result;
    }

    public static List<CompilerDiagnostic> validate(VernacProject project, FileTypeScope scope,
            String namespace, String owner, List<JavaImportNode> imports, List<MethodNode> methods) {
        List<CompilerDiagnostic> errors = new ArrayList<>();
        Map<String, String> names = visible(project, scope);
        Set<String> forbidden = forbidden(project);
        String companion = "__VernacBehavior_" + owner;
        if (project.symbols().find(namespace + "." + companion).isPresent())
            errors.add(CompilerDiagnostic.error(project.symbols().find(namespace + "." + companion).orElseThrow().location(),
                    "Type name '" + companion + "' conflicts with a generated behavior implementation."));
        for (var imported : imports) {
            String target = imported.target();
            String simple = target.substring(target.lastIndexOf('.') + 1);
            if (!target.contains(".") || Arrays.stream(target.split("\\.")).anyMatch(s -> !org.vernac.language.VernacNames.isIdentifier(s))) {
                errors.add(CompilerDiagnostic.error(imported.location(), "Expected a fully qualified Java type import: '" + target + "'."));
                continue;
            }
            var existing = scope.resolve(simple, imported.location());
            if (existing.diagnostics().stream().anyMatch(d -> d.message().startsWith("Ambiguous type")))
                errors.add(CompilerDiagnostic.error(imported.location(), "Java import '" + target
                        + "' cannot resolve an ambiguous Vernac type name. Disambiguate the Vernac imports first."));
            String previous = names.putIfAbsent(simple, target);
            if (simple.equals(companion) || previous != null && !previous.equals(target))
                errors.add(CompilerDiagnostic.error(imported.location(), "Java import '" + target
                        + "' conflicts with visible type '" + (previous == null ? companion : previous)
                        + "'. Use the fully qualified Java name in the implementation."));
            if (forbidden.contains(target)) errors.add(CompilerDiagnostic.error(imported.location(),
                    "Domain behavior cannot import the known repository or adapter '" + target + "'."));
        }
        for (var method : methods) {
            if (!Set.of("public", "private").contains(method.accessModifier()))
                errors.add(CompilerDiagnostic.error(method.location(), "Behavior methods must be public or private."));
            if (method.accessModifier().equals("public")) for (var parameter : method.parameters())
                if (parameter.name().equals("self")) errors.add(CompilerDiagnostic.error(parameter.location(),
                        "Parameter name 'self' is reserved for the domain receiver in public behavior methods."));
            method.implementation().ifPresent(target -> {
                if (!target.contains(".") || Arrays.stream(target.split("\\.")).anyMatch(s -> !org.vernac.language.VernacNames.isIdentifier(s)))
                    errors.add(CompilerDiagnostic.error(method.location(), "implemented by requires a fully qualified Java type name."));
                if (!method.accessModifier().equals("public")) errors.add(CompilerDiagnostic.error(method.location(),
                        "implemented by is only supported for public behavior methods. Keep private helpers inline."));
                if (forbidden.contains(target)) errors.add(CompilerDiagnostic.error(method.location(),
                        "Domain behavior cannot delegate to the known repository or adapter '" + target + "'."));
            });
        }
        return errors;
    }

    public static List<CompilerDiagnostic> validateLowered(String namespace, String owner,
            List<MethodNode> methods, Map<TypeNode, ResolvedType> types) {
        Set<String> signatures = new HashSet<>();
        List<CompilerDiagnostic> errors = new ArrayList<>();
        for (var method : methods) {
            if (method.parameters().stream().anyMatch(p -> !types.containsKey(p.type()))) continue;
            List<String> parameters = new ArrayList<>();
            if (method.accessModifier().equals("public")) parameters.add(namespace + (method.mode() == MethodNode.Mode.DEFAULT ? ".domain." : ".domain.access.") + owner + (method.mode() == MethodNode.Mode.DEFAULT ? "" : method.mode() == MethodNode.Mode.READ ? "Read" : "Access"));
            method.parameters().forEach(p -> parameters.add(JavaTypeNames.canonicalName(types.get(p.type()))));
            String signature = method.name() + "(" + String.join(",", parameters) + ")";
            if (!signatures.add(signature)) errors.add(CompilerDiagnostic.error(method.location(),
                    "Behavior implementation signature '" + signature + "' conflicts after adding the self receiver. Rename the private helper."));
        }
        return errors;
    }

    private static Set<String> forbidden(VernacProject project) {
        Set<String> result = new HashSet<>();
        for (var source : project.sources()) for (var definition : source.unit().definitions()) {
            String ns = source.unit().namespace();
            if (definition instanceof RepositoryNode repository) {
                result.add(ns + "." + repository.name());
                result.add(PackageResolver.resolveDomainPackage(ns, repository.customPackage()) + "." + repository.name());
                result.add(PackageResolver.resolveAdapterPackage(ns, repository.customPackage()) + ".Jdbc" + repository.name());
            } else if (definition instanceof PortNode port) {
                for (var method : port.methods()) if (method.adapter() != null) {
                    String pkg = PackageResolver.resolveOutboundAdapterPackage(ns, port.name(), method.adapter().customPackage());
                    String suffix = Character.toUpperCase(method.name().charAt(0)) + method.name().substring(1);
                    if (method.adapter() instanceof RestAdapterNode) result.add(pkg + ".Rest" + port.name() + suffix + "Adapter");
                    if (method.adapter() instanceof CustomAdapterNode custom)
                        result.add(pkg + "." + custom.delegateName().orElse(port.name() + suffix + "Delegate"));
                }
            }
        }
        return result;
    }
}
