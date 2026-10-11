// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.Optional;
public record SchemaFieldNode(SourceLocation location, TypeNode type, String name,
        boolean patch, boolean absentAsNull, Optional<String> externalName) implements AstNode { }
