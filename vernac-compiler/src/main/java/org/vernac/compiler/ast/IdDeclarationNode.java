// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;


public record IdDeclarationNode(
        SourceLocation location,
        String name
) implements TopLevelDefinition {
}