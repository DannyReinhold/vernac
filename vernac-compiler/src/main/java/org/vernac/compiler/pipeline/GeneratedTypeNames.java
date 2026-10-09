// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.ast.SourceLocation;
import java.util.*;

/** Last compiler boundary before generated files are handed to any writer. */
final class GeneratedTypeNames {
    static void check(List<JavaFile> files) {
        Set<String> names = new HashSet<>();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (var file : files) {
            String name = file.packageName() + "." + file.typeSpec().name();
            if (!names.add(name)) diagnostics.add(CompilerDiagnostic.error(SourceLocation.UNKNOWN,
                    "Generated Java type collision: '" + name + "'. Two outputs would overwrite the same file."));
        }
        if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
    }
}
