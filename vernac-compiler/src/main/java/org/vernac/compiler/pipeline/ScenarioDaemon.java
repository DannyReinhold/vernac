// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.SemanticValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Line-delimited JSON transport for a local scenario workspace. */
public final class ScenarioDaemon {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ScenarioWorkspace shared;
    private final Supplier<ScenarioRunSession.Executor> executors;
    private final Map<String, ScenarioWorkspace> overlays = new HashMap<>();
    private long lastClient;

    private record Reply(JsonNode id, Object result, Problem error) { }
    private record Problem(String code, String message, Object details) { }

    public ScenarioDaemon(ScenarioWorkspace shared, Supplier<ScenarioRunSession.Executor> executors) {
        this.shared = Objects.requireNonNull(shared);
        this.executors = Objects.requireNonNull(executors);
    }

    /** Serves one stream until EOF. The caller owns the streams and provides a dedicated protocol writer. */
    public void serve(Reader input, Writer output) throws IOException {
        var reader = new BufferedReader(input);
        String line;
        while ((line = reader.readLine()) != null) {
            output.write(JSON.writeValueAsString(handle(line)));
            output.write('\n');
            output.flush();
        }
    }

    private synchronized Reply handle(String line) {
        JsonNode id = null;
        try {
            var request = JSON.readTree(line);
            if (!request.isObject()) throw new IllegalArgumentException("Expected a request object");
            id = request.get("id");
            if (id == null || !(id.isString() || id.isIntegralNumber()))
                throw new IllegalArgumentException("Expected a string or integer request ID");
            return new Reply(id, dispatch(request), null);
        } catch (VersionedSources.Conflict conflict) {
            return error(id, "stale_revision", conflict.getMessage(), Map.of("currentRevision", conflict.currentRevision()));
        } catch (SemanticValidationException failure) {
            return error(id, "diagnostics", "Workspace check failed", failure.diagnostics());
        } catch (IllegalArgumentException | JacksonException failure) {
            return error(id, "invalid_request", failure.getMessage(), null);
        } catch (IOException | IllegalStateException failure) {
            return error(id, "execution_error", failure.getMessage(), null);
        }
    }

    private Object dispatch(JsonNode request) throws IOException {
        return switch (requiredText(request, "op")) {
            case "hello" -> Map.of("protocolVersion", 1, "revision", shared.revision());
            case "sharedRevision" -> Map.of("revision", shared.revision());
            case "refresh" -> Map.of("revision", shared.refreshSavedSources(revision(request)));
            case "checkSaved" -> checkReply(shared.check(shared.refreshSavedSources(revision(request))));
            case "runSaved" -> {
                boolean opaqueChange = optionalBoolean(request, "opaqueChange");
                long savedRevision = shared.refreshSavedSources(revision(request));
                yield shared.run(savedRevision, opaqueChange);
            }
            case "open" -> {
                var overlay = shared.openOverlay(revision(request), executors.get());
                String client = Long.toString(Math.incrementExact(lastClient));
                lastClient++;
                overlays.put(client, overlay.workspace());
                yield Map.of("client", client, "baseRevision", overlay.baseRevision(),
                        "revision", overlay.workspace().revision());
            }
            case "apply" -> Map.of("revision", workspace(request).apply(revision(request), edits(request)));
            case "check" -> checkReply(workspace(request).check(revision(request)));
            case "run" -> workspace(request).run(revision(request), optionalBoolean(request, "opaqueChange"));
            case "runScenario" -> workspace(request).runScenario(revision(request), requiredText(request, "scenario"));
            case "close" -> {
                String client = requiredText(request, "client");
                if (overlays.remove(client) == null) throw new IllegalArgumentException("Unknown overlay: " + client);
                yield Map.of("closed", true);
            }
            default -> throw new IllegalArgumentException("Unknown workspace operation");
        };
    }

    private static Map<String, Object> checkReply(ScenarioPlanningSession.Diagnostics check) {
        return Map.of("revision", check.revision(), "valid", check.valid(), "diagnostics", check.diagnostics());
    }

    private ScenarioWorkspace workspace(JsonNode request) {
        String client = requiredText(request, "client");
        var workspace = overlays.get(client);
        if (workspace == null) throw new IllegalArgumentException("Unknown overlay: " + client);
        return workspace;
    }

    private static java.util.List<VersionedSources.Edit> edits(JsonNode request) {
        var array = request.path("edits");
        if (!array.isArray()) throw new IllegalArgumentException("Expected an edits array");
        var edits = new ArrayList<VersionedSources.Edit>();
        for (var edit : array) {
            edits.add(switch (requiredText(edit, "kind")) {
                case "put" -> new VersionedSources.Put(Path.of(requiredText(edit, "path")), requiredText(edit, "text"));
                case "delete" -> new VersionedSources.Delete(Path.of(requiredText(edit, "path")));
                case "move" -> new VersionedSources.Move(Path.of(requiredText(edit, "from")), Path.of(requiredText(edit, "to")));
                default -> throw new IllegalArgumentException("Unknown edit kind");
            });
        }
        return edits;
    }

    private static long revision(JsonNode request) {
        var revision = request.path("revision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0)
            throw new IllegalArgumentException("Expected a nonnegative revision");
        return revision.asLong();
    }

    private static String requiredText(JsonNode request, String key) {
        var field = request.path(key);
        if (!field.isString()) throw new IllegalArgumentException("Expected a string field: " + key);
        return field.asText();
    }

    private static boolean optionalBoolean(JsonNode request, String key) {
        var field = request.path(key);
        if (field.isMissingNode()) return false;
        if (!field.isBoolean()) throw new IllegalArgumentException("Expected a boolean field: " + key);
        return field.asBoolean();
    }

    private static Reply error(JsonNode id, String code, String message, Object details) {
        return new Reply(id, null, new Problem(code, message, details));
    }
}
