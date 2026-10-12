// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;

/** HTTP transport boundary for explicitly injected application usecases. */
public record InboundHttpAdapterNode(SourceLocation location, String name, String base,
        List<FieldNode> dependencies, List<Endpoint> endpoints, List<SchemaNode> schemas,
        List<MappingNode> mappings, Optional<String> errorImplementation) implements TopLevelDefinition {
    public record Endpoint(SourceLocation location, String name, String verb, String route,
            List<Binding> bindings, Optional<TypeNode> bodyType, String dependency,
            List<MappingAssignmentNode> arguments, int status, Optional<TypeNode> responseType,
            Optional<String> responseMapping, Optional<Integer> absentStatus) { }
    public record Binding(SourceLocation location, String kind, TypeNode type, String name, String externalName) { }
}
