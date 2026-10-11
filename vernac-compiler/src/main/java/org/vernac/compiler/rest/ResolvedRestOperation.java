// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.rest;

import java.util.List;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.mapping.ResolvedMapping;

/** One closed, type-checked transport plan. Generators never rediscover parameter/DTO types. */
public record ResolvedRestOperation(RestAdapterNode.Operation source, List<Binding> bindings,
        Body body, Response response) {
    public ResolvedRestOperation { bindings = List.copyOf(bindings); }
    public record Input(FieldNode parameter, List<ResolvedMapping.Field> fields,
            ResolvedMapping.Type result, boolean optional) {
        public Input { fields = List.copyOf(fields); }
    }
    public record Binding(RestAdapterNode.Binding source, Input input) { }
    public record Body(Input input, ResolvedMapping mapping) { }
    public record Response(ResolvedMapping.Type dto, ResolvedMapping mapping) { }
}
