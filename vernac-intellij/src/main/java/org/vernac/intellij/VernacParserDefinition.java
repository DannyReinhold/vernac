// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.*;
import com.intellij.lexer.Lexer;
import com.intellij.lexer.LexerBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.*;
import org.jetbrains.annotations.NotNull;

/** Lightweight host PSI. Vernac semantic analysis remains owned by the language server. */
public final class VernacParserDefinition implements ParserDefinition {
    static final IFileElementType FILE = new IFileElementType(VernacLanguage.INSTANCE);
    static final IElementType HOST = new IElementType("VERNAC_INJECTION_HOST", VernacLanguage.INSTANCE);
    private static final IElementType TEXT = new IElementType("VERNAC_TEXT", VernacLanguage.INSTANCE);

    @Override public @NotNull Lexer createLexer(Project project) { return new HostLexer(); }
    @Override public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            var file = builder.mark();
            var host = builder.mark();
            while (!builder.eof()) builder.advanceLexer();
            host.done(HOST);
            file.done(root);
            return builder.getTreeBuilt();
        };
    }
    @Override public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override public @NotNull TokenSet getCommentTokens() { return TokenSet.EMPTY; }
    @Override public @NotNull TokenSet getStringLiteralElements() { return TokenSet.EMPTY; }
    @Override public @NotNull PsiElement createElement(ASTNode node) { return new VernacInjectionHost(node); }
    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider provider) {
        return new PsiFileBase(provider, VernacLanguage.INSTANCE) {
            @Override public @NotNull FileType getFileType() { return VernacFileType.INSTANCE; }
        };
    }

    private static final class HostLexer extends LexerBase {
        private CharSequence buffer = "";
        private int start, end;
        @Override public void start(@NotNull CharSequence text, int startOffset, int endOffset, int initialState) {
            buffer = text; start = startOffset; end = endOffset;
        }
        @Override public int getState() { return 0; }
        @Override public IElementType getTokenType() { return start < end ? TEXT : null; }
        @Override public int getTokenStart() { return start; }
        @Override public int getTokenEnd() { return end; }
        @Override public void advance() { start = end; }
        @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
        @Override public int getBufferEnd() { return end; }
    }
}
