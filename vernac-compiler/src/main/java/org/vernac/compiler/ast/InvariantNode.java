// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public record InvariantNode(
        SourceLocation location,
        String name,
        String codeBlock
) implements AstNode {}