// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.AbstractElementManipulator;
import org.jetbrains.annotations.NotNull;

/** Applies edits from Java completion/the injected editor without touching surrounding DSL text. */
public final class VernacHostManipulator extends AbstractElementManipulator<VernacInjectionHost> {
    @Override public VernacInjectionHost handleContentChange(@NotNull VernacInjectionHost host,
            @NotNull TextRange range, String replacement) {
        String text = host.getText();
        return (VernacInjectionHost) host.updateText(text.substring(0, range.getStartOffset())
                + replacement + text.substring(range.getEndOffset()));
    }
}
