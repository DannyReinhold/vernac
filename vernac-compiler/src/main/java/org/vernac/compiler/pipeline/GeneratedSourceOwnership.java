// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Read-only access to the compiler's existing ownership inventory for IDE integrations. */
public final class GeneratedSourceOwnership {
    private GeneratedSourceOwnership() { }

    public static Optional<Path> outputRootOf(Path javaSource) throws IOException {
        Path source = javaSource.toAbsolutePath().normalize();
        if (!source.getFileName().toString().endsWith(".java") || !Files.isRegularFile(source)) return Optional.empty();
        for (Path root = source.getParent(); root != null; root = root.getParent()) {
            if (!Files.exists(root.resolve(GeneratedSourceWriter.MANIFEST))) continue;
            // Use exactly the same manifest validation as generation/cleanup.
            return GeneratedSourceWriter.readManifest(root).contains(root.relativize(source))
                    ? Optional.of(root) : Optional.empty();
        }
        return Optional.empty();
    }
}
