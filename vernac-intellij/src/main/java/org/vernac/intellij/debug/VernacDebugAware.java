// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.engine.JavaDebugAware;
import com.intellij.psi.PsiFile;

import org.vernac.intellij.VernacFileType;

public final class VernacDebugAware extends JavaDebugAware {
    @Override
    public boolean isBreakpointAware(PsiFile file) {
        return file.getFileType() == VernacFileType.INSTANCE;
    }
}
