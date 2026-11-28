// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VersionedSourcesTest {
    @TempDir Path root;

    @Test
    void publishesTheWholeBatchAndKeepsOlderSnapshotsReadable() throws Exception {
        var model = Path.of("app/model.vernac");
        var old = Path.of("app/old.vernac");
        var moved = Path.of("app/moved.vernac");
        String original = "namespace app; value Title(String);";
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve(old), "namespace app; id OldId;");
        var workspace = new VersionedSources(root, Map.of(model, original, old, "namespace app; id OldId;"));
        var initial = workspace.snapshot();
        var loader = new VernacProjectLoader().openSession(root);
        loader.loadSnapshot(initial.sources());

        assertThrows(IllegalArgumentException.class, () -> workspace.apply(0, List.of(
                new VersionedSources.Put(model, "invalid"), new VersionedSources.Move(old, model))));
        assertSame(initial, workspace.snapshot());

        var second = workspace.apply(0, List.of(new VersionedSources.Move(old, moved),
                new VersionedSources.Put(Path.of("app/new.vernac"), "namespace app; id NewId;")));
        assertEquals(1, second.revision());
        assertEquals(1, assertThrows(VersionedSources.Conflict.class,
                () -> workspace.apply(0, List.of(new VersionedSources.Delete(model)))).currentRevision());
        var project = loader.loadSnapshot(second.sources());
        assertEquals(new VernacProjectLoader.ParseStatistics(2, 1), loader.statistics());
        assertEquals(root.resolve(moved), project.symbols().find("app.OldId").orElseThrow().sourceFile());

        var third = workspace.apply(1, List.of(new VersionedSources.Delete(moved)));
        assertTrue(loader.loadSnapshot(third.sources()).symbols().find("app.OldId").isEmpty());
        assertTrue(Files.exists(root.resolve(old)));
        assertEquals(root.resolve(old),
                loader.loadSnapshot(initial.sources()).symbols().find("app.OldId").orElseThrow().sourceFile());
        assertSame(third, workspace.apply(2, List.of(new VersionedSources.Put(model, original))));
    }

    @Test
    void checksTheRequestedRevisionEvenAfterADifferentRevisionWasChecked() throws Exception {
        var path = Path.of("app/model.vernac");
        String valid = "namespace app; value Title(String);";
        var sources = new VersionedSources(root, Map.of(path, valid));
        var session = new WorkspaceCheckSession(root, Map.of(), List.of());
        var initial = sources.snapshot();
        assertTrue(session.check(initial).diagnostics().isEmpty());
        assertTrue(session.check(initial).reusedCheck());

        var invalid = sources.apply(0, List.of(new VersionedSources.Put(path,
                "namespace app; value Title(MissingType);")));
        var failure = session.check(invalid);
        assertEquals(1L, failure.revision());
        assertFalse(failure.diagnostics().isEmpty());
        assertTrue(session.check(invalid).reusedCheck());

        var syntax = sources.apply(1, List.of(new VersionedSources.Put(path, "namespace app; value ;")));
        assertTrue(session.check(syntax).project().isEmpty());
        assertFalse(session.check(syntax).diagnostics().isEmpty());
        assertTrue(session.check(initial).diagnostics().isEmpty());
        assertEquals(0L, session.check(initial).revision());
    }

    @Test
    void reconcilesCompleteCapturesWithoutPublishingAnInvalidOrStaleRevision() {
        var model = Path.of("src/main/vernac/app/model.vernac");
        var scenario = Path.of("src/test/vernac/app/checks.vernac");
        var sources = new VersionedSources(root, Map.of(model, "old", scenario, "test"));
        var initial = sources.snapshot();
        assertSame(initial, sources.reconcile(0, Map.of(model, "old", scenario, "test")));
        assertThrows(IllegalArgumentException.class,
                () -> sources.reconcile(0, Map.of(model, "new", Path.of("../outside.vernac"), "out")));
        assertSame(initial, sources.snapshot());

        var updated = sources.reconcile(0, Map.of(model, "new"));
        assertEquals(1, updated.revision());
        assertEquals(Map.of(root.resolve(model), "new"), updated.sources());
        assertEquals("test", initial.sources().get(root.resolve(scenario)));
        assertThrows(VersionedSources.Conflict.class, () -> sources.reconcile(0, Map.of()));
        assertSame(updated, sources.snapshot());
    }


    @Test
    void usesCapturedModuleConfigurationInsteadOfReadingDisk() throws Exception {
        var path = Path.of("app/model.vernac");
        var module = root.resolve("app/vernac-module.vmodule");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "module app { export app.Missing; }");
        var snapshot = new VersionedSources(root, Map.of(path, "namespace app; value Title(String);")).snapshot();
        assertTrue(new WorkspaceCheckSession(root, Map.of(), List.of()).check(snapshot).diagnostics().isEmpty());
        assertFalse(new WorkspaceCheckSession(root, Map.of(module, Files.readString(module)), List.of())
                .check(snapshot).diagnostics().isEmpty());
    }

}
