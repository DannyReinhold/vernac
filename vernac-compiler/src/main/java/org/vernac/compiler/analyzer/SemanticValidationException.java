// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.analyzer;

import java.util.List;
import java.util.stream.Collectors;

public class SemanticValidationException extends RuntimeException {

    private final List<CompilerDiagnostic> diagnostics;

    public SemanticValidationException(List<CompilerDiagnostic> diagnostics) {
        super(buildMessage(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    private static String buildMessage(List<CompilerDiagnostic> diagnostics) {
        return "Vernac semantic analysis failed with " + diagnostics.size() + " issue(s):\n" +
                diagnostics.stream()
                        .map(d -> "  - " + d)
                        .collect(Collectors.joining("\n"));
    }

    public List<CompilerDiagnostic> diagnostics() {
        return diagnostics;
    }
}