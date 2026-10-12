// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.inbound;
import java.util.List;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.dto.ResolvedSchema;
import org.vernac.compiler.mapping.ResolvedMapping;
import org.vernac.compiler.symbols.TypeSymbol;

/** Closed transport/application invocation plan; generation performs no type lookup. */
public record ResolvedInbound(List<ResolvedSchema> schemas, List<ResolvedMapping> mappings,
        List<Dependency> dependencies, List<Endpoint> endpoints) {
    public record Dependency(FieldNode field, TypeSymbol symbol, UseCaseNode usecase) { }
    public record Endpoint(InboundHttpAdapterNode.Endpoint source, Dependency dependency,
            List<Argument> arguments, ResolvedMapping.Type result, ResolvedMapping response) { }
    public record Argument(FieldNode target, ResolvedMapping.Path source, ResolvedMapping.Conversion conversion) { }
}
