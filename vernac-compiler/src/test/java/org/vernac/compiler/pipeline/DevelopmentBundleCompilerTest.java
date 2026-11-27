// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.runtime.DevelopmentRevisions;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.vernac.compiler.pipeline.DevelopmentBundleCompiler.Status.*;

class DevelopmentBundleCompilerTest {
    @TempDir Path root;

    private static final String PACKAGED = """
            namespace proof;
            id CounterId;
            value Points(int);
            aggregate Counter[CounterId](Points points, Points bonus) behavior {
                read int score() vernac { /* ✨🧪 */ return self points intValue + 10. }
            };
            view Board() {
                page "Scoreboard" { heading "Old heading"; }
            }
            """;

    @Test
    void createsAnActivatableBundleUsingThePackagedJvmBridge() throws Exception {
        var baseline = source(0, PACKAGED);
        var compiler = new DevelopmentBundleCompiler(root, Map.of(), List.of(), baseline);
        var packaged = new WorkspaceCheckSession(root, Map.of(), List.of()).check(baseline).project().orElseThrow();
        var classes = InMemoryJavaCompiler.compile(new VernacCompiler().generate(packaged, SchemaModel.empty(), Map.of()).generatedFiles());
        assertTrue(classes.success(), classes.diagnostics().toString());
        var counter = classes.loadClass("proof.domain.Counter");
        var points = classes.loadClass("proof.domain.Points");
        var of = points.getMethod("of", int.class);
        var instance = counter.getMethod("create", points, points).invoke(null, of.invoke(null, 7), of.invoke(null, 0));
        var score = counter.getMethod("score");
        assertEquals(17, score.invoke(instance));

        var candidate = compiler.compile(source(1, PACKAGED.replace("10.", "300.").replace("Old heading",
                "New heading").replace("heading \"New heading\";", "\n            heading \"New heading\";")));
        assertEquals(READY, candidate.status());
        assertEquals(1, candidate.revision());
        var bundle = candidate.bundle().orElseThrow();
        assertEquals(1, bundle.behaviors().size());
        var patch = bundle.behaviors().getFirst();
        var key = new DevelopmentRevisions.BehaviorKey(Class.forName(patch.implementation(), true, classes.classLoader()), patch.program());
        var revisions = new DevelopmentRevisions();
        revisions.activate(0, Map.of(key, new DevelopmentRevisions.BehaviorUpdate(patch.layout(), patch.sourceMap())), bundle.templates());
        try (var scope = revisions.pin()) {
            assertEquals(307, score.invoke(instance));
            assertTrue(revisions.template("vernac-generated/proof/Board.html").orElseThrow().contains("New heading"));
        }

        var restored = compiler.compile(source(2, PACKAGED)).bundle().orElseThrow();
        assertTrue(restored.behaviors().isEmpty());
        assertTrue(restored.templates().isEmpty());
        revisions.activate(1, Map.of(), Map.of());
        try (var scope = revisions.pin()) {
            assertEquals(17, score.invoke(instance));
            assertTrue(revisions.template("vernac-generated/proof/Board.html").isEmpty());
        }
    }

    @Test
    void rejectsBrokenSourceAndRequiresARebuildForNonPortableChanges() throws Exception {
        var compiler = new DevelopmentBundleCompiler(root, Map.of(), List.of(), source(0, PACKAGED));

        var broken = compiler.compile(source(1, PACKAGED.replace("self points", "self missing")));
        assertEquals(INVALID, broken.status());
        assertTrue(broken.bundle().isEmpty());
        assertTrue(broken.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("missing")));

        for (String target : List.of(PACKAGED.replace("self points", "self bonus"),
                PACKAGED.replace("return self points intValue + 10.", "let result = self points intValue + 10. return result."),
                PACKAGED.replace("view Board()", "view Other()"),
                PACKAGED.replace("Points bonus", "mut Points bonus"))) {
            var result = compiler.compile(source(2, target));
            assertEquals(REBUILD_REQUIRED, result.status(), result.toString());
            assertTrue(result.bundle().isEmpty());
        }
    }

    private VersionedSources.Snapshot source(long revision, String text) {
        return new VersionedSources.Snapshot(revision, Map.of(root.resolve("proof/board.vernac"), text));
    }
}
