// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.SourcePosition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiManager;

final class VernacEvaluationContext {
    private VernacEvaluationContext() {}

    static SourcePosition position(Project project, VernacDebugSources.Bound bound) {
        if (!VernacDebugSources.current(bound)) return null;
        var file = VirtualFileManager.getInstance().findFileByNioPath(bound.java());
        var psi = file == null ? null : PsiManager.getInstance(project).findFile(file);
        return psi == null
                ? null
                : SourcePosition.createFromLine(psi, bound.entry().javaLine() - 1);
    }
}
