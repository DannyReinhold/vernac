// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioRunJobsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CASE = "proof.Checks.counter";
    @TempDir Path root;

    @Test
    void cancelledExecutionDoesNotAdvanceBaselineEvenWhenTheFixtureReturnsSuccess() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var source = createWorkspace();
        ScenarioRunSession.Executor executor = request -> {
            if (executions.incrementAndGet() == 1) {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture release timed out");
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture release timed out");
                    } catch (InterruptedException unexpected) {
                        throw new AssertionError(unexpected);
                    }
                }
            }
            return Map.of(CASE, ScenarioRunSession.Outcome.PASSED);
        };
        try (var daemon = new ScenarioDaemon(ScenarioWorkspace.openSaved(root, executor, "domain"), () -> executor)) {
            try {
                Files.writeString(source, "namespace proof; value Counter(int amount);");
                var start = call(daemon, """
                        {"op":"startRunSaved","revision":0,"requestKey":"edit-1"}
                        """);
                var id = start.at("/result/runId").asText();
                assertFalse(id.isEmpty(), start.toString());
                assertEquals(1, start.at("/result/revision").asInt());
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals(start.path("result"), call(daemon, """
                        {"op":"startRunSaved","revision":0,"requestKey":"edit-1"}
                        """).path("result"));
                assertEquals("invalid_request", call(daemon, """
                        {"op":"startRunSaved","revision":1,"requestKey":"edit-1"}
                        """).at("/error/code").asText());
                assertEquals("CANCELLING", job(daemon, "cancelRun", id).path("state").asText());
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertEquals("execution_error", call(daemon, """
                        {"op":"runSaved","revision":1}
                        """).at("/error/code").asText());
                release.countDown();
                var cancelled = await(daemon, id, state -> state.equals("CANCELLED"));
                assertTrue(cancelled.path("result").isNull());
                assertEquals(cancelled, job(daemon, "cancelRun", id));

                var rerun = call(daemon, """
                        {"op":"startRunSaved","revision":1,"requestKey":"edit-2"}
                        """);
                var completed = await(daemon, rerun.at("/result/runId").asText(), state -> state.equals("COMPLETED"));
                assertEquals("PASSED", completed.at("/result/outcomes/" + CASE).asText());
                assertEquals(2, executions.get());
                assertTrue(call(daemon, """
                        {"op":"runSaved","revision":1}
                        """).at("/result/outcomes").isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void keepsResultsForTheCapturedOverlayRevisionAfterFurtherEdits() throws Exception {
        createWorkspace();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ScenarioRunSession.Executor executor = request -> {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Fixture release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
            return Map.of(CASE, ScenarioRunSession.Outcome.PASSED);
        };
        try (var daemon = new ScenarioDaemon(ScenarioWorkspace.openSaved(root, executor, "domain"), () -> executor)) {
            try {
                assertEquals("1", call(daemon, """
                        {"op":"open","revision":0}
                        """).at("/result/client").asText());
                var run = call(daemon, """
                        {"op":"startRun","client":"1","revision":0}
                        """).at("/result/runId").asText();
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals(1, call(daemon, """
                        {"op":"apply","client":"1","revision":0,"edits":[{"kind":"put","path":"src/main/vernac/proof/model.vernac","text":"broken"}]}
                        """).at("/result/revision").asInt());
                release.countDown();
                var completed = await(daemon, run, state -> state.equals("COMPLETED"));
                assertEquals(0, completed.at("/result/revision").asInt());
                assertEquals("PASSED", completed.at("/result/outcomes/" + CASE).asText());
                assertEquals(0, call(daemon, """
                        {"op":"sharedRevision"}
                        """).at("/result/revision").asInt());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void replaysCheckedEditsByKeyWithoutReapplyingThem() throws Exception {
        createWorkspace();
        ScenarioRunSession.Executor unused = request -> fail("edits must not run scenarios");
        try (var daemon = new ScenarioDaemon(ScenarioWorkspace.openSaved(root, unused, "domain"), () -> unused)) {
            call(daemon, """
                    {"op":"open","revision":0}
                    """);
            var request = """
                    {"op":"applyChecked","client":"1","revision":0,"requestKey":"rename","edits":[{"kind":"move","from":"src/main/vernac/proof/model.vernac","to":"src/main/vernac/proof/moved.vernac"}]}
                    """;
            var original = call(daemon, request);
            assertEquals(1, original.at("/result/revision").asInt(), original.toString());
            assertEquals(original, call(daemon, request));
            assertEquals("invalid_request", call(daemon, request.replace("moved.vernac", "other.vernac"))
                    .at("/error/code").asText());
            assertEquals("stale_revision", call(daemon, request.replace("rename", "another"))
                    .at("/error/code").asText());
        }
    }

    private Path createWorkspace() throws Exception {
        var source = Files.createDirectories(root.resolve("src/main/vernac/proof")).resolve("model.vernac");
        Files.writeString(source, "namespace proof; value Counter(int);");
        var scenarios = Files.createDirectories(root.resolve("src/test/vernac/proof")).resolve("scenarios.vernac");
        Files.writeString(scenarios, "namespace proof; suite Checks { scenario counter { expect true equals true; } }");
        return source;
    }

    private static JsonNode await(ScenarioDaemon daemon, String run, Predicate<String> ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode status;
        do {
            status = job(daemon, "runStatus", run);
            if (ready.test(status.path("state").asText())) return status;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(status);
    }

    private static JsonNode job(ScenarioDaemon daemon, String operation, String run) {
        var reply = call(daemon, JSON.writeValueAsString(Map.of("op", operation, "runId", run)));
        assertTrue(reply.path("error").isNull(), reply.toString());
        return reply.path("result");
    }

    private static JsonNode call(ScenarioDaemon daemon, String input) {
        var request = (tools.jackson.databind.node.ObjectNode) JSON.readTree(input);
        request.put("id", 1);
        return JSON.valueToTree(daemon.execute(request));
    }
}
