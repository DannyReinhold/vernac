// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioRunArchiveTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;

    @Test
    void recoversSavedResultsByIdWithoutRestoringPrivateRunsOrTheBaseline() throws Exception {
        var main = Files.createDirectories(root.resolve("src/main/vernac/proof")).resolve("model.vernac");
        Files.writeString(main, "namespace proof; value Counter(int);");
        var tests = Files.createDirectories(root.resolve("src/test/vernac/proof")).resolve("scenarios.vernac");
        Files.writeString(tests, "namespace proof; suite Checks { scenario count { expect true equals true; } }");
        String saved;
        String overlay;
        JsonNode original;
        try (var daemon = ScenarioDaemonLauncher.daemon(root, "domain")) {
            Files.writeString(main, "namespace proof; value Counter(int amount);");
            saved = call(daemon, Map.of("op", "startRunSaved", "revision", 0)).at("/result/runId").asText();
            original = completed(daemon, saved);
            assertEquals(1, original.path("revision").asInt());
            assertEquals("PASSED", original.at("/result/outcomes/proof.Checks.count").asText());
            var client = call(daemon, Map.of("op", "open", "revision", 1)).at("/result/client").asText();
            overlay = call(daemon, Map.of("op", "startRun", "client", client, "revision", 0))
                    .at("/result/runId").asText();
            completed(daemon, overlay);
        }

        try (var restarted = ScenarioDaemonLauncher.daemon(root, "domain")) {
            assertEquals(original, call(restarted, Map.of("op", "runStatus", "runId", saved)).path("result"));
            assertEquals(0, call(restarted, Map.of("op", "sharedRevision")).at("/result/revision").asInt());
            assertEquals("invalid_request", call(restarted, Map.of("op", "runStatus", "runId", overlay))
                    .at("/error/code").asText());
            assertEquals("PASSED", call(restarted, Map.of("op", "runSaved", "revision", 0))
                    .at("/result/outcomes/proof.Checks.count").asText());
        }
    }

    @Test
    void distinguishesALiveOwnerFromAnInterruptedRunWithNoResult() throws Exception {
        var writer = new ScenarioRunArchive(root);
        var reader = new ScenarioRunArchive(root);
        var id = UUID.randomUUID().toString();
        var running = new ScenarioRunJobs.Status(id, 7, ScenarioRunJobs.State.RUNNING, null, null);
        try (var lease = writer.begin(running)) {
            assertEquals(running, reader.lookup(id));
        }
        var interrupted = reader.lookup(id);
        assertEquals(ScenarioRunJobs.State.INTERRUPTED, interrupted.state());
        assertEquals(7, interrupted.revision());
        assertNull(interrupted.result());
        assertEquals("run_interrupted", interrupted.error().code());
        assertEquals(interrupted, new ScenarioRunArchive(root).lookup(id));
    }

    private static JsonNode completed(ScenarioDaemon daemon, String runId) throws Exception {
        assertFalse(runId.isEmpty());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode reply;
        do {
            reply = call(daemon, Map.of("op", "runStatus", "runId", runId));
            if (reply.at("/result/state").asText().equals("COMPLETED")) return reply.path("result");
            assertEquals("RUNNING", reply.at("/result/state").asText(), reply.toString());
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(reply);
    }

    private static JsonNode call(ScenarioDaemon daemon, Map<String, ?> request) {
        ObjectNode message = JSON.valueToTree(request);
        message.put("id", 1);
        return JSON.valueToTree(daemon.execute(message));
    }
}
