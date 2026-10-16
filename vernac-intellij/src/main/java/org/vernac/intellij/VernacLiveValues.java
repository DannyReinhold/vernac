// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.lang.Language;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.testFramework.LightVirtualFile;

import org.vernac.compiler.modules.ModuleArtifacts;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.tooling.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Project-scoped, read-only live domain API models. No compiler output or background build is produced. */
@Service(Service.Level.PROJECT)
public final class VernacLiveValues {
    static final Key<Entry> ENTRY = Key.create("vernac.live.value");

    record Entry(Snapshot snapshot, EditorValueObjects.Value value) {}

    final Project project;
    private final CachedValue<Map<Path, Snapshot>> cache;

    public VernacLiveValues(Project project) {
        this.project = project;
        cache =
                CachedValuesManager.getManager(project)
                        .createCachedValue(
                                () ->
                                        CachedValueProvider.Result.create(
                                                new ConcurrentHashMap<>(),
                                                PsiModificationTracker.MODIFICATION_COUNT,
                                                VirtualFileManager.getInstance()),
                                false);
    }

    Snapshot snapshot(Path root) {
        return cache.getValue().computeIfAbsent(root, this::load);
    }

    Collection<Snapshot> knownSnapshots() {
        return List.copyOf(cache.getValue().values());
    }

    private Snapshot load(Path root) {
        Map<Path, String> texts = new HashMap<>();
        var documents = FileDocumentManager.getInstance();
        for (var document : documents.getUnsavedDocuments()) {
            var file = documents.getFile(document);
            if (file != null && file.isInLocalFileSystem()) {
                Path path = file.toNioPath().toAbsolutePath().normalize();
                if (path.startsWith(root) && path.toString().endsWith(".vernac"))
                    texts.put(path, document.getText());
            }
        }
        try {
            ProgressManager.checkCanceled();
            var model = new VernacProjectLoader().loadForAnalysis(root, texts, new ArrayList<>());
            try {
                model =
                        ModuleArtifacts.merge(
                                model, ModuleArtifacts.editorModules(root), new ArrayList<>());
            } catch (IOException | IllegalArgumentException unavailableContracts) {
                // Build/module diagnostics own this failure. Independent local domain APIs remain useful.
            }
            for (var source : model.sources())
                if (!source.external() && !texts.containsKey(source.path()))
                    texts.put(source.path(), Files.readString(source.path()));
            Set<Path> owned;
            try {
                owned =
                        GeneratedSourceOwnership.sources(
                                ModuleArtifacts.projectDirectory(root)
                                        .resolve("target/generated-sources/vernac"));
            } catch (IOException invalidInventory) {
                owned = Set.of();
            }
            return new Snapshot(root, model, texts, new EditorValueObjects().build(model), owned);
        } catch (IOException unavailableSource) {
            return new Snapshot(
                    root,
                    null,
                    Map.of(),
                    new EditorValueObjects.Model(Map.of(), Set.of(), Set.of()),
                    Set.of());
        }
    }

    final class Snapshot {
        final Path root;
        final VernacProject model;
        final Map<Path, String> texts;
        final EditorValueObjects.Model api;
        final Set<Path> owned;
        final Map<String, PsiClass> classes = new ConcurrentHashMap<>();

        Snapshot(
                Path root,
                VernacProject model,
                Map<Path, String> texts,
                EditorValueObjects.Model api,
                Set<Path> owned) {
            this.root = root;
            this.model = model;
            this.texts = Map.copyOf(texts);
            this.api = api;
            this.owned = Set.copyOf(owned);
        }

        PsiClass find(String name) {
            var value = api.values().get(name);
            if (value == null) return null;
            return classes.computeIfAbsent(
                    name,
                    ignored -> {
                        var source =
                                LocalFileSystem.getInstance().findFileByNioFile(value.source());
                        if (source == null) return null;
                        var language = Language.findLanguageByID("JAVA");
                        if (language == null) return null;
                        var light =
                                new LightVirtualFile(
                                        name.substring(name.lastIndexOf('.') + 1) + ".java",
                                        language,
                                        value.javaSource());
                        light.setOriginalFile(source);
                        light.setWritable(false);
                        var provider =
                                new SingleRootFileViewProvider(
                                        PsiManager.getInstance(project), light, false);
                        var file = new VernacLiveJavaFile(provider, source, this);
                        provider.forceCachedPsi(file);
                        file.putUserData(ENTRY, new Entry(this, value));
                        return file.getClasses()[0];
                    });
        }

        boolean replaces(String name, Path file) {
            return shouldReplace(api, owned, name, file);
        }
    }

    static boolean shouldReplace(
            EditorValueObjects.Model api, Set<Path> owned, String name, Path file) {
        if (name == null || !owned.contains(file.toAbsolutePath().normalize())) return false;
        return api.values().containsKey(name)
                || api.valueNames().contains(name)
                || accessForType(api, name)
                || accessOwner(name) == null && !api.liveNames().contains(name);
    }

    static boolean accessForType(EditorValueObjects.Model api, String name) {
        String owner = accessOwner(name);
        return owner != null && (api.valueNames().contains(owner) || !api.liveNames().contains(owner));
    }

    private static String accessOwner(String name) {
        if (name == null || !name.contains(".domain.access.")) return null;
        String role = List.of("Read", "Write", "Access").stream().filter(name::endsWith).findFirst().orElse(null);
        if (role == null) return null;
        int access = name.lastIndexOf(".domain.access.");
        String simple = name.substring(access + ".domain.access.".length(), name.length() - role.length());
        if (simple.isEmpty() || simple.contains(".")) return null;
        return name.substring(0, access) + ".domain." + simple;
    }
}
