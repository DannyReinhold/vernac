// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;

import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.vernac.compiler.debug.BehaviorSourceMap;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reads build-owned maps. It never generates code or treats edited sources as compiled. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
public final class VernacDebugSources {
    record Bound(Path root, Path output, BehaviorSourceMap.Entry entry) {
        Path source() {
            return root.resolve("src/main/vernac").resolve(entry.source()).normalize();
        }

        Path java() {
            return output.resolve(entry.javaFile()).normalize();
        }
    }

    private record Cached(long modified, long size, BehaviorSourceMap map) {}

    private final Map<Path, Cached> cache = new ConcurrentHashMap<>();

    static List<Bound> all(Project project) {
        Set<Path> roots = new LinkedHashSet<>();
        if (project.getBasePath() != null) roots.add(Path.of(project.getBasePath()));
        for (var maven : MavenProjectsManager.getInstance(project).getProjects())
            roots.add(maven.getDirectoryPath());
        List<Bound> result = new ArrayList<>();
        for (var root : roots) result.addAll(inRoot(project, root));
        return result;
    }

    private static List<Bound> inRoot(Project project, Path directory) {
        var cache = project.getService(VernacDebugSources.class).cache;
        Path root = directory.toAbsolutePath().normalize();
        List<Bound> result = new ArrayList<>();
        {
            Path output = root.resolve("target/generated-sources/vernac"),
                    file = output.resolve(BehaviorSourceMap.FILE);
            try {
                long stamp = Files.getLastModifiedTime(file).toMillis(), size = Files.size(file);
                var cached = cache.get(file);
                if (cached == null || cached.modified() != stamp || cached.size() != size) {
                    cached = new Cached(stamp, size, BehaviorSourceMap.read(output));
                    cache.put(file, cached);
                }
                for (var entry : cached.map().entries()) {
                    var bound = new Bound(root, output, entry);
                    if (bound.source().startsWith(root.resolve("src/main/vernac"))
                            && bound.java().startsWith(output)) result.add(bound);
                }
            } catch (java.io.IOException | IllegalArgumentException unavailable) {
                cache.remove(file);
            }
        }
        return result;
    }

    static List<Bound> at(Project project, Path source, int line) {
        var path = source.toAbsolutePath().normalize();
        return org.vernac.intellij.DiagramSelection.from(path)
                .map(
                        selection ->
                                inRoot(project, selection.projectRoot()).stream()
                                        .filter(
                                                b ->
                                                        b.source().equals(path)
                                                                && b.entry().sourceLine() == line
                                                                && current(b))
                                        .toList())
                .orElse(List.of());
    }

    static boolean current(Bound b) {
        try {
            return BehaviorSourceMap.hash(text(b.source())).equals(b.entry().sourceHash())
                    && BehaviorSourceMap.hash(text(b.java())).equals(b.entry().javaHash());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String text(Path path) throws java.io.IOException {
        String live =
                ReadAction.computeBlocking(
                        () -> {
                            var file = VirtualFileManager.getInstance().findFileByNioPath(path);
                            var document =
                                    file == null
                                            ? null
                                            : FileDocumentManager.getInstance()
                                                    .getCachedDocument(file);
                            return document == null ? null : document.getText();
                        });
        return live == null ? Files.readString(path) : live;
    }
}
