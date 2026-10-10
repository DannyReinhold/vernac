// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;

/** A stateless domain capability with named operations. */
public record DomainServiceNode(SourceLocation location, String name,
        List<FieldNode> injections, List<JavaImportNode> javaImports,
        List<ServiceMethodNode> methods) implements TopLevelDefinition { }
