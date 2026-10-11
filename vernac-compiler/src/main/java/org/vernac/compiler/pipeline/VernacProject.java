// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.symbols.ProjectSymbolIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Parsed, indexed project. Field references and imports are not resolved yet. */
public record VernacProject(Path sourceRoot, List<VernacSourceFile> sources,
                            ProjectSymbolIndex symbols) {
    public VernacProject {
        sourceRoot = Objects.requireNonNull(sourceRoot).toAbsolutePath().normalize();
        sources = List.copyOf(sources);
        Objects.requireNonNull(symbols);
    }
}
