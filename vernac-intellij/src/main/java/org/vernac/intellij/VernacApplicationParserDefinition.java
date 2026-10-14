// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.extapi.psi.*;
import com.intellij.lang.*;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.*;
import org.jetbrains.annotations.NotNull;

/** Lightweight application PSI; composition diagnostics are shared by compiler and LSP. */
public final class VernacApplicationParserDefinition implements ParserDefinition {
    private static final IFileElementType FILE=new IFileElementType(VernacApplicationLanguage.INSTANCE);
    @Override public @NotNull Lexer createLexer(Project project) {return new VernacParserDefinition().createLexer(project);}
    @Override public @NotNull PsiParser createParser(Project project) {return new VernacParserDefinition().createParser(project);}
    @Override public @NotNull IFileElementType getFileNodeType() {return FILE;}
    @Override public @NotNull TokenSet getCommentTokens() {return TokenSet.EMPTY;}
    @Override public @NotNull TokenSet getStringLiteralElements() {return TokenSet.EMPTY;}
    @Override public @NotNull PsiElement createElement(ASTNode node) {return new ASTWrapperPsiElement(node);}
    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider provider) {
        return new PsiFileBase(provider,VernacApplicationLanguage.INSTANCE) {
            @Override public @NotNull FileType getFileType() {return VernacApplicationFileType.INSTANCE;}
        };
    }
}
