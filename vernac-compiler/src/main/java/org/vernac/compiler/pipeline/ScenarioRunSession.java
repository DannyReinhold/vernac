// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.junit.platform.engine.CancellationToken;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Plans and executes affected scenarios, retaining only an execution-verified baseline. */
public final class ScenarioRunSession {
    public enum Outcome { PASSED, FAILED, SKIPPED }

    public record Execution(long revision, ResolvedProject project, JavaFile bridge,
                            List<AffectedScenarioSelector.Selection> selected, CancellationToken cancellation) {
        public Execution { selected = List.copyOf(selected); }

        public Execution(long revision, ResolvedProject project, JavaFile bridge,
                         List<AffectedScenarioSelector.Selection> selected) {
            this(revision, project, bridge, selected, CancellationToken.disabled());
        }
    }

    @FunctionalInterface
    public interface Executor {
        Map<String, Outcome> execute(Execution request) throws IOException;

        default void invalidate() { }
    }

    public record Run(long revision, List<AffectedScenarioSelector.Selection> selected,
                      Map<String, Outcome> outcomes) {
        public Run {
            selected = List.copyOf(selected);
            outcomes = Map.copyOf(outcomes);
        }
    }

    @FunctionalInterface
    interface Completion {
        // A background job can decline to publish after cancellation, including the baseline update.
        void publish(Run run, Runnable commitBaseline);

        default CancellationToken cancellation() { return CancellationToken.disabled(); }
    }

    private final ScenarioPlanningSession planning;
    private final Executor executor;
    private final String profile;
    private ScenarioPlanningSession.Prepared baseline;

    public ScenarioRunSession(ScenarioPlanningSession planning, Executor executor, String profile) {
        this.planning = Objects.requireNonNull(planning);
        this.executor = Objects.requireNonNull(executor);
        this.profile = profile;
    }

    public synchronized Run run(ScenarioPlanningSession.Inputs inputs, boolean opaqueChange) throws IOException {
        return run(inputs, opaqueChange, (result, commit) -> commit.run());
    }

    synchronized Run run(ScenarioPlanningSession.Inputs inputs, boolean opaqueChange, Completion completion)
            throws IOException {
        var previous = Optional.ofNullable(baseline);
        if (opaqueChange) {
            baseline = null;
            executor.invalidate();
        }
        var plan = planning.plan(previous, inputs, opaqueChange, profile);
        var run = execute(plan, completion.cancellation());
        completion.publish(run, () -> {
            if (run.outcomes().values().stream().allMatch(outcome -> outcome == Outcome.PASSED)) {
                baseline = plan.target();
            }
        });
        return run;
    }

    public synchronized Run runScenario(ScenarioPlanningSession.Inputs inputs, String id) throws IOException {
        var plan = planning.plan(Optional.empty(), inputs, false, profile);
        if (plan.selected().stream().noneMatch(selection -> selection.id().equals(id))) {
            throw new IllegalArgumentException("Unknown scenario in the selected profile: " + id);
        }
        var run = execute(new ScenarioPlanningSession.Plan(plan.target(), List.of(
                new AffectedScenarioSelector.Selection(id, AffectedScenarioSelector.Reason.EXPLICIT_SELECTION))), CancellationToken.disabled());
        if (run.outcomes().get(id) != Outcome.PASSED) baseline = null;
        return run;
    }

    private Run execute(ScenarioPlanningSession.Plan plan, CancellationToken cancellation) throws IOException {
        if (cancellation.isCancellationRequested()) throw new InterruptedIOException("Scenario run cancelled");
        long revision = plan.target().revision();
        var ids = plan.selected().stream().map(AffectedScenarioSelector.Selection::id)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Outcome> outcomes = Map.of();

        if (!ids.isEmpty()) {
            outcomes = Map.copyOf(executor.execute(
                    new Execution(revision, plan.target().project(), plan.testBridge(), plan.selected(), cancellation)));
            if (cancellation.isCancellationRequested()) throw new InterruptedIOException("Scenario run cancelled");
            if (!outcomes.keySet().equals(ids)) {
                baseline = null;
                throw new IllegalStateException("Scenario execution results do not match the selected cases");
            }
        }
        return new Run(revision, plan.selected(), outcomes);
    }
}
