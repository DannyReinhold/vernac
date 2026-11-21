// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
/** Application-owned role key and its explicit permission union; not a domain type. */
public record RoleNode(SourceLocation location, String key, List<AuthorizationNode.Permission> permissions) implements TopLevelDefinition {
    public RoleNode { permissions = List.copyOf(permissions); }
}
