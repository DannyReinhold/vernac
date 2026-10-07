// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.ide.fileTemplates.DefaultCreateFromTemplateHandler;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.psi.PsiDirectory;

/** These templates require the namespace-aware Vernac action, not the generic template dialog. */
public final class VernacFileTemplateHandler extends DefaultCreateFromTemplateHandler {
    @Override
    public boolean handlesTemplate(FileTemplate template) {
        return "vernac".equals(template.getExtension())
                && ("Vernac File".equals(template.getName())
                    || "Vernac Getting Started".equals(template.getName()));
    }

    @Override
    public boolean canCreate(PsiDirectory[] directories) {
        // FileTemplateUtil uses this for generic menu availability, not direct creation.
        return false;
    }
}
