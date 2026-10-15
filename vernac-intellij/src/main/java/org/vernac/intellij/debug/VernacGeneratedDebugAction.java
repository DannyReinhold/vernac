// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;

public final class VernacGeneratedDebugAction extends ToggleAction implements DumbAware {
    public VernacGeneratedDebugAction() {
        super(
                "Show Generated Java in Debugger",
                "Prefer generated Java source positions for contributor debugging",
                null);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public boolean isSelected(AnActionEvent event) {
        return event.getProject() != null
                && VernacDebugSettings.get(event.getProject()).generatedJava;
    }

    @Override
    public void setSelected(AnActionEvent event, boolean selected) {
        if (event.getProject() != null)
            VernacDebugSettings.get(event.getProject()).generatedJava = selected;
    }

    @Override
    public void update(AnActionEvent event) {
        super.update(event);
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
