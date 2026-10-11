// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

public final class VernacInjectionHost extends ASTWrapperPsiElement implements PsiLanguageInjectionHost {
    public VernacInjectionHost(@NotNull ASTNode node) { super(node); }
    @Override public boolean isValidHost() { return true; }
    @Override public @NotNull PsiLanguageInjectionHost updateText(@NotNull String text) {
        var replacement = PsiFileFactory.getInstance(getProject()).createFileFromText(
                "behavior.vernac", VernacFileType.INSTANCE, text);
        return (PsiLanguageInjectionHost) replace(replacement.getFirstChild());
    }
    @Override public @NotNull LiteralTextEscaper<VernacInjectionHost> createLiteralTextEscaper() {
        return new LiteralTextEscaper<>(this) {
            @Override public boolean decode(@NotNull TextRange range, @NotNull StringBuilder out) {
                out.append(range.substring(myHost.getText()));
                return true;
            }
            @Override public int getOffsetInHost(int offset, @NotNull TextRange range) {
                return offset >= 0 && offset <= range.getLength() ? range.getStartOffset() + offset : -1;
            }
            @Override public boolean isOneLine() { return false; }
        };
    }
}
