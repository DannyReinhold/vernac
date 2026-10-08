// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.vernac.compiler.pipeline.GeneratedSourceOwnership;
import org.vernac.compiler.pipeline.VernacProjectLoader;
import org.vernac.compiler.tooling.VernacTypeNavigation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Redirects references to compiler-owned Java model types, never unrelated Java classes. */
public final class VernacJavaDeclarationHandler implements GotoDeclarationHandler {
    @Override public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement element, int offset, Editor editor) {
        if (element == null || DumbService.isDumb(element.getProject())) return null;
        var reference = PsiTreeUtil.getParentOfType(element, PsiJavaCodeReferenceElement.class, false);
        if (reference == null || reference.getReferenceNameElement() == null
                || !reference.getReferenceNameElement().getTextRange().containsOffset(offset)) return null;
        PsiElement resolved = reference.resolve();
        PsiClass type;
        Optional<VernacTypeNavigation.Member> member;
        if (resolved instanceof PsiClass clazz) {
            type = clazz;
            member = Optional.empty();
        } else if (resolved instanceof PsiMethod method && !method.isConstructor()
                && method.hasModifierProperty(PsiModifier.PUBLIC) && !method.hasModifierProperty(PsiModifier.STATIC)) {
            type = method.getContainingClass();
            member = Optional.of(new VernacTypeNavigation.Member(method.getName(),
                    Arrays.stream(method.getParameterList().getParameters())
                            .map(p -> p.getType().getCanonicalText(false)).toList()));
        } else return null;
        if (type == null || type.getContainingClass() != null) return null;
        // Cache on the resolved declaration, not the owner: overloads/getters have different targets.
        var target = CachedValuesManager.getCachedValue(resolved, () -> CachedValueProvider.Result.create(
                find(type, member), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.getInstance()));
        if (target.isEmpty()) return null;
        var declaration = target.get();
        var file = VirtualFileManager.getInstance().findFileByNioPath(declaration.source());
        if (file == null) return null;
        var psi = PsiManager.getInstance(element.getProject()).findFile(file);
        if (psi == null) return null;
        return new PsiElement[]{new VernacTypeNavigationTarget(psi, declaration.name(), declaration.start(), declaration.end())};
    }

    private Optional<VernacTypeNavigation.Target> find(PsiClass type, Optional<VernacTypeNavigation.Member> member) {
        String javaName = type.getQualifiedName();
        var containingFile = type.getContainingFile();
        var javaFile = containingFile == null ? null : containingFile.getVirtualFile();
        if (javaName == null || javaFile == null || !javaFile.isInLocalFileSystem()) return Optional.empty();
        try {
            ProgressManager.checkCanceled();
            if (GeneratedSourceOwnership.outputRootOf(Path.of(javaFile.getPath())).isEmpty()) return Optional.empty();
            // Resolve in the referenced type's module, not the Java caller's module.
            var module = ModuleUtilCore.findModuleForPsiElement(type);
            if (module == null) return Optional.empty();
            var maven = MavenProjectsManager.getInstance(type.getProject()).findProject(module);
            if (maven == null) return Optional.empty();
            Path root = maven.getDirectoryPath().resolve("src/main/vernac").toAbsolutePath().normalize();
            Map<Path, String> texts = new HashMap<>();
            var documents = FileDocumentManager.getInstance();
            for (var document : documents.getUnsavedDocuments()) {
                var file = documents.getFile(document);
                if (file == null || !file.isInLocalFileSystem()) continue;
                Path path = Path.of(file.getPath()).toAbsolutePath().normalize();
                if (path.startsWith(root) && path.toString().endsWith(".vernac")) texts.put(path, document.getText());
            }
            var project = new VernacProjectLoader().loadForAnalysis(root, texts, new ArrayList<>());
            for (var source : project.sources()) {
                ProgressManager.checkCanceled();
                if (!texts.containsKey(source.path())) texts.put(source.path(), Files.readString(source.path()));
            }
            var navigation = new VernacTypeNavigation();
            return member.isPresent() ? navigation.findMember(project, texts, javaName, member.get())
                    : navigation.find(project, texts, javaName);
        } catch (IOException unavailableSource) {
            // A missing/unreadable model or manifest leaves standard Java navigation intact.
            return Optional.empty();
        }
    }
}
