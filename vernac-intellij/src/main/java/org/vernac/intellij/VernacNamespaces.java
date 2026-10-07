// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import javax.lang.model.SourceVersion;
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
        // Java 21 package-name rule plus the current lexer identifier alphabet.
        if (namespace == null || !SourceVersion.isName(namespace, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException("Enter a qualified namespace such as org.example.tasks. Java keywords are not allowed.");
        }
        if (!namespace.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw new IllegalArgumentException("The current Vernac lexer supports only ASCII letters, digits and underscores in namespace segments.");
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
