// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.ProjectSymbolIndex;

/** Namespace-local module interfaces for product code compiled into a single application. */
public final class SourceModules {
    private final Map<String, String> internal = new TreeMap<>();
    private final Map<String, ProjectSymbolIndex> views = new HashMap<>();
    private final ProjectSymbolIndex index;

    public SourceModules(VernacProject project, Map<Path, String> snapshots, List<CompilerDiagnostic> diagnostics) {
        index = project.symbols();
        var namespaces = new TreeSet<String>();
        for (var source : project.sources()) if (!source.external()) namespaces.add(source.unit().namespace());
        for (String namespace : namespaces) {
            Path path = project.sourceRoot().resolve(namespace.replace('.', '/')).resolve(ModuleDefinition.FILE);
            if (!snapshots.containsKey(path) && !Files.isRegularFile(path)) continue;
            var location = new SourceLocation(path.toString(), 1, 1);
            try {
                String text = snapshots.containsKey(path) ? snapshots.get(path) : Files.readString(path);
                var module = ModuleDefinition.parse(path, text);
                if (!module.name().equals(namespace)) {
                    diagnostics.add(CompilerDiagnostic.error(location, "Source module name must match its namespace: " + namespace));
                    continue;
                }
                var members = new HashSet<String>();
                index.inNamespace(namespace).forEach(symbol -> members.add(symbol.identity().qualifiedName()));
                for (var export : module.exports()) {
                    if (!members.remove(export.name()))
                        diagnostics.add(CompilerDiagnostic.error(export.location(), "Unknown export in source module " + namespace + ": " + export.name()));
                }
                members.forEach(name -> internal.put(name, namespace));
                if (module.explicitTenancy()) {
                    project.sources().stream().filter(s -> !s.external() && s.unit().namespace().equals(namespace))
                            .flatMap(s -> s.unit().definitions().stream()).filter(AggregateNode.class::isInstance)
                            .map(AggregateNode.class::cast).filter(a -> !a.global() && !a.tenant())
                            .forEach(a -> diagnostics.add(CompilerDiagnostic.error(a.location(),
                                    "Choose tenant aggregate or global aggregate in this explicitly classified module.")));
                }
            } catch (SemanticValidationException e) {
                diagnostics.addAll(e.diagnostics());
            } catch (IOException e) {
                diagnostics.add(CompilerDiagnostic.error(location, "Cannot read source module interface."));
            }
        }
    }

    public ProjectSymbolIndex visibleFrom(String namespace) {
        if (internal.isEmpty()) return index;
        return views.computeIfAbsent(namespace, current -> {
            var hidden = new HashMap<>(internal);
            hidden.values().removeIf(current::equals);
            return index.withoutDeclarations(hidden);
        });
    }
}
