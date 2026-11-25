// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Plans and executes affected scenarios, retaining only an execution-verified baseline. */
public final class ScenarioRunSession {
    public enum Outcome { PASSED, FAILED, SKIPPED }

    public record Execution(long revision, ResolvedProject project, JavaFile bridge,
                            List<AffectedScenarioSelector.Selection> selected) {
        public Execution { selected = List.copyOf(selected); }
    }

    @FunctionalInterface
    public interface Executor {
        Map<String, Outcome> execute(Execution request) throws IOException;
    }

    public record Run(long revision, List<AffectedScenarioSelector.Selection> selected,
                      Map<String, Outcome> outcomes) {
        public Run {
            selected = List.copyOf(selected);
            outcomes = Map.copyOf(outcomes);
        }
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
        var previous = Optional.ofNullable(baseline);
        if (opaqueChange) baseline = null;
        var plan = planning.plan(previous, inputs, opaqueChange, profile);
        long revision = plan.target().revision();
        var ids = plan.selected().stream().map(AffectedScenarioSelector.Selection::id)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Outcome> outcomes = Map.of();

        if (!ids.isEmpty()) {
            outcomes = Map.copyOf(executor.execute(
                    new Execution(revision, plan.target().project(), plan.testBridge(), plan.selected())));
            if (!outcomes.keySet().equals(ids)) {
                baseline = null;
                throw new IllegalStateException("Scenario execution results do not match the selected cases");
            }
        }
        if (outcomes.values().stream().allMatch(outcome -> outcome == Outcome.PASSED)) {
            baseline = plan.target();
        }
        return new Run(revision, plan.selected(), outcomes);
    }
}
