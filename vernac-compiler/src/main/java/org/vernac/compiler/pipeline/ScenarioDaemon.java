// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.tooling.VernacModelEdits;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Line-delimited JSON transport for a local scenario workspace. */
public final class ScenarioDaemon implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int RETAINED_EDITS = 32;

    private final ScenarioWorkspace shared;
    private final Supplier<ScenarioRunSession.Executor> executors;
    private final Map<String, ScenarioWorkspace> overlays = new HashMap<>();
    private final ScenarioRunJobs jobs = new ScenarioRunJobs();
    private final LinkedHashMap<EditKey, CompletedEdit> editReceipts = new LinkedHashMap<>();
    private long lastClient;

    private record EditKey(String client, String key) { }
    private record EditRequest(long revision, boolean checked, List<VersionedSources.Edit> edits) { }
    private record CompletedEdit(EditRequest request, Object result) { }

    record Reply(JsonNode id, Object result, Problem error) { }
    record Problem(String code, String message, Object details) { }

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

    private Reply handle(String line) {
        try {
            return execute(JSON.readTree(line));
        } catch (JacksonException failure) {
            return error(null, "invalid_request", failure.getMessage(), null);
        }
    }

    synchronized Reply execute(JsonNode request) {
        JsonNode id = null;
        try {
            if (request == null || !request.isObject()) throw new IllegalArgumentException("Expected a request object");
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
                jobs.requireIdle();
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
            case "apply" -> edit(request, false);
            case "applyChecked" -> edit(request, true);
            case "describeType" -> workspace(request).describeType(revision(request), requiredText(request, "type"));
            case "previewAddValueField" -> preview(revision(request), workspace(request)
                    .previewAddValueField(revision(request), Path.of(requiredText(request, "path")),
                            requiredText(request, "type"), requiredText(request, "name"),
                            requiredText(request, "fieldType"), optionalBoolean(request, "optional")));
            case "previewCreateAggregate" -> preview(revision(request), workspace(request)
                    .previewCreateAggregate(revision(request), Path.of(requiredText(request, "path")),
                            requiredText(request, "namespace"), requiredText(request, "name"),
                            requiredText(request, "idType"), optionalBoolean(request, "declareId"),
                            aggregateFields(request)));
            case "check" -> checkReply(workspace(request).check(revision(request)));
            case "run" -> {
                jobs.requireIdle();
                yield workspace(request).run(revision(request), optionalBoolean(request, "opaqueChange"));
            }
            case "runScenario" -> {
                jobs.requireIdle();
                yield workspace(request).runScenario(revision(request), requiredText(request, "scenario"));
            }
            case "startRunSaved" -> startRun(request, true);
            case "startRun" -> startRun(request, false);
            case "runStatus" -> jobs.status(requiredText(request, "runId"));
            case "cancelRun" -> jobs.cancel(requiredText(request, "runId"));
            case "close" -> {
                String client = requiredText(request, "client");
                if (overlays.remove(client) == null) throw new IllegalArgumentException("Unknown overlay: " + client);
                editReceipts.keySet().removeIf(key -> key.client().equals(client));
                yield Map.of("closed", true);
            }
            default -> throw new IllegalArgumentException("Unknown workspace operation");
        };
    }

    private Object edit(JsonNode input, boolean checked) throws IOException {
        var workspace = workspace(input);
        var request = new EditRequest(revision(input), checked, List.copyOf(edits(input)));
        String key = requestKey(input);
        var receipt = key == null ? null : new EditKey(requiredText(input, "client"), key);
        var previous = receipt == null ? null : editReceipts.get(receipt);
        if (previous != null) {
            if (!previous.request().equals(request)) {
                throw new IllegalArgumentException("Edit request key was already used for different inputs");
            }
            return previous.result();
        }
        var result = checked ? workspace.applyChecked(request.revision(), request.edits())
                : Map.of("revision", workspace.apply(request.revision(), request.edits()));
        if (receipt != null) {
            editReceipts.put(receipt, new CompletedEdit(request, result));
            if (editReceipts.size() > RETAINED_EDITS) editReceipts.pollFirstEntry();
        }
        return result;
    }

    private static String requestKey(JsonNode request) {
        String key = request.has("requestKey") ? requiredText(request, "requestKey") : null;
        if (key != null && (key.isBlank() || key.length() > 128)) {
            throw new IllegalArgumentException("Request key must contain 1–128 characters");
        }
        return key;
    }

    private ScenarioRunJobs.Start startRun(JsonNode request, boolean saved) throws IOException {
        String client = saved ? null : requiredText(request, "client");
        String key = requestKey(request);
        long expected = revision(request);
        boolean opaque = optionalBoolean(request, "opaqueChange");
        return jobs.start(new ScenarioRunJobs.Request(key, client, expected, opaque), () -> {
            var workspace = saved ? shared : workspace(request);
            long captured = saved ? shared.refreshSavedSources(expected) : expected;
            return new ScenarioRunJobs.Captured(captured, workspace.captureRun(captured, opaque));
        });
    }

    @Override
    public void close() {
        jobs.close();
    }

    private static Map<String, Object> preview(long revision, VersionedSources.Put edit) {
        return Map.of("revision", revision, "edits", List.of(Map.of("kind", "put",
                "path", edit.path().toString().replace('\\', '/'), "text", edit.text())));
    }

    private static List<VernacModelEdits.AggregateField> aggregateFields(JsonNode request) {
        var fields = request.path("fields");
        if (fields.isMissingNode()) return List.of();
        if (!fields.isArray()) throw new IllegalArgumentException("Expected a fields array");
        var result = new ArrayList<VernacModelEdits.AggregateField>();
        for (var field : fields) {
            result.add(new VernacModelEdits.AggregateField(requiredText(field, "name"),
                    requiredText(field, "type"), optionalBoolean(field, "optional"), optionalBoolean(field, "mutable")));
        }
        return result;
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
