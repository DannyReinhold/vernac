// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Generated sources from multiple namespaces, with non-fatal analysis diagnostics. */
public record VernacProjectCompilationResult(List<JavaFile> generatedFiles, List<CompilerDiagnostic> diagnostics) {
    public VernacProjectCompilationResult {
        generatedFiles = List.copyOf(generatedFiles);
        diagnostics = List.copyOf(diagnostics);
    }

    public void writeTo(Path outputDirectory) throws IOException {
        GeneratedSourceWriter.write(generatedFiles, outputDirectory);
    }
}
