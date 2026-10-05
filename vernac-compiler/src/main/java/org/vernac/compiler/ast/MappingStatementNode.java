// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public record MappingStatementNode(
        String sourcePath,
        String targetPath,
        String direction, // "->" oder "<-"
        SourceLocation location
) implements AstNode {
}