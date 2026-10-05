// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public record VernacCompilationResult(
        String packageName,
        List<JavaFile> generatedFiles
) {
    public VernacCompilationResult {
        generatedFiles = Collections.unmodifiableList(generatedFiles);
    }

    public void writeTo(Path outputDirectory) throws IOException {
        for (JavaFile file : generatedFiles) {
            file.writeTo(outputDirectory);
        }
    }
}