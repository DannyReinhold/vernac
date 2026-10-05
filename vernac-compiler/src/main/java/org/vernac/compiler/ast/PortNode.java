// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record PortNode(
        String name,
        Optional<String> customPackage, // Optional, falls wir später package overrides erlauben
        List<SchemaNode> schemas,
        List<PortMethodNode> methods,
        SourceLocation location
) implements TopLevelDefinition {
}