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
    @Override public boolean canNavigate() { return isValid() && file.getViewProvider().getVirtualFile() != null; }
    @Override public boolean isEquivalentTo(PsiElement other) {
        return other instanceof VernacTypeNavigationTarget target && name.equals(target.name)
                && range.equals(target.range)
                && file.getViewProvider().getVirtualFile().equals(target.file.getViewProvider().getVirtualFile());
    }
    @Override public PsiElement setName(String name) {
        throw new com.intellij.util.IncorrectOperationException("Cross-language Vernac rename is not supported yet.");
    }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
    @Override public void navigate(boolean requestFocus) {
        if (canNavigate()) new OpenFileDescriptor(file.getProject(), file.getViewProvider().getVirtualFile(), getTextOffset()).navigate(requestFocus);
    }
}
