// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public record RawJavaStatementNode(
        SourceLocation location,
        String javaCode
) implements UseCaseStatementNode {
}