// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keep the conventional generated Java root visible after the first delegated Maven build. */
public final class VernacGeneratedSourcesActivity implements ProjectActivity {
    @Override
    public Object execute(Project project, Continuation<? super Unit> continuation) {
        project.getService(Watcher.class);
        return Unit.INSTANCE;
    }

    @Service(Service.Level.PROJECT)
    public static final class Watcher implements Disposable {
        public Watcher(Project project) {
            var pending = new AtomicBoolean();
            Runnable update = () -> schedule(project, pending);
            var connection = project.getMessageBus().connect(this);
            connection.subscribe(
                    VirtualFileManager.VFS_CHANGES,
                    new BulkFileListener() {
                        @Override
                        public void after(@NotNull List<? extends VFileEvent> events) {
                            if (events.stream().anyMatch(e -> relevant(e.getPath()))) update.run();
                        }
                    });
            connection.subscribe(
                    ModuleRootListener.TOPIC,
                    new ModuleRootListener() {
                        @Override
                        public void rootsChanged(@NotNull ModuleRootEvent event) {
                            update.run();
                        }
                    });
            update.run();
        }

        @Override
        public void dispose() {}
    }

    static boolean relevant(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.endsWith("/target")
                || normalized.endsWith("/target/generated-sources")
                || normalized.contains("/target/generated-sources/vernac")
                || normalized.endsWith(".vernac")
                || normalized.endsWith("/pom.xml");
    }

    static Path candidate(Path content, Set<String> sourceUrls) {
        Path root = content.resolve("target/generated-sources/vernac");
        if (!Files.isRegularFile(content.resolve("pom.xml"))
                || !Files.isDirectory(content.resolve("src/main/vernac"))
                || !Files.isDirectory(root)) return null;
        // Compare normalized paths: IntelliJ file URLs and URI slash spelling may differ.
        for (String url : sourceUrls) {
            if (url.replace('\\', '/')
                    .replaceAll("/+$", "")
                    .equals(
                            "file://"
                                    + root.toAbsolutePath()
                                            .normalize()
                                            .toString()
                                            .replace('\\', '/'))) return null;
        }
        return root;
    }

    private static void schedule(Project project, AtomicBoolean pending) {
        if (project.isDisposed() || !pending.compareAndSet(false, true)) return;
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            pending.set(false);
                            if (!project.isDisposed()) WriteAction.run(() -> register(project));
                        });
    }

    private static void register(Project project) {
        var index = ProjectFileIndex.getInstance(project);
        for (var module : ModuleManager.getInstance(project).getModules()) {
            if (module.isDisposed()) continue;
            var manager = ModuleRootManager.getInstance(module);
            // No model mutation for modules that need no repair.
            for (var entry : manager.getContentEntries()) {
                var content = entry.getFile();
                if (content == null || !content.isInLocalFileSystem()) continue;
                var pom = content.findChild("pom.xml");
                if (pom == null || index.getModuleForFile(pom) != module) continue;
                Path candidate =
                        candidate(
                                content.toNioPath(),
                                java.util.Arrays.stream(manager.getSourceRootUrls())
                                        .collect(java.util.stream.Collectors.toSet()));
                if (candidate == null) continue;
                var root = content.findFileByRelativePath("target/generated-sources/vernac");
                if (root == null || !root.isDirectory() || index.isInSource(root)) continue;
                var owner = index.getModuleForFile(root, false);
                if (owner != null && owner != module) continue;
                var model = manager.getModifiableModel();
                try {
                    var writable =
                            java.util.Arrays.stream(model.getContentEntries())
                                    .filter(e -> e.getUrl().equals(entry.getUrl()))
                                    .findFirst()
                                    .orElseThrow();
                    writable.addSourceFolder(
                            root,
                            JavaSourceRootType.SOURCE,
                            JpsJavaExtensionService.getInstance()
                                    .createSourceRootProperties("", true));
                    model.commit();
                } finally {
                    if (!model.isDisposed()) model.dispose();
                }
            }
        }
    }
}
