// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.rest;

import java.util.*;
import java.util.regex.Pattern;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.dto.ResolvedSchema;
import org.vernac.compiler.mapping.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;
import static org.vernac.compiler.rest.ResolvedRestOperation.*;

/** Shared mapping type model also defines HTTP binding types and response conversions. */
public final class RestResolver {
    private final List<CompilerDiagnostic> errors;
    private final MappingResolver values;
    private final VernacProject project;
    private final Map<String, ResolvedMapping> mappings = new LinkedHashMap<>();
    private static final Pattern SLOT = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "content-length", "connection", "expect", "upgrade");

    public RestResolver(VernacProject project, Map<TypeNode, ResolvedType> types, List<CompilerDiagnostic> errors,
            OutboundAdapterNode adapter, String namespace, FileTypeScope scope, List<ResolvedSchema> schemas,
            List<ResolvedMapping> mappings) {
        this.errors = errors;
        this.project = project;
        this.values = new MappingResolver(project, types, errors).context(adapter, namespace, scope, schemas);
        mappings.forEach(m -> this.mappings.put(m.source().name(), m));
    }

    public Map<MethodNode, ResolvedRestOperation> resolve(RestAdapterNode rest) {
        Map<MethodNode, ResolvedRestOperation> result = new LinkedHashMap<>();
        for (var operation : rest.operations()) {
            MethodNode method = operation.method();
            String route = operation.route();
            var matcher = SLOT.matcher(route);
            Set<String> slots = new LinkedHashSet<>();
            while (matcher.find()) slots.add(matcher.group(1));
            String plain = SLOT.matcher(route).replaceAll("placeholder");
            if (plain.contains("{") || plain.contains("}") || plain.startsWith("//") || plain.contains(":")
                    || plain.contains("?") || plain.contains("#") || Arrays.asList(plain.split("/", -1)).stream().anyMatch(p -> p.equals(".") || p.equals(".."))) {
                error(method.location(), "REST route must be a relative path with well-formed placeholders, without query, fragment or dot segments.");
            }
            try { new java.net.URI(plain); }
            catch (java.net.URISyntaxException ex) { error(method.location(), "Invalid REST route URI syntax."); }
            Set<String> bound = new LinkedHashSet<>(), targets = new HashSet<>();
            List<Binding> bindings = new ArrayList<>();
            for (var binding : operation.bindings()) {
                String target = binding.target();
                String normalized = binding.kind().equals("header") ? target.toLowerCase(Locale.ROOT) : target;
                if (!targets.add(binding.kind() + ":" + normalized)) error(binding.location(), "Duplicate REST " + binding.kind() + " binding: " + target);
                if (target.isEmpty()) error(binding.location(), "REST binding names must not be empty.");
                if (binding.kind().equals("path")) {
                    bound.add(target);
                    if (!slots.contains(target)) error(binding.location(), "Path binding has no matching placeholder: " + target);
                }
                if (binding.kind().equals("header") && (!target.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                        || RESTRICTED_HEADERS.contains(normalized))) error(binding.location(), "Invalid or transport-managed HTTP header: " + target);
                if (binding.kind().equals("header") && normalized.equals("content-type") && operation.body().isPresent())
                    error(binding.location(), "JSON request Content-Type is supplied automatically.");
                Input input = input(method, binding.path(), binding.location());
                if (input != null) {
                    input = scalar(input, binding.location());
                    if (input != null) {
                        if (binding.kind().equals("path") && input.optional()) error(binding.location(), "Path bindings require a present value; optional values are allowed only in query/header bindings.");
                        bindings.add(new Binding(binding, input));
                    }
                }
            }
            for (String slot : slots) if (!bound.contains(slot)) error(method.location(), "Missing REST path binding: " + slot);
            Body body = null;
            if (operation.body().isPresent()) {
                var syntax = operation.body().get();
                var input = input(method, syntax.path(), syntax.location());
                var mapping = mapping(syntax.mapping(), syntax.location());
                if (operation.verb().equals("GET")) error(syntax.location(), "GET request bodies are not supported; use query bindings.");
                if (input != null && mapping != null) {
                    if (input.optional()) error(syntax.location(), "REST request bodies require a present source value.");
                    if (!same(input.result(), mapping.input()) || mapping.target().kind() != ResolvedMapping.Kind.DTO)
                        error(syntax.location(), "Request mapping must accept the selected source type and produce an adapter-local DTO.");
                    body = new Body(input, mapping);
                }
            }
            Response response = null;
            boolean isVoid = method.returnType().name().equals("void");
            if (operation.response().isPresent()) {
                var syntax = operation.response().get();
                var dto = values.resolveType(syntax.type());
                var mapping = mapping(syntax.mapping(), syntax.type().location());
                if (isVoid) error(method.location(), "Void REST operations must not declare a response mapping.");
                else if (dto != null && mapping != null) {
                    var expected = values.resolveType(required(method.returnType()));
                    if (dto.kind() != ResolvedMapping.Kind.DTO || !same(dto, mapping.input()) || expected == null || !same(expected, mapping.target()))
                        error(syntax.type().location(), "Response mapping must accept the selected DTO and return the port result type.");
                    response = new Response(dto, mapping);
                }
            } else if (!isVoid) error(method.location(), "REST value results require response <Schema> using <mapping>.");
            if (operation.absentStatus().isPresent()) {
                if (!operation.absentStatus().get().equals("404")) error(method.location(), "Only on 404 absent is supported.");
                if (!method.returnType().isOptional()) error(method.location(), "on 404 absent requires an optional port result.");
            }
            operation.errorImplementation().ifPresent(name -> {
                if (!name.contains(".") || Arrays.stream(name.split("\\.")).anyMatch(n -> !VernacNames.isIdentifier(n)))
                    error(method.location(), "errors implemented by requires a fully qualified Java class name.");
            });
            result.put(method, new ResolvedRestOperation(operation, bindings, body, response, resolveErrors(operation)));
        }
        return result;
    }

    private Errors resolveErrors(RestAdapterNode.Operation op) {
        if (op.errors().isEmpty()) return null;
        var syntax = op.errors().get();
        var dto = syntax.response().map(values::resolveType).orElse(null);
        if (dto != null && (dto.kind() != ResolvedMapping.Kind.DTO || syntax.response().orElseThrow().isOptional())) {
            error(syntax.response().orElseThrow().location(), "Error response requires a required adapter-local schema.");
            dto = null;
        }
        List<Rule> rules = new ArrayList<>();
        for (var rule : syntax.rules()) {
            var condition = condition(rule.condition(), dto);
            var action = rule.action();
            ResolvedMapping.Type codeType = null; String code = action.code();
            if (action.kind().equals("reject")) {
                int dot = code.lastIndexOf('.');
                if (dot < 1) error(action.location(), "Business rejection requires a declared Vernac enum constant: Type.CODE.");
                else {
                    codeType = values.resolveType(new TypeNode(action.location(), code.substring(0, dot)));
                    code = code.substring(dot + 1);
                    if (codeType != null) {
                        if (codeType.symbol() == null || codeType.symbol().kind() != TypeSymbol.Kind.ENUM)
                            error(action.location(), "Business rejection code must belong to a Vernac enum.");
                        else {
                            var id = codeType.symbol().identity();
                            String constant = code;
                            boolean exists = project.sources().stream().filter(f -> f.unit().namespace().equals(id.namespace()))
                                .flatMap(f -> f.unit().definitions().stream())
                                .filter(n -> n instanceof ValueObjectNode v && v.name().equals(id.name()))
                                .map(n -> (ValueObjectNode)n).flatMap(v -> v.enumConstants().stream())
                                .anyMatch(c -> c.name().equals(constant));
                            if (!exists) error(action.location(), "Unknown business rejection constant: " + action.code());
                        }
                    }
                }
            } else {
                try { org.vernac.runtime.ports.IntegrationCategory.valueOf(code); }
                catch (IllegalArgumentException ex) { error(action.location(), "Unknown integration failure category: " + code); }
            }
            if (condition != null) {
                if (op.absentStatus().isPresent() && couldMatch404(condition))
                    error(rule.location(), "Error rule can match 404 but on 404 absent handles it first. Exclude 404 explicitly or remove the shortcut.");
                rules.add(new Rule(condition, action, codeType, code));
            }
        }
        return new Errors(dto, rules);
    }
    private Condition condition(RestAdapterNode.Condition node, ResolvedMapping.Type dto) {
        if (node instanceof RestAdapterNode.Logical l) {
            var children = l.operands().stream().map(c -> condition(c, dto)).filter(Objects::nonNull).toList();
            return new Logical(l.operator(), children);
        }
        if (node instanceof RestAdapterNode.Negation n) {
            var c = condition(n.operand(), dto); return c == null ? null : new Negation(c);
        }
        var p = (RestAdapterNode.Predicate)node;
        if (p.path().equals(List.of("status"))) {
            try {
                int status = Integer.parseInt(p.literal());
                if (!p.literalKind().equals("number") || status < 100 || status > 599) throw new NumberFormatException();
            } catch (NumberFormatException ex) { error(p.location(), "HTTP status comparison requires an integer from 100 to 599."); }
            return new Predicate(p, List.of(), "status");
        }
        if (!p.path().getFirst().equals("response") || p.path().size() < 2) {
            error(p.location(), "Error conditions require status or response.field paths."); return null;
        }
        if (dto == null) { error(p.location(), "Response predicates require response <Schema> in the errors block."); return null; }
        var current = dto; List<ResolvedMapping.Field> fields = new ArrayList<>();
        for (String segment : p.path().subList(1, p.path().size())) {
            var field = values.fields(current).stream().filter(f -> f.name().equals(segment)).findFirst().orElse(null);
            if (field == null || field.patch()) { error(p.location(), "Unknown or unsupported error response field: " + segment); return null; }
            fields.add(field); current = field.type();
        }
        if (p.literalKind().equals("presence")) return new Predicate(p, fields, "presence");
        String scalar = current.identity();
        String kind = scalar.equals("java.lang.String") ? "string" : scalar.equals("java.lang.Boolean") ? "boolean"
            : Set.of("java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.math.BigInteger", "java.math.BigDecimal").contains(scalar) ? "number" : "unsupported";
        if (current.kind() != ResolvedMapping.Kind.SCALAR || !kind.equals(p.literalKind()))
            error(p.location(), "Error comparison literal must match the field type; supported literals are strings, booleans and exact numbers.");
        if (!kind.equals("number") && !Set.of("=", "!=").contains(p.operator()))
            error(p.location(), "Only numeric error fields support ordered comparisons.");
        return new Predicate(p, fields, kind);
    }
    private boolean couldMatch404(Condition condition) { return possible404(condition).contains(true); }
    private Set<Boolean> possible404(Condition c) {
        if (c instanceof Predicate p) {
            if (!p.kind().equals("status")) return Set.of(true, false);
            try {
                int n = Integer.parseInt(p.source().literal());
                boolean match = switch (p.source().operator()) {
                    case "=" -> 404 == n; case "!=" -> 404 != n; case "<" -> 404 < n;
                    case "<=" -> 404 <= n; case ">" -> 404 > n; case ">=" -> 404 >= n; default -> false;
                };
                return Set.of(match);
            } catch (NumberFormatException ex) { return Set.of(true, false); }
        }
        if (c instanceof Negation n) { Set<Boolean> r = new HashSet<>(); possible404(n.operand()).forEach(v -> r.add(!v)); return r; }
        var l = (Logical)c; Set<Boolean> result = Set.of(l.operator().equals("and"));
        for (var child : l.operands()) {
            Set<Boolean> next = new HashSet<>();
            for (boolean a : result) for (boolean b : possible404(child)) next.add(l.operator().equals("and") ? a && b : a || b);
            result = next;
        }
        return result;
    }

    private Input input(MethodNode method, List<String> path, SourceLocation at) {
        if (path.isEmpty()) { error(at, "Expected an operation parameter path."); return null; }
        var root = method.parameters().stream().filter(p -> p.name().equals(path.getFirst())).findFirst().orElse(null);
        if (root == null) { error(at, "Unknown REST operation parameter: " + path.getFirst()); return null; }
        var type = values.resolveType(required(root.type()));
        if (type == null) return null;
        List<ResolvedMapping.Field> fields = new ArrayList<>();
        boolean optional = root.type().isOptional();
        for (String segment : path.subList(1, path.size())) {
            var field = values.fields(type).stream().filter(f -> f.name().equals(segment)).findFirst().orElse(null);
            if (field == null || field.patch()) { error(at, "Unknown or unsupported REST source field: " + segment); return null; }
            fields.add(field); type = field.type(); optional |= field.optional();
        }
        return new Input(root, fields, type, optional);
    }
    private Input scalar(Input input, SourceLocation at) {
        var type = input.result();
        var fields = new ArrayList<>(input.fields());
        boolean optional = input.optional();
        Set<String> seen = new HashSet<>();
        while (type.kind() == ResolvedMapping.Kind.DOMAIN && seen.add(type.identity())) {
            var members = values.fields(type);
            if (members.size() != 1) break;
            var field = members.getFirst(); fields.add(field); optional |= field.optional(); type = field.type();
        }
        if (type.kind() != ResolvedMapping.Kind.SCALAR) {
            error(at, "REST path/query/header requires a scalar or single-value wrapper. Select a scalar field explicitly; enums and collections are not implicitly serialized.");
            return null;
        }
        return new Input(input.parameter(), fields, type, optional);
    }
    private ResolvedMapping mapping(String name, SourceLocation at) {
        var mapping = mappings.get(name);
        if (mapping == null) error(at, "Unknown REST mapping: " + name);
        return mapping;
    }
    private static TypeNode required(TypeNode type) { return new TypeNode(type.location(), type.name(), type.typeArguments(), false); }
    private static boolean same(ResolvedMapping.Type a, ResolvedMapping.Type b) { return a.identity().equals(b.identity()); }
    private void error(SourceLocation at, String message) { errors.add(CompilerDiagnostic.error(at, message)); }
}
