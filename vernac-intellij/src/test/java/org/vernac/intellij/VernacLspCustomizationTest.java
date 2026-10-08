// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.psi.PsiFile;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class VernacLspCustomizationTest {
    @Test void semanticTokensRemainEnabledForCustomVernacPsi() {
        var support = new VernacLspCustomization().getSemanticTokensCustomizer();
        assertTrue(support.shouldAskServerForSemanticTokens(file(VernacLanguage.INSTANCE)));
    }

    @Test void semanticTokensAreNotRequestedForOtherLanguages() {
        var support = new VernacLspCustomization().getSemanticTokensCustomizer();
        assertFalse(support.shouldAskServerForSemanticTokens(file(PlainTextLanguage.INSTANCE)));
        assertFalse(support.shouldAskServerForSemanticTokens(file(Language.ANY)));
    }

    private PsiFile file(Language language) {
        return (PsiFile) Proxy.newProxyInstance(PsiFile.class.getClassLoader(), new Class<?>[]{PsiFile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLanguage")) return language;
                    throw new AssertionError("Unexpected PSI access: " + method.getName());
                });
    }
}
