// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

/** Explicit HTTP transport bindings; domain signatures remain ordinary port methods. */
public record RestAdapterNode(SourceLocation location, String client, String base, List<Operation> operations) {
    public RestAdapterNode { operations = List.copyOf(operations); }
    public record Operation(MethodNode method, String verb, String route, List<Binding> bindings,
            Optional<Body> body, Optional<Response> response, Optional<String> absentStatus,
            Optional<String> errorImplementation) {
        public Operation { bindings = List.copyOf(bindings); }
    }
    public record Binding(SourceLocation location, String kind, String target, List<String> path) {
        public Binding { path = List.copyOf(path); }
    }
    public record Body(SourceLocation location, List<String> path, String mapping) {
        public Body { path = List.copyOf(path); }
    }
    public record Response(TypeNode type, String mapping) { }
}
