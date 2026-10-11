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
            Optional<String> errorImplementation, Optional<Errors> errors) {
        public Operation { bindings = List.copyOf(bindings); }
    }
    public record Errors(Optional<TypeNode> response, List<ErrorRule> rules) {
        public Errors { rules = List.copyOf(rules); }
    }
    public record ErrorRule(SourceLocation location, Condition condition, Action action) { }
    public sealed interface Condition permits Logical, Negation, Predicate { }
    public record Logical(String operator, List<Condition> operands) implements Condition {
        public Logical { operands = List.copyOf(operands); }
    }
    public record Negation(Condition operand) implements Condition { }
    public record Predicate(SourceLocation location, List<String> path, String operator,
                            String literal, String literalKind) implements Condition {
        public Predicate { path = List.copyOf(path); }
    }
    public record Action(SourceLocation location, String kind, String code, String message) { }
    public record Binding(SourceLocation location, String kind, String target, List<String> path) {
        public Binding { path = List.copyOf(path); }
    }
    public record Body(SourceLocation location, List<String> path, String mapping) {
        public Body { path = List.copyOf(path); }
    }
    public record Response(TypeNode type, String mapping) { }
}
