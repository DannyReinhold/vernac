// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.ast.CompilationUnitNode;
import java.nio.file.Path;
import java.util.Objects;

/** A parsed source and its filesystem origin. */
public record VernacSourceFile(Path path, CompilationUnitNode unit) {
    public VernacSourceFile {
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        Objects.requireNonNull(unit);
    }
}
