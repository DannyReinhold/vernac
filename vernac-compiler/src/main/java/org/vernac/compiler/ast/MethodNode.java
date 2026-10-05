// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record MethodNode(
        SourceLocation location,
        String accessModifier,
        TypeNode returnType,
        String name,
        List<FieldNode> parameters,
        String bodyCode
) implements AstNode {}