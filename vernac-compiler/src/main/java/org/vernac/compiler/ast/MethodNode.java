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
        String bodyCode,
        java.util.Optional<String> implementation,
        Mode mode
) implements AstNode {
    public enum Mode { DEFAULT, READ, MODIFY }

    public MethodNode(SourceLocation location, String accessModifier, TypeNode returnType,
                      String name, List<FieldNode> parameters, String bodyCode, java.util.Optional<String> implementation) {
        this(location, accessModifier, returnType, name, parameters, bodyCode, implementation, Mode.DEFAULT);
    }

    public MethodNode(SourceLocation location, String accessModifier, TypeNode returnType,
                      String name, List<FieldNode> parameters, String bodyCode) {
        this(location, accessModifier, returnType, name, parameters, bodyCode, java.util.Optional.empty());
    }
}