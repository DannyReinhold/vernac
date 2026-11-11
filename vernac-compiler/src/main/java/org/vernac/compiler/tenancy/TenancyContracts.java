// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tenancy;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.application.ApplicationDefinition;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

/** Source-controlled opt-in classification rule. Does not infer or create tenant ownership. */
public final class TenancyContracts {
    private TenancyContracts() {}

    public static List<CompilerDiagnostic> validate(VernacProject project, Map<java.nio.file.Path, FileTypeScope> scopes) {
        return validate(project, scopes, Map.of());
    }

    /** Editor configuration snapshots override disk without changing build behavior. */
    public static List<CompilerDiagnostic> validate(VernacProject project, Map<java.nio.file.Path, FileTypeScope> scopes,
                                                  Map<java.nio.file.Path, String> snapshots) {
        var base = ModuleArtifacts.projectDirectory(project.sourceRoot());
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        boolean explicit = false;
        for (String name : List.of(ApplicationDefinition.FILE, ModuleDefinition.FILE)) {
            var path = base.resolve(name);
            if (!snapshots.containsKey(path) && !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                String text = snapshots.containsKey(path) ? snapshots.get(path) : Files.readString(path);
                boolean selected = name.equals(ApplicationDefinition.FILE)
                        ? ApplicationDefinition.parse(path, text).explicitTenancy()
                        : ModuleDefinition.parse(path, text).explicitTenancy();
                explicit |= selected;
            } catch (SemanticValidationException invalid) {
                diagnostics.addAll(invalid.diagnostics());
            } catch (IOException failed) {
                diagnostics.add(CompilerDiagnostic.error(new SourceLocation(path.toString(), 1, 1),
                        "Cannot read Vernac tenancy configuration."));
            }
        }
        if (Files.exists(base.resolve(ModuleDefinition.FILE))) {
            for (var source : project.sources()) if (!source.external()) for (var definition : source.unit().definitions())
                if (definition instanceof AggregateNode aggregate && aggregate.tenant())
                    diagnostics.add(CompilerDiagnostic.error(aggregate.location(),
                            "Tenant aggregates in reusable modules require tenant metadata/activation support, which is not implemented yet."));
        }
        if (!explicit) return diagnostics;
        Map<String, AggregateNode> own = new HashMap<>();
        for (var source : project.sources()) if (!source.external()) for (var definition : source.unit().definitions()) {
            if (definition instanceof AggregateNode aggregate) own.put(source.unit().namespace() + "." + aggregate.name(), aggregate);
        }
        Set<String> checked = new HashSet<>();
        for (var source : project.sources()) if (!source.external()) for (var definition : source.unit().definitions()) {
            if (!(definition instanceof RepositoryNode repository)) continue;
            var resolved = scopes.get(source.path()).resolve(repository.aggregateName(), repository.location()).type().orElse(null);
            if (!(resolved instanceof ResolvedType.Declared declared)) continue;
            String identity = declared.symbol().identity().qualifiedName();
            var aggregate = own.get(identity);
            if (aggregate != null && !aggregate.global() && !aggregate.tenant() && checked.add(identity)) {
                diagnostics.add(CompilerDiagnostic.error(aggregate.location(),
                        "tenancy explicit requires classification of persisted aggregate '" + identity
                        + "'. Choose tenant aggregate or global aggregate explicitly; no classification is inferred."));
            }
        }
        return diagnostics;
    }
}
