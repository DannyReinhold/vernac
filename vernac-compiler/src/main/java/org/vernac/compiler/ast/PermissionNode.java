// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;

/** Named authorization keys, distinct from persisted domain enums. */
public record PermissionNode(SourceLocation location, String name, List<EnumConstantNode> members) implements TopLevelDefinition {
    public PermissionNode { members = List.copyOf(members); }
}
