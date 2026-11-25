// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.modules.ModuleArtifacts;
import org.vernac.compiler.modules.ModuleDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Checks isolated source revisions against fixed module inputs. */
public final class WorkspaceCheckSession {
    public record Check(long revision, Optional<ResolvedProject> project, List<CompilerDiagnostic> diagnostics,
                        VernacProjectLoader.ParseStatistics parsing, boolean reusedCheck) {
        public Check { diagnostics = List.copyOf(diagnostics); }
    }

    private final VernacProjectLoader.ParseSession parser;
    private final ProjectTypeResolver resolver = new ProjectTypeResolver();
    private final Map<Path, String> configuration;
    private final List<ModuleDescriptor> modules;
    private VersionedSources.Snapshot last;
    private Check result;

    public WorkspaceCheckSession(Path root, Map<Path, String> configuration, List<ModuleDescriptor> modules) {
        parser = new VernacProjectLoader().openSession(root);
        this.configuration = Map.copyOf(configuration);
        this.modules = List.copyOf(modules);
    }

    public synchronized Check check(VersionedSources.Snapshot snapshot) throws IOException {
        if (snapshot.equals(last)) {
            return new Check(result.revision(), result.project(), result.diagnostics(),
                    new VernacProjectLoader.ParseStatistics(0, 0), true);
        }
        Check checked;
        try {
            var project = ModuleArtifacts.merge(parser.loadSnapshot(snapshot.sources()), modules);
            var resolved = resolver.resolveSnapshot(project, configuration);
            checked = new Check(snapshot.revision(), Optional.of(resolved), resolved.diagnostics(), parser.statistics(), false);
        } catch (SemanticValidationException failure) {
            checked = new Check(snapshot.revision(), Optional.empty(), failure.diagnostics(), parser.statistics(), false);
        }
        last = snapshot;
        result = checked;
        return checked;
    }
}
