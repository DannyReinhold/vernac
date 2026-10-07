// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

/** A domain constant, without any external representation or persistence code. */
public record EnumConstantNode(SourceLocation location, String name) implements AstNode { }
