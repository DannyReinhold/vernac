package org.vernac.compiler.ast;

import java.util.List;

public record AggregateNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields,
        List<InvariantNode> invariants,
        List<EntityNode> entities,
        List<MethodNode> methods
) implements TopLevelDefinition {}