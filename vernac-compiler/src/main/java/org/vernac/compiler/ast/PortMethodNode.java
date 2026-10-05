// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record PortMethodNode(
        String name,
        TypeNode returnType,
        List<FieldNode> parameters,
        List<String> thrownExceptions,
        AdapterNode adapter,
        Optional<MappingBlockNode> mapping,
        SourceLocation location
) implements AstNode {
}