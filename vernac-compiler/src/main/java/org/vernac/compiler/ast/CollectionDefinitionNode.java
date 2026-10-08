// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;

public record CollectionDefinitionNode(SourceLocation location, Kind kind,
        Optional<String> customName, List<MethodNode> customMethods) implements AstNode {
    public enum Kind { LIST, SET }
    public String nameFor(String elementName) { return customName.orElse(elementName + "s"); }
}
