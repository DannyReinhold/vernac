// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
public record ListenerNode(SourceLocation location, String name, boolean deferred,
        TypeNode eventType, UseCaseNode operation) implements TopLevelDefinition {
    public String listenerName() { return name; }
}
