// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.symbols.*;

/** Roles are application configuration, resolved with the same file scope as usecase permissions. */
public final class RoleDeclarations {
    public static final String RESOURCE = "META-INF/vernac/roles.json";
    public void validate(VernacProject project, Map<java.nio.file.Path,FileTypeScope> scopes, List<CompilerDiagnostic> errors) {
        Set<String> keys = new HashSet<>();
        var rows = new ArrayList<Map<String,Object>>();
        SourceLocation first = null;
        for (var source : project.sources()) {
            if (source.external()) continue;
            for (var definition : source.unit().definitions()) if (definition instanceof RoleNode role) {
                if (first == null) first = role.location();
                if (!role.key().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){1,15}") || role.key().length() > 256)
                    error(errors, role.location(), "Role keys must be qualified identifiers of at most 256 characters.");
                if (!keys.add(role.key())) error(errors, role.location(), "Duplicate role: " + role.key());
                if (keys.size() > 128) error(errors, role.location(), "At most 128 roles are supported.");
                if (role.permissions().size() > 128) error(errors, role.location(), "At most 128 permissions per role are supported.");
                Set<String> permissions = new HashSet<>();
                for (var leaf : role.permissions()) {
                    if (leaf.resource().isPresent()) error(errors, leaf.location(), "Role grants cannot bind a usecase resource; declare object policy in the application.");
                    var lookup = scopes.get(source.path()).resolve(leaf.type().name(),leaf.location());
                    errors.addAll(lookup.diagnostics());
                    if (lookup.type().orElse(null) instanceof ResolvedType.Declared d && d.symbol().kind() == TypeSymbol.Kind.PERMISSION) {
                        String key = d.symbol().identity().qualifiedName() + "." + leaf.member();
                        boolean known = project.sources().stream().filter(s -> s.unit().namespace().equals(d.symbol().identity().namespace()))
                            .flatMap(s -> s.unit().definitions().stream()).filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                            .anyMatch(p -> p.name().equals(d.symbol().identity().name()) && p.members().stream().anyMatch(m -> m.name().equals(leaf.member())));
                        if (!known) error(errors,leaf.location(),"Unknown permission member: " + key);
                        if (!permissions.add(key)) error(errors,leaf.location(),"Duplicate role permission: " + key);
                    } else if (lookup.type().isPresent()) error(errors,leaf.location(),"Role grants require a declared permission, not a domain type.");
                }
                rows.add(Map.of("key",role.key(),"permissions",permissions.stream().sorted().toList()));
            }
        }
        if (first != null && org.vernac.compiler.modules.ModuleDescriptor.JSON.writeValueAsBytes(Map.of("format",1,"roles",rows)).length > 65_536)
            error(errors,first,"Generated role catalog exceeds the 64 KiB runtime limit.");
    }
    /** Only called after successful analysis; dependencies cannot contribute implicit product roles. */
    public Map<String,Object> catalog(ResolvedProject resolved) {
        var rows = new TreeMap<String,Map<String,Object>>();
        for (var source : resolved.project().sources()) {
            if (source.external()) continue;
            for (var definition : source.unit().definitions()) if (definition instanceof RoleNode role) {
                var permissions = role.permissions().stream().map(leaf -> {
                    var type = (ResolvedType.Declared) resolved.scopes().get(source.path()).resolve(leaf.type().name(),leaf.location()).type().orElseThrow();
                    return type.symbol().identity().qualifiedName() + "." + leaf.member();
                }).sorted().toList();
                rows.put(role.key(),Map.of("key",role.key(),"permissions",permissions));
            }
        }
        return Map.of("format",1,"roles",List.copyOf(rows.values()));
    }
    private static void error(List<CompilerDiagnostic> errors,SourceLocation at,String text) {errors.add(CompilerDiagnostic.error(at,text));}
}
