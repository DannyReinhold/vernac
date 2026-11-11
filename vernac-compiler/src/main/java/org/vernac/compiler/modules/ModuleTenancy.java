// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.util.List;
import java.util.Map;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.ast.RepositoryNode;
import org.vernac.compiler.ast.TopLevelDefinition;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.pipeline.VernacProject;
import org.vernac.compiler.symbols.FileTypeScope;
import org.vernac.compiler.symbols.ResolvedType;

/** Tenant metadata is importable before persistent module activation is enabled. */
public final class ModuleTenancy {
    private ModuleTenancy() { }

    public static String aggregateScope(TopLevelDefinition definition) {
        if (!(definition instanceof AggregateNode aggregate)) return "NONE";
        return aggregate.tenant() ? "TENANT" : "GLOBAL";
    }

    public static void validateDefinition(ModuleDescriptor.Definition definition) {
        if (!definition.kind().equals("ENTITY")) return;
        boolean restorer = definition.javaMembers().keySet().stream()
                .anyMatch(key -> key.startsWith("__vernacRestore(")
                        && key.endsWith(",org.vernac.runtime.tenancy.TenantOwnership)"));
        if (definition.entityOwnershipVersion() != 1 || !restorer
                || !definition.javaMembers().containsKey("__vernacTenantOwnership()"))
            throw new IllegalArgumentException("Missing entity ownership/restoration contract: "
                    + definition.identity() + ". Rebuild the producer with the current compiler.");
    }

    public static void validatePublication(ResolvedProject project, List<CompilerDiagnostic> diagnostics) {
        validatePublication(project.project(), project.scopes(), diagnostics);
    }

    public static void validatePublication(VernacProject project, Map<java.nio.file.Path, FileTypeScope> scopes,
                                           List<CompilerDiagnostic> diagnostics) {
        for (var source : project.sources()) {
            if (source.external()) continue;
            for (var definition : source.unit().definitions()) {
                if (!(definition instanceof RepositoryNode repository)) continue;
                var resolved = scopes.get(source.path()).resolve(repository.aggregateName(), repository.location());
                if (!(resolved.type().orElse(null) instanceof ResolvedType.Declared target)) continue;
                if (tenantAggregate(project, target.symbol().identity().qualifiedName()))
                    diagnostics.add(CompilerDiagnostic.error(repository.location(),
                            "Tenant repositories in reusable modules require verified tenant activation/RLS startup, "
                                    + "which is not implemented yet. Tenant type exports and separate migration connections are supported."));
            }
        }
    }

    private static boolean tenantAggregate(VernacProject project, String identity) {
        return project.sources().stream().anyMatch(source -> source.unit().definitions().stream()
                .anyMatch(definition -> definition instanceof AggregateNode aggregate && aggregate.tenant()
                        && (source.unit().namespace() + "." + aggregate.name()).equals(identity)));
    }
}
