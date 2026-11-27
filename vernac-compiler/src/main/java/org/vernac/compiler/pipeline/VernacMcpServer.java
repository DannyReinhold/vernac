// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


/** A stdio MCP session over one isolated Vernac daemon. */
final class VernacMcpServer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOLS = Set.of(PROTOCOL, "2025-06-18");
    private static final Map<String, String> TEXT = Map.of("type", "string");
    private static final Map<String, Object> REVISION = Map.of("type", "integer", "minimum", 0);
    private static final Map<String, String> BOOLEAN = Map.of("type", "boolean");
    private static final Map<String, Object> EDITS = Map.of("type", "array", "items",
            Map.of("oneOf", List.of(
                    schema(Map.of("kind", constant("put"), "path", TEXT, "text", TEXT), "kind", "path", "text"),
                    schema(Map.of("kind", constant("delete"), "path", TEXT), "kind", "path"),
                    schema(Map.of("kind", constant("move"), "from", TEXT, "to", TEXT), "kind", "from", "to"))));
    private static final Map<String, Object> FIELDS = Map.of("type", "array", "items",
            schema(Map.of("name", TEXT, "type", TEXT, "optional", BOOLEAN, "mutable", BOOLEAN), "name", "type"));

    private record Tool(String name, String operation, String description, Map<String, Object> inputSchema) {
        Map<String, ?> advertised() {
            return Map.of("name", name, "description", description, "inputSchema", inputSchema);
        }
    }

    private static final Map<String, Tool> TOOLS = List.of(
            tool("workspace_revision", "sharedRevision", "Read the current saved workspace revision.", Map.of()),
            tool("check_saved", "checkSaved", "Refresh saved sources and check them against the expected revision.",
                    Map.of("revision", REVISION), "revision"),
            tool("test_affected_saved", "runSaved", "Refresh saved sources and run the affected scenarios.",
                    Map.of("revision", REVISION, "opaqueChange", BOOLEAN), "revision"),
            tool("open_overlay", "open", "Open a private editor overlay from the saved workspace revision.",
                    Map.of("revision", REVISION), "revision"),
            tool("close_overlay", "close", "Release a private overlay.", Map.of("client", TEXT), "client"),
            overlay("apply_edits", "apply", "Apply one atomic source edit batch to an overlay.",
                    Map.of("edits", EDITS), "edits"),
            overlay("apply_checked", "applyChecked", "Publish an edit batch only after it passes validation.",
                    Map.of("edits", EDITS), "edits"),
            overlay("check_overlay", "check", "Check one overlay revision.", Map.of()),
            overlay("test_affected_overlay", "run", "Run affected scenarios at one overlay revision.",
                    Map.of("opaqueChange", BOOLEAN)),
            overlay("test_scenario", "runScenario", "Run one named scenario at an overlay revision.",
                    Map.of("scenario", TEXT), "scenario"),
            overlay("describe_type", "describeType", "Read fields and declared methods for a qualified Vernac type.",
                    Map.of("type", TEXT), "type"),
            overlay("preview_add_value_field", "previewAddValueField", "Preview adding a field to a value object.",
                    Map.of("path", TEXT, "type", TEXT, "name", TEXT, "fieldType", TEXT, "optional", BOOLEAN),
                    "path", "type", "name", "fieldType"),
            overlay("preview_create_aggregate", "previewCreateAggregate", "Preview creating an aggregate declaration.",
                    Map.of("path", TEXT, "namespace", TEXT, "name", TEXT, "idType", TEXT, "declareId", BOOLEAN, "fields", FIELDS),
                    "path", "namespace", "name", "idType"),
            tool("search_docs", "searchDocs", "Find bundled documentation sections and their readable resource URIs.",
                    Map.of("query", TEXT), "query"))
            .stream().collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));

    private final ScenarioDaemon daemon;
    private final VernacMcpDocumentation docs;
    private boolean negotiated;
    private boolean initialized;

    VernacMcpServer(ScenarioDaemon daemon) throws IOException {
        this.daemon = daemon;
        docs = new VernacMcpDocumentation();
    }

    void serve(Reader input, Writer output) throws IOException {
        var reader = new BufferedReader(input);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            var answer = handle(line);
            if (answer != null) {
                output.write(JSON.writeValueAsString(answer));
                output.write('\n');
                output.flush();
            }
        }
    }

    private Map<String, ?> handle(String line) {
        JsonNode message;
        try {
            message = JSON.readTree(line);
        } catch (JacksonException failure) {
            return error(NullNode.getInstance(), -32700, "Parse error");
        }
        if (message == null || !message.isObject() || !message.path("jsonrpc").asText().equals("2.0")
                || !message.path("method").isString()) {
            return error(NullNode.getInstance(), -32600, "Invalid JSON-RPC request");
        }
        String method = message.path("method").asText();
        JsonNode id = message.get("id");
        if (id == null) {
            if (negotiated && method.equals("notifications/initialized")) initialized = true;
            return null;
        }
        if (!(id.isString() || id.isIntegralNumber())) {
            return error(NullNode.getInstance(), -32600, "Expected a string or integer request ID");
        }
        var params = message.path("params");
        if (!params.isMissingNode() && !params.isObject()) return error(id, -32602, "Expected object params");
        try {
            if (method.equals("initialize")) return success(id, initialize(params));
            if (method.equals("ping")) return success(id, Map.of());
            if (!initialized) return error(id, -32000, "MCP session is not initialized");
            return switch (method) {
                case "tools/list" -> success(id, Map.of("tools", TOOLS.values().stream()
                        .sorted(Comparator.comparing(Tool::name)).map(Tool::advertised).toList()));
                case "tools/call" -> success(id, call(params, id));
                case "resources/list" -> success(id, docs.list());
                case "resources/templates/list" -> success(id, Map.of("resourceTemplates", List.of()));
                case "resources/read" -> {
                    var resource = docs.resource(requiredText(params, "uri"));
                    yield resource == null ? error(id, -32002, "Unknown Vernac documentation URI") : success(id, resource);
                }
                default -> error(id, -32601, "Method not found");
            };
        } catch (IllegalArgumentException failure) {
            return error(id, -32602, failure.getMessage());
        }
    }

    private Map<String, ?> initialize(JsonNode params) {
        if (negotiated) throw new IllegalArgumentException("MCP session is already initialized");
        String version = requiredText(params, "protocolVersion");
        negotiated = true;
        return Map.of("protocolVersion", PROTOCOLS.contains(version) ? version : PROTOCOL,
                "serverInfo", Map.of("name", "vernac", "version", docs.version()),
                "capabilities", Map.of("tools", Map.of(), "resources", Map.of()),
                "instructions", "Use saved revisions or private overlays for edits and tests. Read bundled language help with resources or search_docs.");
    }

    private Map<String, ?> call(JsonNode params, JsonNode id) {
        String name = requiredText(params, "name");
        var tool = TOOLS.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown Vernac tool: " + name);
        var input = params.path("arguments");
        if (!input.isMissingNode() && !input.isObject()) {
            throw new IllegalArgumentException("Expected object arguments for " + name);
        }
        ObjectNode request = input.isMissingNode() ? JSON.createObjectNode() : (ObjectNode) input.deepCopy();
        var fields = (Map<?, ?>) tool.inputSchema().get("properties");
        for (String field : request.propertyNames()) {
            if (!fields.containsKey(field)) {
                return toolResult(Map.of("code", "invalid_request", "message", "Unknown argument: " + field), true);
            }
        }
        if (tool.operation().equals("searchDocs")) {
            try {
                return toolResult(docs.search(requiredText(input, "query")), false);
            } catch (IllegalArgumentException failure) {
                return toolResult(Map.of("code", "invalid_request", "message", failure.getMessage()), true);
            }
        }
        request.set("id", id);
        request.put("op", tool.operation());
        var reply = daemon.execute(request);
        return reply.error() == null ? toolResult(reply.result(), false) : toolResult(reply.error(), true);
    }

    private static Map<String, ?> toolResult(Object result, boolean failure) {
        return Map.of("content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(result))),
                "structuredContent", result, "isError", failure);
    }

    private static Tool tool(String name, String operation, String description, Map<String, ?> properties,
                             String... required) {
        return new Tool(name, operation, description, schema(properties, required));
    }

    private static Tool overlay(String name, String operation, String description, Map<String, ?> properties,
                                String... required) {
        var fields = new HashMap<String, Object>(properties);
        fields.putAll(Map.of("client", TEXT, "revision", REVISION));
        var requiredFields = new ArrayList<>(List.of("client", "revision"));
        requiredFields.addAll(List.of(required));
        return tool(name, operation, description, fields, requiredFields.toArray(String[]::new));
    }

    private static Map<String, Object> schema(Map<String, ?> fields, String... required) {
        return Map.of("type", "object", "properties", Map.copyOf(fields), "required", List.of(required),
                "additionalProperties", false);
    }

    private static Map<String, ?> constant(String value) {
        return Map.of("type", "string", "const", value);
    }

    private static String requiredText(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isString()) throw new IllegalArgumentException("Expected a string field: " + field);
        return value.asText();
    }

    private static Map<String, ?> success(JsonNode id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private static Map<String, ?> error(JsonNode id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", code, "message", message));
    }
}
