// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.engine.evaluation.CodeFragmentFactory;
import com.intellij.debugger.engine.evaluation.DefaultCodeFragmentFactory;
import com.intellij.debugger.engine.evaluation.TextWithImports;
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilder;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;

import org.vernac.intellij.VernacFileType;

/** Java expression fragments use the requested Vernac line's verified generated Java scope. */
public class VernacCodeFragmentFactory extends CodeFragmentFactory {
    static PsiElement context(PsiElement element) {
        if (element == null || element.getContainingFile() == null) return null;
        var source = element.getContainingFile();
        // Keep an already supplied Java context, including debugger-added synthetic variables.
        if (source instanceof PsiJavaFile || source instanceof JavaCodeFragment) return element;
        if (source.getFileType() != VernacFileType.INSTANCE) return null;
        var file = source.getVirtualFile();
        var document = PsiDocumentManager.getInstance(element.getProject()).getDocument(source);
        if (file == null || !file.isInLocalFileSystem() || document == null) return null;
        int offset = element.getTextRange().getStartOffset();
        if (offset < 0 || offset >= document.getTextLength()) return null;
        var entries =
                VernacDebugSources.at(
                        element.getProject(), file.toNioPath(), document.getLineNumber(offset) + 1);
        if (entries.size() != 1) return null;
        var position = VernacEvaluationContext.position(element.getProject(), entries.getFirst());
        return position == null ? null : position.getElementAt();
    }

    @Override
    public boolean isContextAccepted(PsiElement element) {
        // Do not intercept normal Java files or guess the selected frame from global UI state.
        return element != null
                && element.getContainingFile() != null
                && element.getContainingFile().getFileType() == VernacFileType.INSTANCE
                && context(element) != null;
    }

    @Override
    public LanguageFileType getFileType() {
        return DefaultCodeFragmentFactory.getInstance().getFileType();
    }

    @Override
    public EvaluatorBuilder getEvaluatorBuilder() {
        return DefaultCodeFragmentFactory.getInstance().getEvaluatorBuilder();
    }

    @Override
    public PsiCodeFragment createPsiCodeFragment(
            TextWithImports text, PsiElement element, Project project) {
        return DefaultCodeFragmentFactory.getInstance()
                .createPsiCodeFragment(text, context(element), project);
    }

    @Override
    public PsiCodeFragment createPresentationPsiCodeFragment(
            TextWithImports text, PsiElement element, Project project) {
        return DefaultCodeFragmentFactory.getInstance()
                .createPresentationPsiCodeFragment(text, context(element), project);
    }
}
