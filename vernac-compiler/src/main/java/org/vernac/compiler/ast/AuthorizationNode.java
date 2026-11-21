// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;

/** Source-located ordered requirement tree; Java expressions are deliberately excluded. */
public sealed interface AuthorizationNode extends AstNode {
    record Mode(SourceLocation location, boolean authenticated) implements AuthorizationNode {}
    record Permission(SourceLocation location, TypeNode type, String member, Optional<String> resource) implements AuthorizationNode {}
    record Group(SourceLocation location, boolean all, List<AuthorizationNode> children) implements AuthorizationNode {
        public Group { children = List.copyOf(children); }
    }
}
