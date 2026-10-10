// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.util.MemberNames;
import org.vernac.language.VernacNames;

/** Domain service boundaries are checked before Java generation. */
final class ServiceContracts {
    private static final Set<String> RESERVED = Set.of("getClass", "toString", "hashCode", "equals", "notify", "notifyAll", "wait", "clone", "finalize");
    void validate(DomainServiceNode service, FileTypeScope scope, Map<TypeNode, ResolvedType> types,
            List<CompilerDiagnostic> errors, VernacProject project, String namespace) {
        names(service.injections(), errors);
        for (var dependency : service.injections()) {
            var type = resolve(dependency.type(), scope, types, errors);
            if (dependency.type().isOptional() || !(type instanceof ResolvedType.Declared d)
                    || !Set.of(TypeSymbol.Kind.DOMAIN_SERVICE, TypeSymbol.Kind.PORT, TypeSymbol.Kind.REPOSITORY).contains(d.symbol().kind()))
                error(errors, dependency.location(), "Service uses requires a non-optional domain service, port or repository; usecases and adapters are not allowed.");
        }
        // Reuse import validation, allowing domain repository interfaces (but not adapters).
        var allowedRepositories = new HashSet<String>();
        for (var source : project.sources()) for (var symbol : project.symbols().inNamespace(source.unit().namespace()))
            if (symbol.kind() == TypeSymbol.Kind.REPOSITORY) allowedRepositories.add(symbol.identity().namespace() + ".domain." + symbol.identity().name());
        var importsToCheck = service.javaImports().stream().filter(i -> !allowedRepositories.contains(i.target())).toList();
        errors.addAll(BehaviorImports.validate(project, scope, namespace, service.name(), importsToCheck,
                service.methods().stream().map(ServiceMethodNode::method).toList()));
        Map<String, String> visible = new HashMap<>(BehaviorImports.visible(project, scope));
        for (var source : project.sources()) for (var symbol : project.symbols().inNamespace(source.unit().namespace())) {
            var lookup = scope.resolve(symbol.identity().name(), service.location());
            if (lookup.type().orElse(null) instanceof ResolvedType.Declared d && d.symbol().equals(symbol))
                visible.putIfAbsent(symbol.identity().name(), UseCaseContracts.javaName(d));
        }
        for (var imported : service.javaImports()) {
            String simple = imported.target().substring(imported.target().lastIndexOf('.') + 1);
            String previous = visible.putIfAbsent(simple, imported.target());
            if (previous != null && !previous.equals(imported.target())) error(errors, imported.location(), "Java import conflicts with visible type: " + simple);
            for (var source : project.sources()) for (var symbol : project.symbols().inNamespace(source.unit().namespace()))
                if (symbol.kind() == TypeSymbol.Kind.USE_CASE && imported.target().equals(UseCaseContracts.javaName(new ResolvedType.Declared(symbol))))
                    error(errors, imported.location(), "Domain service cannot import a known usecase.");
        }
        Set<String> forbiddenUsecases = new HashSet<>();
        for (var source : project.sources()) for (var symbol : project.symbols().inNamespace(source.unit().namespace()))
            if (symbol.kind() == TypeSymbol.Kind.USE_CASE) forbiddenUsecases.add(UseCaseContracts.javaName(new ResolvedType.Declared(symbol)));
        Set<String> signatures = new HashSet<>();
        Set<String> publicSignatures = new HashSet<>();
        if (service.methods().stream().noneMatch(m -> m.method().accessModifier().equals("public")))
            error(errors, service.location(), "A service requires at least one public operation.");
        for (var operation : service.methods()) {
            var method = operation.method();
            names(method.parameters(), errors);
            if (method.implementation().filter(forbiddenUsecases::contains).isPresent())
                error(errors, method.location(), "Domain service cannot delegate to a known usecase.");
            if (!VernacNames.isIdentifier(method.name()) || RESERVED.contains(method.name()) || method.name().startsWith("__"))
                error(errors, method.location(), "Service operation conflicts with a reserved/generated method: " + method.name());
            if (method.parameters().isEmpty() && service.injections().stream().anyMatch(f -> f.name().equals(method.name())))
                error(errors, method.location(), "Operation conflicts with a dependency accessor: " + method.name());
            if (method.accessModifier().equals("private") && !operation.validations().isEmpty())
                error(errors, method.location(), "validates belongs to public service operations; private helpers stay inline.");
            resolve(method.returnType(), scope, types, errors);
            for (var field : method.parameters()) resolve(field.type(), scope, types, errors);
            String signature = method.name() + method.parameters().stream().map(f -> f.type().isOptional() ? "java.util.Optional" :
                    types.containsKey(f.type()) ? UseCaseContracts.javaName(types.get(f.type())) : f.type().name()).toList();
            if (!signatures.add(signature)) error(errors, method.location(), "Duplicate service implementation signature: " + method.name());
            if (method.accessModifier().equals("public")) {
                String publicSignature = method.name() + method.parameters().stream().map(f -> types.containsKey(f.type())
                        ? UseCaseContracts.javaName(types.get(f.type())) : f.type().name()).toList();
                if (!publicSignatures.add(publicSignature)) error(errors, method.location(), "Duplicate public service signature: " + method.name());
            }
            // Apply the established explicit self.parameter() validation contract.
            var validation = new UseCaseNode(method.location(), service.name(), method.parameters(), operation.validations(),
                    List.of(), Optional.empty(), List.of(), service.javaImports(), List.of(), "", Optional.empty(), 1);
            errors.addAll(ValidationContracts.validate(validation, namespace, project));
        }
    }
    private ResolvedType resolve(TypeNode type, FileTypeScope scope, Map<TypeNode, ResolvedType> types, List<CompilerDiagnostic> errors) {
        if (type.name().equals("void") && !type.isOptional() && type.typeArguments().isEmpty()) {
            var result = new ResolvedType.VoidReturn(); types.put(type, result); return result;
        }
        var lookup = scope.resolve(type.name(), type.location()); errors.addAll(lookup.diagnostics());
        if (!type.typeArguments().isEmpty()) error(errors, type.location(), "Use declared Vernac collections instead of generic types.");
        lookup.type().ifPresent(t -> {
            types.put(type, t);
            if (t instanceof ResolvedType.Declared d && !Set.of(TypeSymbol.Kind.ID, TypeSymbol.Kind.VALUE_OBJECT, TypeSymbol.Kind.ENUM,
                    TypeSymbol.Kind.ENTITY, TypeSymbol.Kind.AGGREGATE, TypeSymbol.Kind.COLLECTION,
                    TypeSymbol.Kind.DOMAIN_SERVICE, TypeSymbol.Kind.PORT, TypeSymbol.Kind.REPOSITORY).contains(d.symbol().kind()))
                error(errors, type.location(), "Type is not available in domain service signatures.");
            if (type.isOptional() && t instanceof ResolvedType.Builtin primitive && primitive.javaType().isPrimitive())
                error(errors, type.location(), "Optional primitives require a boxed type or value object.");
        });
        return lookup.type().orElse(null);
    }
    private void names(List<FieldNode> fields, List<CompilerDiagnostic> errors) {
        errors.addAll(MemberNames.duplicates(fields, "service parameter/dependency", ""));
        for (var field : fields) if (!VernacNames.isIdentifier(field.name()) || field.name().equals("self")
                || field.name().startsWith("__") || RESERVED.contains(field.name()) || field.isMutable() || field.type().name().equals("void"))
            error(errors, field.location(), "Invalid service parameter/dependency name or type: " + field.name());
    }
    private static void error(List<CompilerDiagnostic> errors, SourceLocation at, String message) {
        errors.add(CompilerDiagnostic.error(at, message));
    }
}
