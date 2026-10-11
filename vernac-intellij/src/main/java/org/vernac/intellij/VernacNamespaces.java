// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import org.vernac.language.VernacNames;
import java.nio.file.Path;
import java.util.Optional;

/** Namespace layout rules shared by the two creation actions. */
final class VernacNamespaces {
    private VernacNamespaces() { }

    static Optional<Path> sourceRoot(Path directory) {
        for (Path current = directory.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (current.endsWith(Path.of("src", "main", "vernac"))) return Optional.of(current);
        }
        return Optional.empty();
    }

    static String namespace(Path root, Path directory) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("The directory must be inside the Vernac source root.");
        }
        String result = normalizedRoot.relativize(normalizedDirectory).toString()
                .replace(directory.getFileSystem().getSeparator(), ".");
        requireNamespace(result);
        if (!destination(normalizedRoot, result).equals(normalizedDirectory)) {
            throw new IllegalArgumentException("Each namespace segment must have its own directory.");
        }
        return result;
    }

    static Path destination(Path root, String namespace) {
        requireNamespace(namespace);
        return root.toAbsolutePath().normalize().resolve(namespace.replace('.', '/'));
    }

    static void requireNamespace(String namespace) {
        if (!VernacNames.isNamespace(namespace)) {
            throw new IllegalArgumentException("Enter a Java-compatible namespace such as org.example.tasks. "
                    + "Unicode names are supported. " + VernacNames.invalidNamespaceMessage(namespace));
        }
    }

    static String fileStem(String name) {
        String stem = name.endsWith(".vernac") ? name.substring(0, name.length() - 7) : name;
        if (stem.isBlank() || stem.equals(".") || stem.equals("..")
                || !name.equals(name.strip()) || stem.endsWith(".")
                || stem.chars().anyMatch(c -> c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Enter a file name, without a directory path.");
        }
        return stem;
    }
}
