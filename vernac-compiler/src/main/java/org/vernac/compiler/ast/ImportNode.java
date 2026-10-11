// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.Objects;

/** A file-local Vernac import, including the location needed for diagnostics. */
public record ImportNode(SourceLocation location, String target, boolean wildcard) implements AstNode {
    public ImportNode {
        Objects.requireNonNull(location);
        Objects.requireNonNull(target);
    }

    public String text() {
        return target + (wildcard ? ".*" : "");
    }

    public String namespace() {
        int separator = target.lastIndexOf('.');
        return wildcard ? target : separator < 0 ? "" : target.substring(0, separator);
    }
}
