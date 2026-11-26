// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.inbound.InboundResolver;
import org.vernac.compiler.inbound.ResolvedInbound;
import org.vernac.compiler.mapping.MappingResolver;
import org.vernac.compiler.mapping.ResolvedMapping.Field;
import org.vernac.compiler.mapping.ResolvedMapping.Kind;
import org.vernac.compiler.mapping.ResolvedMapping.Type;

import java.util.*;

/** Resolves presentation bindings and emits server-rendered templates. */
public final class WebViews {
    private final ResolvedProject project;
    private final List<CompilerDiagnostic> diagnostics;
    private final Map<String, String> templates = new LinkedHashMap<>();
    private final Map<String, WebViewNode> declared = new LinkedHashMap<>();
    private final Set<String> activeComponents = new HashSet<>();
    private int variableSequence;

    public WebViews(ResolvedProject project, List<CompilerDiagnostic> diagnostics) {
        this.project = project;
        this.diagnostics = diagnostics;
    }

    public Map<String, String> compile() {
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            for (var definition : source.unit().definitions()) {
                if (definition instanceof WebViewNode view) {
                    var key = WebViewNode.template(source.unit().namespace(), view.name());
                    if (declared.putIfAbsent(key, view) != null)
                        error(view.location(), "Duplicate view or component " + view.name());
                }
            }
        }
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            String namespace = source.unit().namespace();
            for (var definition : source.unit().definitions()) {
                if (!(definition instanceof WebViewNode view)) continue;
                var scope = project.scopes().get(source.path());
                if (scope == null) continue;
                String template = WebViewNode.template(namespace, view.name());
                if (declared.get(template) != view) continue;
                var bindings = project.inboundAdapters().entrySet().stream()
                        .flatMap(adapter -> adapter.getValue().endpoints().stream()
                                .filter(endpoint -> endpoint.source().view()
                                        .map(reply -> reply.generated() && reply.template().equals(template)).orElse(false))
                                .map(endpoint -> new Binding(adapter.getKey(), adapter.getValue(), endpoint)))
                        .toList();
                var projection = bindings.stream().filter(binding -> binding.endpoint().response() != null)
                        .findFirst().orElse(null);
                var mapping = new MappingResolver(project.project(), project.types(), diagnostics);
                if (projection == null) mapping.context(view.name(), namespace, scope, List.of(), namespace);
                else mapping.context(projection.adapter().name(), namespace, scope, projection.plan().schemas(),
                        namespace + ".adapter.inbound.http.dto");
                for (var binding : bindings) {
                    var endpoint = binding.endpoint();
                    if (endpoint.result() == null || endpoint.result().kind() != Kind.RESULT) continue;
                    var usecase = endpoint.dependency().usecase();
                    var fields = new ArrayList<Field>();
                    for (var field : usecase.resultFields()) {
                        var resolved = project.types().get(field.type());
                        if (resolved == null) continue;
                        var type = mapping.domain(resolved, field.location());
                        if (type != null) fields.add(new Field(field.name(), type, field.type().isOptional(), false));
                    }
                    var result = endpoint.result();
                    mapping.register(result, fields, result.identity(), usecase.name() + ".Result");
                }
                Map<String, Type> model = new HashMap<>();
                view.model().ifPresent(field -> {
                    if (reserved(field.name())) {
                        error(field.location(), "Reserved view model name " + field.name());
                        return;
                    }
                    Type type = mapping.resolveType(field.type());
                    if (type != null) model.put(field.name(), type);
                });
                if (view.component()) {
                    var componentScope = new HashMap<String, Expression>();
                    model.forEach((name, type) -> componentScope.put(name, new Expression(name, type)));
                    activeComponents.add(template);
                    elements(new StringBuilder(), view.elements(), namespace, mapping, componentScope, false);
                    activeComponents.remove(template);
                    continue;
                }
                for (var binding : bindings) {
                    var endpoint = binding.endpoint();
                    String name = endpoint.source().view().orElseThrow().modelName().orElse("");
                    Type expected = model.get(name);
                    Type actual = endpoint.response() == null ? endpoint.result() : endpoint.response().target();
                    if (view.model().isPresent()
                            ? expected == null || actual == null || !expected.identity().equals(actual.identity())
                                || !expected.javaType().equals(actual.javaType())
                            : actual != null || !name.isEmpty()) {
                        error(endpoint.source().location(), "Render model must match the name and type declared by view " + view.name());
                    }
                }
                var html = new StringBuilder("<!doctype html>\n<html xmlns:th=\"http://www.thymeleaf.org\"><head>")
                        .append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                        .append("<title>").append(escape(view.title())).append("</title>");
                view.theme().ifPresent(theme -> {
                    if (!theme.matches("/[A-Za-z0-9/_-]+[.]css"))
                        error(view.location(), "A theme must be an application-local CSS resource.");
                    else html.append("<link rel=\"stylesheet\" th:href=\"@{").append(theme).append("}\">");
                });
                html.append("</head><body><main");
                view.layout().ifPresent(layout -> html.append(" class=\"").append(escape(layout)).append('\"'));
                html.append(">\n").append("<p role=\"status\" th:if=\"${notice != null}\" th:text=\"${notice}\"></p>\n")
                        .append("<p role=\"alert\" th:if=\"${error != null}\" th:text=\"${error.message()}\"></p>\n");
                var viewScope = new HashMap<String, Expression>();
                model.forEach((name, type) -> viewScope.put(name, new Expression(name, type)));
                elements(html, view.elements(), namespace, mapping, viewScope, false);
                templates.put(template + ".html", html.append("</main></body></html>\n").toString());
            }
        }
        for (var source : project.project().sources()) for (var definition : source.unit().definitions()) {
            if (!(definition instanceof InboundHttpAdapterNode adapter) || source.external()) continue;
            adapter.errorView().ifPresent(name -> checkErrorView(name, adapter.location()));
            for (var rule : adapter.errorRules()) rule.view().flatMap(InboundHttpAdapterNode.ErrorView::template)
                    .ifPresent(name -> checkErrorView(name, rule.location()));
            for (var endpoint : adapter.endpoints()) {
                for (var rule : endpoint.errorRules()) rule.view().flatMap(InboundHttpAdapterNode.ErrorView::template)
                        .ifPresent(name -> checkErrorView(name, rule.location()));
                endpoint.view().ifPresent(reply -> {
                    if (!reply.generated()) return;
                    var view = declared.get(reply.template());
                    if (view == null || view.component())
                        error(endpoint.location(), "Unknown Vernac page view: " + reply.template());
                    else if (endpoint.dependency().isEmpty() && view.model().isPresent())
                        error(endpoint.location(), "A template-only endpoint cannot render a view with a model.");
                });
            }
        }
        return Map.copyOf(templates);
    }

    private void checkErrorView(String name, SourceLocation location) {
        if (!name.startsWith(WebViewNode.TEMPLATE_PREFIX)) return;
        var view = declared.get(name);
        if (view == null || view.component() || view.model().isPresent())
            error(location, "An MVC error view requires a declared page without a model: " + name);
    }

    private void elements(StringBuilder html, List<WebViewNode.Element> elements, String namespace,
                          MappingResolver mapping, Map<String, Expression> model, boolean repeated) {
        for (var element : elements) {
            switch (element) {
                case WebViewNode.Text text -> {
                    String tag = text.heading() ? "h1" : "p";
                    if (text.literal().isPresent()) {
                        html.append('<').append(tag).append('>').append(escape(text.literal().get()))
                                .append("</").append(tag).append(">\n");
                    } else {
                        var value = expression(text.path().orElseThrow(), text.location(), model, mapping);
                        if (value != null && value.type().kind() != Kind.SCALAR) {
                            var fields = mapping.fields(value.type());
                            if (fields.size() == 1 && fields.getFirst().type().kind() == Kind.SCALAR
                                    && !fields.getFirst().optional())
                                value = new Expression(value.java() + "." + fields.getFirst().name() + "()", fields.getFirst().type());
                            else {
                                error(text.location(), "Text requires a scalar or a single-scalar value.");
                                value = null;
                            }
                        }
                        if (value != null) html.append('<').append(tag).append(" th:text=\"${").append(value.java())
                                .append("}\"></").append(tag).append(">\n");
                    }
                }
                case WebViewNode.Repeat repeat -> {
                    var value = expression(repeat.path(), repeat.location(), model, mapping);
                    if (value == null) continue;
                    if (value.type().kind() != Kind.SEQUENCE && value.type().kind() != Kind.PAGE) {
                        error(repeat.location(), "A view list requires a value collection.");
                        continue;
                    }
                    if (model.containsKey(repeat.item()) || reserved(repeat.item())) {
                        error(repeat.location(), "Invalid or duplicate list variable " + repeat.item());
                        continue;
                    }
                    var scope = new HashMap<>(model);
                    String variable = "vernacViewItem" + ++variableSequence;
                    String collection = value.java() + (value.type().kind() == Kind.PAGE ? ".items()" : "");
                    scope.put(repeat.item(), new Expression(variable, value.type().element()));
                    html.append("<ul><li th:each=\"").append(variable).append(" : ${").append(collection)
                            .append("}\">\n");
                    elements(html, repeat.body(), namespace, mapping, scope, true);
                    html.append("</li></ul>\n");
                    if (!repeat.empty().isEmpty()) {
                        html.append("<div th:if=\"${").append(collection).append(".isEmpty()}\">\n");
                        elements(html, repeat.empty(), namespace, mapping, model, repeated);
                        html.append("</div>\n");
                    }
                }
                case WebViewNode.Include include -> {
                    var key = WebViewNode.template(namespace, include.component());
                    var component = declared.get(key);
                    var value = expression(include.argument(), include.location(), model, mapping);
                    if (component == null || !component.component()) {
                        error(include.location(), "Unknown view component " + include.component());
                    } else if (value != null && activeComponents.add(key)) {
                        var parameter = component.model().orElseThrow();
                        var type = mapping.resolveType(parameter.type());
                        if (type != null && type.javaType().equals(value.type().javaType()))
                            elements(html, component.elements(), namespace, mapping, Map.of(parameter.name(), value), repeated);
                        else if (type != null)
                            error(include.location(), "Component argument does not match " + parameter.type().name());
                        activeComponents.remove(key);
                    } else if (value != null) {
                        error(include.location(), "Cyclic view component " + include.component());
                    }
                }
                case WebViewNode.Conditional conditional -> {
                    var value = expression(conditional.path(), conditional.location(), model, mapping);
                    if (value == null) continue;
                    var symbol = value.type().symbol();
                    boolean hasConstant = symbol != null
                            && symbol.kind() == org.vernac.compiler.symbols.TypeSymbol.Kind.ENUM
                            && project.project().sources().stream()
                                    .filter(source -> source.unit().namespace().equals(symbol.identity().namespace()))
                                    .flatMap(source -> source.unit().valueObjects().stream())
                                    .filter(type -> type.name().equals(symbol.identity().name()))
                                    .flatMap(type -> type.enumConstants().stream())
                                    .anyMatch(constant -> constant.name().equals(conditional.constant()));
                    if (!hasConstant) {
                        error(conditional.location(), "A view condition requires a declared enum constant.");
                        continue;
                    }
                    html.append("<div th:if=\"${").append(value.java())
                            .append(".name() == '").append(conditional.constant()).append("'}\">\n");
                    elements(html, conditional.body(), namespace, mapping, model, repeated);
                    html.append("</div>\n");
                }
                case WebViewNode.Pagination paging -> pagination(html, paging, model, mapping);
                case WebViewNode.Link link -> {
                    if (!staticRoute(link.route())) error(link.location(), "A link requires a static application path.");
                    else html.append("<a th:href=\"@{").append(link.route()).append("}\">")
                            .append(escape(link.label())).append("</a>\n");
                }
                case WebViewNode.Form form -> form(html, form, namespace, model, mapping);
            }
        }
    }

    private void pagination(StringBuilder html, WebViewNode.Pagination paging, Map<String, Expression> model,
                            MappingResolver mapping) {
        var value = expression(paging.path(), paging.location(), model, mapping);
        if (value == null) return;
        if (value.type().kind() != Kind.PAGE || value.type().pageTransport()
                || value.type().pageKind() != TypeNode.PageKind.COUNTED_OFFSET || !staticRoute(paging.route())) {
            error(paging.location(), "Pagination requires a counted offset model and a static application path.");
            return;
        }
        String page = value.java() + ".pageNumber().orElseThrow()";
        String total = value.java() + ".totalPages()";
        html.append("<nav aria-label=\"Pages\" th:if=\"${").append(total).append(" > 1}\">")
                .append("<a rel=\"prev\" th:if=\"${").append(page).append(" > 1}\" th:href=\"@{")
                .append(paging.route()).append("(page=${").append(page).append(" - 1})}\">Previous</a> ")
                .append("<span th:text=\"${'Page ' + ").append(page).append(" + ' of ' + ").append(total)
                .append("}\"></span> ")
                .append("<a rel=\"next\" th:if=\"${").append(value.java()).append(".hasNext()}\" th:href=\"@{")
                .append(paging.route()).append("(page=${").append(page).append(" + 1})}\">Next</a></nav>\n");
    }

    private static boolean staticRoute(String route) {
        return route.matches("/[A-Za-z0-9/_-]*") && !route.contains("//");
    }

    private record Binding(InboundHttpAdapterNode adapter, ResolvedInbound plan, ResolvedInbound.Endpoint endpoint) {}

    private record Expression(String java, Type type) {}

    private Expression expression(String path, SourceLocation location, Map<String, Expression> model, MappingResolver mapping) {
        String[] parts = path.split("\\.");
        Expression binding = model.get(parts[0]);
        if (binding == null || reserved(parts[0])) {
            error(location, "Unknown view binding " + parts[0]);
            return null;
        }
        Type type = binding.type();
        var java = new StringBuilder(binding.java());
        for (int i = 1; i < parts.length; i++) {
            String name = parts[i];
            var field = mapping.fields(type).stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
            if (field == null || field.optional() || field.patch()) {
                error(location, "View binding requires a declared, non-optional field: " + path);
                return null;
            }
            java.append('.').append(name).append("()");
            type = field.type();
        }
        return new Expression(java.toString(), type);
    }

    private void form(StringBuilder html, WebViewNode.Form form, String namespace,
                      Map<String, Expression> model, MappingResolver mapping) {
        var adapter = project.project().sources().stream()
                .filter(source -> source.unit().namespace().equals(namespace))
                .flatMap(source -> source.unit().definitions().stream())
                .filter(InboundHttpAdapterNode.class::isInstance)
                .map(InboundHttpAdapterNode.class::cast)
                .filter(candidate -> candidate.name().equals(form.adapter()) && candidate.mvc())
                .findFirst().orElse(null);
        if (adapter == null) {
            error(form.location(), "A form requires a local MVC adapter: " + form.adapter());
            return;
        }
        var endpoint = adapter.endpoints().stream().filter(e -> e.name().equals(form.endpoint())).findFirst().orElse(null);
        if (endpoint == null || !endpoint.verb().equals("POST")) {
            error(form.location(), "A form requires an MVC POST endpoint: " + form.endpoint());
            return;
        }
        String route = InboundResolver.join(adapter.base(), endpoint.route());
        if (!staticRoute(route.replaceAll("\\{[A-Za-z_][A-Za-z0-9_]*}", "x"))
                || endpoint.bindings().stream().anyMatch(b -> !Set.of("path", "form").contains(b.kind())
                        && !b.type().isOptional() && b.defaultValue().isEmpty())) {
            error(form.location(), "A form action requires a local route and self-contained request bindings.");
            return;
        }
        Map<String, String> pathValues = new LinkedHashMap<>();
        for (var assignment : form.paths()) {
            var binding = endpoint.bindings().stream()
                    .filter(b -> b.kind().equals("path") && b.name().equals(assignment.target()))
                    .findFirst().orElse(null);
            if (binding == null || pathValues.containsKey(binding.externalName())) {
                error(assignment.location(), "Unknown or duplicate form path binding " + assignment.target());
                continue;
            }
            var value = expression(assignment.path().getFirst(), assignment.location(), model, mapping);
            var target = mapping.resolveType(binding.type());
            if (value == null || target == null) continue;
            if (!value.type().identity().equals(target.identity())) {
                error(assignment.location(), "Form path value must match " + binding.type().name());
                continue;
            }
            pathValues.put(binding.externalName(), binding.externalName() + "=${" + value.java() + "}");
        }
        if (endpoint.bindings().stream().anyMatch(b -> b.kind().equals("path") && !pathValues.containsKey(b.externalName()))) {
            error(form.location(), "The form must bind every route parameter.");
            return;
        }
        html.append("<form method=\"post\" th:action=\"@{").append(route);
        if (!pathValues.isEmpty()) html.append('(').append(String.join(",", pathValues.values())).append(')');
        html.append("}\">\n");
        Set<String> fields = new HashSet<>();
        for (var control : form.controls()) {
            if (control.field().isEmpty()) {
                html.append("<button type=\"submit\"");
                control.variant().ifPresent(v -> html.append(" class=\"").append(escape(v)).append("\""));
                html.append('>').append(escape(control.label())).append("</button>\n");
                continue;
            }
            String field = control.field().get();
            var binding = endpoint.bindings().stream().filter(b -> b.kind().equals("form") && b.name().equals(field)).findFirst().orElse(null);
            if (binding == null || !fields.add(field) || !binding.externalName().matches("[A-Za-z_][A-Za-z0-9_-]*")) {
                error(control.location(), "Unknown, unsupported or duplicate form field: " + field);
                continue;
            }
            String name = binding.externalName();
            html.append("<label>").append(escape(control.label())).append("<input type=\"")
                    .append(binding.sensitive() ? "password" : "text").append("\" name=\"").append(name).append('"');
            if (!binding.sensitive()) {
                html.append(" th:value=\"${inputs == null ? null : inputs['form.")
                        .append(binding.name()).append("']}\"");
            }
            if (!binding.type().isOptional() && binding.defaultValue().isEmpty()) html.append(" required");
            html.append("></label>\n");
        }
        for (var binding : endpoint.bindings()) {
            if (binding.kind().equals("form") && !binding.type().isOptional()
                    && binding.defaultValue().isEmpty() && !fields.contains(binding.name()))
                error(form.location(), "Form does not supply required field " + binding.name());
        }
        html.append("</form>\n");
    }

    private static boolean reserved(String name) {
        return name.startsWith("__") || name.startsWith("vernacViewItem") || Set.of("notice", "error", "inputs", "class").contains(name);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void error(SourceLocation location, String message) {
        diagnostics.add(CompilerDiagnostic.error(location, message));
    }
}
