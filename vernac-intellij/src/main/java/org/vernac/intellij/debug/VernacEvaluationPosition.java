// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.SourcePosition;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/** Navigation uses Vernac; Java evaluation uses the corresponding generated method scope. */
final class VernacEvaluationPosition extends SourcePosition {
    private final SourcePosition source;
    private final SourcePosition java;

    VernacEvaluationPosition(SourcePosition source, SourcePosition java) {
        this.source = source;
        this.java = java;
    }

    @Override
    public PsiFile getFile() {
        return source.getFile();
    }

    @Override
    public PsiElement getElementAt() {
        return java.getElementAt();
    }

    @Override
    public int getLine() {
        return source.getLine();
    }

    @Override
    public int getOffset() {
        return source.getOffset();
    }

    @Override
    public Editor openEditor(boolean focus) {
        return source.openEditor(focus);
    }

    @Override
    public void navigate(boolean focus) {
        source.navigate(focus);
    }

    @Override
    public boolean canNavigate() {
        return source.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return source.canNavigateToSource();
    }
}
