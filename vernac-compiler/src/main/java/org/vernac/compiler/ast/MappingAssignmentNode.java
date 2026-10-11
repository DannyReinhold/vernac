// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;
public record MappingAssignmentNode(SourceLocation location,String target,List<String> path,
        Optional<String> mapping) implements AstNode {
    public MappingAssignmentNode { path=List.copyOf(path); }
}
