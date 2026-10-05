// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record CollectionDefinitionNode(
        SourceLocation location,
        Optional<String> customName,
        List<MethodNode> customMethods,
        Optional<String> customPackage
) implements AstNode {
}