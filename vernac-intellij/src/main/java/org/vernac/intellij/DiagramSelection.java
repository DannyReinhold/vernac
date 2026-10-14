// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import org.vernac.language.VernacNames;

import java.nio.file.*;
import java.util.Optional;

/** Path selection shared by the action's availability and execution. */
public record DiagramSelection(Path projectRoot, Path sourceRoot, String namespace) {
    public static Optional<DiagramSelection> from(Path selection) {
        Path path = selection.toAbsolutePath().normalize();
        Path directory = Files.isDirectory(path) ? path : path.getParent();
        for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
            if (!candidate.endsWith(Path.of("src", "main", "vernac"))) continue;
            String namespace =
                    candidate
                            .relativize(directory)
                            .toString()
                            .replace(candidate.getFileSystem().getSeparator(), ".");
            Path project = candidate.getParent().getParent().getParent();
            if (VernacNames.isNamespace(namespace)
                    && Files.isRegularFile(project.resolve("pom.xml")))
                return Optional.of(new DiagramSelection(project, candidate, namespace));
        }
        return Optional.empty();
    }
}
