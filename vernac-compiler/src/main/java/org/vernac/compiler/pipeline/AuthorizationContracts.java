// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.util.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;

/** Source-located authorization validation before any Java is generated. */
final class AuthorizationContracts {
    void declaration(PermissionNode permission, List<CompilerDiagnostic> errors) {
        if (permission.members().isEmpty()) error(errors, permission.location(), "A permission declaration requires at least one member.");
        var seen = new HashSet<String>();
        for (var member : permission.members()) {
            if (!VernacNames.isIdentifier(member.name())) error(errors, member.location(), "Invalid Java permission member: " + member.name());
            if (!seen.add(member.name())) error(errors, member.location(), "Duplicate permission member: " + member.name());
        }
    }

    void requirement(AuthorizationNode node, UseCaseNode operation, FileTypeScope scope,
            Map<TypeNode, ResolvedType> types, List<CompilerDiagnostic> errors, VernacProject project) {
        if (node instanceof AuthorizationNode.Mode) return;
        if (node instanceof AuthorizationNode.Permission leaf) {
            leaf(leaf, operation, scope, types, errors, project); return;
        }
        var group = (AuthorizationNode.Group) node;
        if (group.children().isEmpty()) error(errors, group.location(), "Authorization all/any groups must not be empty.");
        var siblings = new HashSet<String>();
        for (var child : group.children()) {
            requirement(child, operation, scope, types, errors, project);
            if (child instanceof AuthorizationNode.Permission leaf && types.get(leaf.type()) instanceof ResolvedType.Declared declared) {
                String identity = declared.symbol().identity().qualifiedName() + "." + leaf.member() + " on " + leaf.resource().orElse("");
                if (!siblings.add(identity)) error(errors, leaf.location(), "Duplicate sibling permission requirement: " + identity);
            }
        }
    }

    private void leaf(AuthorizationNode.Permission leaf, UseCaseNode operation, FileTypeScope scope,
            Map<TypeNode, ResolvedType> types, List<CompilerDiagnostic> errors, VernacProject project) {
        if (leaf.member().isBlank()) {
            error(errors, leaf.location(), "Expected a permission declaration and member, for example InvoicePermissions.VIEW."); return;
        }
        var lookup = scope.resolve(leaf.type().name(), leaf.location());
        errors.addAll(lookup.diagnostics());
        if (lookup.type().orElse(null) instanceof ResolvedType.Declared declared && declared.symbol().kind() == TypeSymbol.Kind.PERMISSION) {
            types.put(leaf.type(), declared);
            var declaration = project.sources().stream().filter(s -> s.unit().namespace().equals(declared.symbol().identity().namespace()))
                    .flatMap(s -> s.unit().definitions().stream()).filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                    .filter(p -> p.name().equals(declared.symbol().identity().name())).findFirst();
            if (declaration.isEmpty() || declaration.get().members().stream().noneMatch(m -> m.name().equals(leaf.member())))
                error(errors, leaf.location(), "Unknown permission member: " + declared.symbol().identity().qualifiedName() + "." + leaf.member());
        } else if (lookup.type().isPresent()) error(errors, leaf.location(), "requires permission expects a permission declaration, not a domain enum or another type.");
        if (leaf.resource().isPresent()) {
            String name = leaf.resource().get();
            var parameter = operation.parameters().stream().filter(p -> p.name().equals(name)).findFirst();
            if (parameter.isEmpty()) error(errors, leaf.location(), "Unknown authorization resource input '" + name + "'; on requires a named usecase input, not a dependency or expression.");
            else {
                var field = parameter.get();
                var type = types.get(field.type());
                if (field.type().isOptional() || field.type().paginated() || !(type instanceof ResolvedType.Declared d)
                        || !Set.of(TypeSymbol.Kind.ID, TypeSymbol.Kind.VALUE_OBJECT, TypeSymbol.Kind.ENUM).contains(d.symbol().kind()))
                    error(errors, leaf.location(), "Authorization on requires a required ID or value-object input; optional values, collections and pages are not resources.");
            }
        }
    }
    private static void error(List<CompilerDiagnostic> errors, SourceLocation at, String text) { errors.add(CompilerDiagnostic.error(at, text)); }
}
