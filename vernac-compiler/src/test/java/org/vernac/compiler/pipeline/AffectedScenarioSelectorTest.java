// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.pipeline.AffectedScenarioSelector.Graph;
import org.vernac.compiler.pipeline.AffectedScenarioSelector.Revision;
import org.vernac.compiler.pipeline.AffectedScenarioSelector.Selection;
import org.vernac.compiler.pipeline.VernacScenarioCompiler.Reference;
import org.vernac.compiler.pipeline.VernacScenarioCompiler.ScenarioReferences;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.vernac.compiler.pipeline.AffectedScenarioSelector.Reason.*;

class AffectedScenarioSelectorTest {
    @TempDir Path root;

    @Test
    void plansFromCapturedSourceChangesAndKeepsTheVerifiedBaselineExplicit() throws Exception {
        var path = Path.of("app/counter.vernac");
        var sources = new VersionedSources(root, Map.of(path, "namespace app; value Counter(int);"));
        var testPath = "checks.vernac";
        var tests = Map.of(testPath, """
                namespace app;
                suite Checks {
                    scenario counter { given counter = Counter(1); expect counter.intValue equals 1; }
                }
                """);
        var session = new ScenarioPlanningSession(root, Map.of(), List.of());
        var initial = new ScenarioPlanningSession.Inputs(sources.snapshot(), tests);
        var plan = session.plan(Optional.empty(), initial, false, null);
        assertEquals(List.of(new Selection("app.Checks.counter", NO_BASELINE)), plan.selected());
        var baseline = Optional.of(plan.target());

        var change = sources.apply(0, List.of(new VersionedSources.Put(path,
                "namespace app; value Counter(int) validates { require self intValue > 0 else Negative. };")));
        var edited = new ScenarioPlanningSession.Inputs(change, tests);
        assertEquals(List.of(new Selection("app.Checks.counter", CHANGED_DEPENDENCY)),
                session.plan(baseline, edited, false, null).selected());
        assertEquals(List.of(new Selection("app.Checks.counter", CHANGED_DEPENDENCY)),
                session.plan(baseline, edited, false, null).selected());
        assertEquals(List.of(new Selection("app.Checks.counter", NEW_OR_CHANGED_CASE)),
                session.plan(baseline, new ScenarioPlanningSession.Inputs(initial.main(),
                        Map.of(testPath, tests.get(testPath).replace("equals 1", "equals 2"))), false, null).selected());
        assertEquals(List.of(), session.plan(baseline, initial, false, null).selected());

        assertEquals(List.of(new Selection("app.Checks.counter", NO_BASELINE)),
                session.plan(baseline, initial, false, "domain").selected());
        var otherSession = new ScenarioPlanningSession(root, Map.of(), List.of());
        assertEquals(List.of(new Selection("app.Checks.counter", NO_BASELINE)),
                otherSession.plan(baseline, initial, false, null).selected());
    }

    @Test
    void derivesFieldEdgesFromRealCompilerOutput() throws Exception {
        var namespace = Files.createDirectories(root.resolve("app"));
        var title = Files.writeString(namespace.resolve("title.vernac"), "namespace app; value Title(String);");
        Files.writeString(namespace.resolve("summary.vernac"), "namespace app; value Summary(Title title);");
        Files.writeString(namespace.resolve("counter.vernac"), "namespace app; value Counter(int);");
        var tests = Map.of("src/test/vernac/app/checks.vernac", """
                namespace app;
                suite Checks {
                    scenario summary {
                        given title = Title("hello");
                        given summary = Summary(title);
                        expect summary.title equals title;
                    }
                    scenario counter {
                        given counter = Counter(1);
                        expect counter.intValue equals 1;
                    }
                }
                """);
        var session = new WorkspaceCheckSession(root, Map.of(), List.of());
        var inputs = new VersionedSources(root, Map.of(title, Files.readString(title),
                namespace.resolve("summary.vernac"), "namespace app; value Summary(Title title);",
                namespace.resolve("counter.vernac"), "namespace app; value Counter(int);"));
        var before = ScenarioDependencyGraph.inspect(session.check(inputs.snapshot()).project().orElseThrow(), tests);
        var changed = inputs.apply(0, List.of(new VersionedSources.Put(title,
                "namespace app; value Title(String) validates { require self string != \"\" else BlankTitle. };")));
        var after = ScenarioDependencyGraph.inspect(session.check(changed).project().orElseThrow(), tests);
        assertEquals(Set.of(title.toString()), before.graph().dependencies().get(namespace.resolve("summary.vernac").toString()));
        assertEquals(List.of(new Selection("app.Checks.summary", CHANGED_DEPENDENCY)),
                AffectedScenarioSelector.select(Optional.of(before), after, Set.of(title.toString()), false, null));
    }

    @Test
    void followsBothGraphsAndTerminatesOnCycles() {
        var cases = List.of(scenario("dependent", "domain", false, "entry"),
                scenario("unrelated", "domain", false, "other"));
        var before = new Revision(cases, new Graph(Map.of(
                "dependent", Set.of(), "unrelated", Set.of(), "entry", Set.of("bridge"),
                "bridge", Set.of("entry", "deleted"), "deleted", Set.of(), "other", Set.of())));
        var after = new Revision(cases, new Graph(Map.of(
                "dependent", Set.of(), "unrelated", Set.of(), "entry", Set.of("added"),
                "added", Set.of(), "other", Set.of())));
        for (String changed : List.of("deleted", "added")) {
            assertEquals(List.of(new Selection("dependent", CHANGED_DEPENDENCY)),
                    AffectedScenarioSelector.select(Optional.of(before), after, Set.of(changed), false, null));
        }
    }

    @Test
    void widensSelectionWithMissingEdgesOrOpaqueInputsAndRespectsProfile() {
        var cases = List.of(scenario("domain", "domain", false, "unknown"),
                scenario("mvc", "mvc", true, "view"),
                scenario("postgres", "postgres", true, "store"));
        var revision = new Revision(cases, new Graph(Map.of("domain", Set.of())));
        assertEquals(List.of(new Selection("domain", INCOMPLETE_GRAPH), new Selection("mvc", JAVA_FIXTURE)),
                AffectedScenarioSelector.select(Optional.of(revision), revision, Set.of("edited"), false, null));
        assertEquals(List.of(new Selection("postgres", OPAQUE_CHANGE)),
                AffectedScenarioSelector.select(Optional.of(revision), revision, Set.of(), true, "postgres"));
        assertEquals(List.of(new Selection("domain", NO_BASELINE), new Selection("mvc", NO_BASELINE)),
                AffectedScenarioSelector.select(Optional.empty(), revision, Set.of(), false, null));
        assertEquals(List.of(),
                AffectedScenarioSelector.select(Optional.of(revision), revision, Set.of(), false, null));
    }

    @Test
    void selectsAddedCasesAndDoesNotRunDeletedCases() {
        var graph = new Graph(Map.of());
        var before = new Revision(List.of(scenario("old", "domain", false, "model")), graph);
        var after = new Revision(List.of(scenario("new", "domain", false, "model")), graph);
        assertEquals(List.of(new Selection("new", NEW_OR_CHANGED_CASE)),
                AffectedScenarioSelector.select(Optional.of(before), after, Set.of(), false, null));
    }

    private static ScenarioReferences scenario(String id, String profile, boolean fixture, String dependency) {
        var at = new SourceLocation(id, 1, 1);
        return new ScenarioReferences(id, profile, at, fixture,
                List.of(new Reference(at, 1, new SourceLocation(dependency, 1, 1))));
    }
}
