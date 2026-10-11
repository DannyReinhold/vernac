// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;
/** A pure adapter-local conversion. Exactly one implementation form is present. */
public record MappingNode(SourceLocation location, String name, TypeNode targetType, TypeNode sourceType,
        List<MappingAssignmentNode> assignments, List<JavaImportNode> javaImports,
        Optional<String> javaBody, Optional<String> implementation) implements AstNode {
    public MappingNode { assignments=List.copyOf(assignments);javaImports=List.copyOf(javaImports); }
}
