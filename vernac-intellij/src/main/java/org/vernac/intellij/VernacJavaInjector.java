// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;
import org.vernac.compiler.pipeline.VernacProjectLoader;
import org.vernac.compiler.tooling.BehaviorJavaProjection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Java contexts for behavior blocks and validation expressions in the live document. */
public final class VernacJavaInjector implements MultiHostInjector {
    @Override public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(VernacInjectionHost.class);
    }

    @Override public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof VernacInjectionHost host)) return;
        Language java = Language.findLanguageByID("JAVA");
        if (java == null) return;
        var blocks = CachedValuesManager.getCachedValue(host, () -> CachedValueProvider.Result.create(
                project(host), PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS));
        for (var block : blocks) {
            ProgressManager.checkCanceled();
            registrar.startInjecting(java);
            for (var f : block.fragments()) registrar.addPlace(f.prefix(), f.suffix(), host,
                    new TextRange(f.start(), f.end()));
            registrar.doneInjecting();
        }
    }

    private List<BehaviorJavaProjection.Block> project(VernacInjectionHost host) {
        var file = host.getContainingFile().getOriginalFile().getVirtualFile();
        if (file == null || !file.isInLocalFileSystem()) return List.of();
        Path path = Path.of(file.getPath()).toAbsolutePath().normalize();
        var root = VernacNamespaces.sourceRoot(path.getParent());
        if (root.isEmpty()) return List.of();
        ProgressManager.checkCanceled();
        Map<Path, String> snapshots = new HashMap<>();
        var documents = FileDocumentManager.getInstance();
        for (var document : documents.getUnsavedDocuments()) {
            var virtualFile = documents.getFile(document);
            if (virtualFile == null || !virtualFile.isInLocalFileSystem()) continue;
            Path candidate = Path.of(virtualFile.getPath()).toAbsolutePath().normalize();
            if (candidate.startsWith(root.get()) && candidate.toString().endsWith(".vernac"))
                snapshots.put(candidate, document.getText());
        }
        String text = host.getText();
        snapshots.put(path, text);
        try {
            // Uses the same namespace/index/import rules as the compiler; unrelated parse errors
            // are retained for the LSP but do not prevent independently valid files being indexed.
            var project = new VernacProjectLoader().loadForAnalysis(root.get(), snapshots, new ArrayList<>());
            ProgressManager.checkCanceled();
            var source = project.sources().stream().filter(s -> s.path().equals(path)).findFirst();
            return source.map(s -> new BehaviorJavaProjection().build(project, s, text)).orElseGet(List::of);
        } catch (IOException unavailableSource) {
            return List.of();
        }
    }
}
