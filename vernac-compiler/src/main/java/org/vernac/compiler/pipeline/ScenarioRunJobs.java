// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.platform.engine.CancellationToken;
import org.vernac.compiler.analyzer.SemanticValidationException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded, process-local background runs with cooperative cancellation. */
final class ScenarioRunJobs implements AutoCloseable {
    private static final int RETAINED_RUNS = 32;

    @FunctionalInterface
    interface Work {
        void execute(ScenarioRunSession.Completion completion) throws IOException;
    }

    @FunctionalInterface
    interface Capture {
        Captured capture() throws IOException;
    }

    record Captured(long revision, Work work) { }

    enum State { RUNNING, CANCELLING, CANCELLED, COMPLETED, ERROR }

    record Request(String requestKey, String client, long revision, boolean opaqueChange) { }

    record Start(String runId, long revision) { }

    record Status(String runId, long revision, State state, ScenarioRunSession.Run result,
                  ScenarioDaemon.Problem error) { }

    private static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final Request request;
        private final long revision;
        private final CancellationToken cancellation = CancellationToken.create();
        private State state = State.RUNNING;
        private Thread worker;
        private ScenarioRunSession.Run result;
        private ScenarioDaemon.Problem error;

        private Job(Request request, long revision) {
            this.request = request;
            this.revision = revision;
        }

        Status status() {
            return new Status(id, revision, state, result, error);
        }
    }

    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private Job active;
    private boolean closed;

    synchronized Start start(Request request, Capture capture) throws IOException {
        if (closed) throw new IllegalStateException("Scenario run service is closed");
        if (request.requestKey() != null) {
            for (Job previous : jobs.values()) {
                if (!request.requestKey().equals(previous.request.requestKey())) continue;
                if (!request.equals(previous.request)) {
                    throw new IllegalArgumentException("Run request key was already used for different inputs");
                }
                return new Start(previous.id, previous.revision);
            }
        }
        requireIdle();
        var work = capture.capture();
        var job = new Job(request, work.revision());
        jobs.put(job.id, job);
        if (jobs.size() > RETAINED_RUNS) jobs.pollFirstEntry();
        active = job;
        job.worker = Thread.ofVirtual().name("vernac-scenario-run").unstarted(() -> execute(job, work.work()));
        job.worker.start();
        return new Start(job.id, job.revision);
    }

    synchronized void requireIdle() {
        if (active != null) throw new IllegalStateException("A background scenario run is still active: " + active.id);
    }

    synchronized Status status(String id) {
        return find(id).status();
    }

    synchronized Status cancel(String id) {
        var job = find(id);
        if (job.state == State.RUNNING) {
            job.state = State.CANCELLING;
            job.cancellation.cancel();
            job.worker.interrupt();
        }
        return job.status();
    }

    private Job find(String id) {
        var job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Unknown or expired scenario run: " + id);
        return job;
    }

    private void execute(Job job, Work work) {
        ScenarioDaemon.Problem error = null;
        try {
            if (!Thread.currentThread().isInterrupted()) {
                work.execute(new ScenarioRunSession.Completion() {
                    @Override
                    public void publish(ScenarioRunSession.Run run, Runnable commitBaseline) {
                        ScenarioRunJobs.this.publish(job, run, commitBaseline);
                    }

                    @Override
                    public CancellationToken cancellation() {
                        return job.cancellation;
                    }
                });
            }
        } catch (SemanticValidationException failure) {
            error = new ScenarioDaemon.Problem("diagnostics", "Workspace check failed", failure.diagnostics());
        } catch (IOException | RuntimeException | LinkageError failure) {
            error = new ScenarioDaemon.Problem("execution_error", failure.toString(), null);
        } finally {
            finish(job, error);
        }
    }

    private synchronized void publish(Job job, ScenarioRunSession.Run result, Runnable commit) {
        if (job.state != State.RUNNING) return;
        commit.run();
        job.result = result;
        job.state = State.COMPLETED;
        active = null;
    }

    private synchronized void finish(Job job, ScenarioDaemon.Problem error) {
        if (job.state == State.CANCELLING) {
            job.state = State.CANCELLED;
        } else if (job.state != State.COMPLETED) {
            job.error = error == null
                    ? new ScenarioDaemon.Problem("execution_error", "Scenario run ended without a result", null) : error;
            job.state = State.ERROR;
        }
        if (active == job) active = null;
        job.worker = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (active != null) cancel(active.id);
    }
}
