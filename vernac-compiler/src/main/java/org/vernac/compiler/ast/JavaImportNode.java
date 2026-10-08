// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

/** Explicit Java type import, scoped to one behavior implementation. */
public record JavaImportNode(SourceLocation location, String target) implements AstNode {}
