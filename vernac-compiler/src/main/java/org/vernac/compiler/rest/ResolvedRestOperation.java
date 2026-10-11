// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.rest;

import java.util.List;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.mapping.ResolvedMapping;

/** One closed, type-checked transport plan. Generators never rediscover parameter/DTO types. */
public record ResolvedRestOperation(RestAdapterNode.Operation source, List<Binding> bindings,
        Body body, Response response, Errors errors) {
    public ResolvedRestOperation { bindings = List.copyOf(bindings); }
    public record Errors(ResolvedMapping.Type dto, List<Rule> rules) {
        public Errors { rules = List.copyOf(rules); }
    }
    public record Rule(Condition condition, RestAdapterNode.Action action, ResolvedMapping.Type codeType, String code) { }
    public sealed interface Condition permits Logical, Negation, Predicate { }
    public record Logical(String operator, List<Condition> operands) implements Condition {
        public Logical { operands = List.copyOf(operands); }
    }
    public record Negation(Condition operand) implements Condition { }
    public record Predicate(RestAdapterNode.Predicate source, List<ResolvedMapping.Field> fields, String kind) implements Condition {
        public Predicate { fields = List.copyOf(fields); }
    }
    public record Input(FieldNode parameter, List<ResolvedMapping.Field> fields,
            ResolvedMapping.Type result, boolean optional) {
        public Input { fields = List.copyOf(fields); }
    }
    public record Binding(RestAdapterNode.Binding source, Input input) { }
    public record Body(Input input, ResolvedMapping mapping) { }
    public record Response(ResolvedMapping.Type dto, ResolvedMapping mapping) { }
}
