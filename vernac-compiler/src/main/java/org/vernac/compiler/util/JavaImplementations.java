// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.util;

import org.vernac.compiler.ast.JavaImportNode;
import org.vernac.language.VernacNames;

import java.util.Arrays;
import java.util.List;

/**
 * One lexical resolution rule for external static implementations. Does not guess class existence.
 */
public final class JavaImplementations {
    private JavaImplementations() {}

    public static boolean validName(String name) {
        return !name.isEmpty()
                && Arrays.stream(name.split("\\.", -1)).allMatch(VernacNames::isIdentifier);
    }

    public static boolean validate(
            String name,
            List<JavaImportNode> imports,
            org.vernac.compiler.ast.SourceLocation location,
            List<org.vernac.compiler.analyzer.CompilerDiagnostic> errors) {
        String message =
                !validName(name)
                        ? "Invalid Java implementation class name: " + name
                        : !name.contains(".")
                                        && imports.stream()
                                                        .map(JavaImportNode::target)
                                                        .filter(t -> t.endsWith("." + name))
                                                        .distinct()
                                                        .count()
                                                > 1
                                ? "Ambiguous Java implementation name: "
                                        + name
                                        + ". Use a fully qualified name or fix the Java imports."
                                : null;
        if (message == null) return true;
        errors.add(org.vernac.compiler.analyzer.CompilerDiagnostic.error(location, message));
        return false;
    }

    public static String resolve(
            String name, List<JavaImportNode> imports, String generatedPackage) {
        if (name.contains(".")) return name;
        var matches =
                imports.stream()
                        .map(JavaImportNode::target)
                        .filter(target -> target.endsWith("." + name))
                        .distinct()
                        .toList();
        if (matches.size() > 1)
            throw new IllegalArgumentException("Ambiguous Java implementation name: " + name);
        return matches.isEmpty() ? generatedPackage + "." + name : matches.getFirst();
    }
}
