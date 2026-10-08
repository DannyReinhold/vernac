// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Keep LSP semantic colors when a Vernac file has its own PSI parser. */
final class VernacLspCustomization extends LspCustomization {
    private final LspSemanticTokensSupport semanticTokens = new LspSemanticTokensSupport() {
        @Override public boolean shouldAskServerForSemanticTokens(@NotNull PsiFile file) {
            // The platform default only opts in TEXT/textmate, not custom PSI languages.
            // Injected Java files keep their native Java highlighting.
            return file.getLanguage() == VernacLanguage.INSTANCE;
        }
    };

    @Override public @NotNull LspSemanticTokensSupport getSemanticTokensCustomizer() {
        return semanticTokens;
    }
}
