// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.analyzer;

import org.vernac.compiler.ast.SourceLocation;

public record CompilerDiagnostic(
        SourceLocation location,
        String message,
        Severity severity
) {
    public static CompilerDiagnostic error(SourceLocation location, String message) {
        return new CompilerDiagnostic(location, message, Severity.ERROR);
    }

    public static CompilerDiagnostic warning(SourceLocation location, String message) {
        return new CompilerDiagnostic(location, message, Severity.WARNING);
    }

    @Override
    public String toString() {
        return "[" + severity + "] at line " + location.line() + ":" + location.column() + " - " + message;
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}