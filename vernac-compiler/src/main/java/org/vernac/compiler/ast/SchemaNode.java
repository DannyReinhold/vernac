// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record SchemaNode(
        String name,
        List<FieldNode> fields,
        SourceLocation location
) implements AstNode {
}