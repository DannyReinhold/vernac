// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;

/** Navigation-only declaration: the lightweight host PSI intentionally has no type nodes. */
final class VernacTypeNavigationTarget extends FakePsiElement {
    private final PsiFile file;
    private final String name;
    private final TextRange range;

    VernacTypeNavigationTarget(PsiFile file, String name, int start, int end) {
        this.file = file;
        this.name = name;
        this.range = new TextRange(start, end);
    }

    @Override public @NotNull PsiElement getParent() { return file; }
    @Override public @NotNull PsiFile getContainingFile() { return file; }
    @Override public String getName() { return name; }
    @Override public @NotNull TextRange getTextRange() { return range; }
    @Override public int getStartOffsetInParent() { return range.getStartOffset(); }
    @Override public int getTextLength() { return range.getLength(); }
    @Override public int getTextOffset() { return range.getStartOffset(); }
    @Override public String getText() { return range.substring(file.getText()); }
    @Override public boolean isValid() { return file.isValid() && range.getEndOffset() <= file.getTextLength(); }
    @Override public boolean canNavigate() { return isValid() && file.getVirtualFile() != null; }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
    @Override public void navigate(boolean requestFocus) {
        if (canNavigate()) new OpenFileDescriptor(file.getProject(), file.getVirtualFile(), getTextOffset()).navigate(requestFocus);
    }
}
