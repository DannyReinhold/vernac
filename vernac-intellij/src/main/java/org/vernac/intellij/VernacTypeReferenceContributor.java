// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.util.*;

import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.tooling.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Native source ranges for Ctrl-hover; Java injections continue to own their Java references. */
public final class VernacTypeReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(VernacInjectionHost.class),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference[] getReferencesByElement(
                            PsiElement element, ProcessingContext context) {
                        if (DumbService.isDumb(element.getProject()))
                            return PsiReference.EMPTY_ARRAY;
                        var host = (VernacInjectionHost) element;
                        var references =
                                CachedValuesManager.getCachedValue(
                                        host,
                                        () ->
                                                CachedValueProvider.Result.create(
                                                        references(host),
                                                        PsiModificationTracker.MODIFICATION_COUNT,
                                                        VirtualFileManager.getInstance()));
                        return references.toArray(PsiReference[]::new);
                    }
                });
    }

    private static List<PsiReference> references(VernacInjectionHost host) {
        var file = host.getContainingFile().getOriginalFile().getVirtualFile();
        if (file == null || !file.isInLocalFileSystem()) return List.of();
        Path path = file.toNioPath();
        var root = VernacNamespaces.sourceRoot(path.getParent());
        if (root.isEmpty()) return List.of();
        Map<Path, String> texts = new HashMap<>();
        var documents = FileDocumentManager.getInstance();
        for (var document : documents.getUnsavedDocuments()) {
            var virtual = documents.getFile(document);
            if (virtual != null && virtual.isInLocalFileSystem()) {
                Path candidate = virtual.toNioPath();
                if (candidate.startsWith(root.get()) && candidate.toString().endsWith(".vernac"))
                    texts.put(candidate, document.getText());
            }
        }
        texts.put(path, host.getText());
        try {
            ProgressManager.checkCanceled();
            var project =
                    new VernacProjectLoader().loadForAnalysis(root.get(), texts, new ArrayList<>());
            var modules = ModuleArtifacts.editorModules(root.get());
            project = ModuleArtifacts.merge(project, modules);
            for (var source : project.sources()) {
                ProgressManager.checkCanceled();
                if (source.external()) {
                    var module =
                            modules.stream()
                                    .filter(
                                            m ->
                                                    m.exports().stream()
                                                            .anyMatch(
                                                                    d ->
                                                                            d.identity()
                                                                                    .startsWith(
                                                                                            source.unit()
                                                                                                            .namespace()
                                                                                                    + ".")))
                                    .filter(
                                            m ->
                                                    source.path()
                                                            .startsWith(
                                                                    ModuleArtifacts
                                                                            .projectDirectory(
                                                                                    root.get())
                                                                            .resolve(
                                                                                    "target/vernac-module-contracts")
                                                                            .resolve(m.module())))
                                    .findFirst()
                                    .orElse(null);
                    if (module != null)
                        texts.put(
                                source.path(),
                                ModuleArtifacts.view(module, source.unit().namespace()));
                } else if (!texts.containsKey(source.path()))
                    texts.put(source.path(), Files.readString(source.path()));
            }
            var origins = ModuleSources.locations(root.get(), modules);
            List<PsiReference> result = new ArrayList<>();
            for (var reference : new VernacTypeReferences().build(project, path, texts)) {
                ProgressManager.checkCanceled();
                var origin = origins.get(reference.identity());
                var target =
                        origin == null
                                ? reference.target()
                                : new VernacTypeNavigation.Target(
                                        origin.path(),
                                        reference.target().name(),
                                        origin.start(),
                                        origin.end());
                String targetText =
                        reference.target().source().startsWith(root.get())
                                ? null
                                : origin == null ? texts.get(target.source()) : origin.text();
                result.add(
                        new TypeReference(
                                host,
                                new TextRange(reference.start(), reference.end()),
                                reference.identity(),
                                target,
                                targetText));
            }
            for (var reference : new VernacTypeReferences().javaReferences(host.getText())) {
                ProgressManager.checkCanceled();
                result.add(
                        new JavaTypeReference(
                                host,
                                new TextRange(reference.start(), reference.end()),
                                reference.javaName()));
            }
            return List.copyOf(result);
        } catch (IOException | SemanticValidationException unavailable) {
            return List.of(); // No guessed target; LSP diagnostics/navigation remain available.
        }
    }

    /** Resolve through IntelliJ's module/JDK scope, never by a project-wide simple-name guess. */
    static final class JavaTypeReference extends PsiReferenceBase<VernacInjectionHost> {
        private final String javaName;

        JavaTypeReference(VernacInjectionHost host, TextRange range, String javaName) {
            super(host, range, true);
            this.javaName = javaName;
        }

        @Override
        public String getCanonicalText() {
            return javaName;
        }

        @Override
        public PsiElement resolve() {
            var host = getElement();
            if (!host.isValid() || DumbService.isDumb(host.getProject())) return null;
            return JavaPsiFacade.getInstance(host.getProject())
                    .findClass(javaName, host.getResolveScope());
        }

        @Override
        public Object[] getVariants() {
            return new Object[0];
        }

        @Override
        public PsiElement handleElementRename(String name) {
            throw new IncorrectOperationException(
                    "Cross-language Vernac rename is not supported by navigation references yet.");
        }

        @Override
        public PsiElement bindToElement(PsiElement element) {
            throw new IncorrectOperationException(
                    "Rebinding Java types requires an explicit Vernac edit.");
        }
    }

    static final class TypeReference extends PsiReferenceBase<VernacInjectionHost> {
        private final String identity;
        private final VernacTypeNavigation.Target target;
        private final String targetText;
        private PsiFile fallback;

        TypeReference(
                VernacInjectionHost host,
                TextRange range,
                String identity,
                VernacTypeNavigation.Target target,
                String targetText) {
            super(host, range, true);
            this.identity = identity;
            this.target = target;
            this.targetText = targetText;
        }

        @Override
        public String getCanonicalText() {
            return identity;
        }

        @Override
        public PsiElement resolve() {
            if (!getElement().isValid()) return null;
            var virtual = VirtualFileManager.getInstance().findFileByNioPath(target.source());
            PsiFile file =
                    virtual == null
                            ? null
                            : PsiManager.getInstance(getElement().getProject()).findFile(virtual);
            if (file != null && targetText != null && !file.getText().equals(targetText))
                file = null;
            if (file == null && targetText != null) file = fallback();
            return file == null
                    ? null
                    : new VernacTypeNavigationTarget(
                            file, target.name(), target.start(), target.end());
        }

        private synchronized PsiFile fallback() {
            if (fallback != null && fallback.isValid()) return fallback;
            // A stable read-only module view, never a fabricated missing local declaration.
            var file =
                    PsiFileFactory.getInstance(getElement().getProject())
                            .createFileFromText(
                                    target.source().getFileName().toString(),
                                    VernacFileType.INSTANCE,
                                    targetText);
            try {
                file.getViewProvider().getVirtualFile().setWritable(false);
            } catch (IOException unavailable) {
                return null;
            }
            fallback = file;
            return file;
        }

        @Override
        public Object[] getVariants() {
            return new Object[0];
        } // Completion remains LSP-owned.

        @Override
        public PsiElement handleElementRename(String name) {
            throw new IncorrectOperationException(
                    "Cross-language Vernac rename is not supported by navigation references yet.");
        }

        @Override
        public PsiElement bindToElement(PsiElement element) {
            throw new IncorrectOperationException(
                    "Rebinding Vernac types requires an explicit model/import edit.");
        }
    }
}
