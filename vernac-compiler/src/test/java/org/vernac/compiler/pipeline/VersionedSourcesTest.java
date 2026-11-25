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
}
