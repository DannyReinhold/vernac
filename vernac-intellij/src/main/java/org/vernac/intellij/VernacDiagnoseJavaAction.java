// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

/** On-demand diagnostic report; no generation, compilation, saving or automatic upload. */
public final class VernacDiagnoseJavaAction extends AnAction {
    public VernacDiagnoseJavaAction() {
        super("Diagnose Embedded Java Context",
                "Inspect live type publication and native Java scopes at the caret", null);
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override public void update(@NotNull AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.HOST_EDITOR);
        event.getPresentation().setEnabled(event.getProject() != null && editor != null);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var editor = event.getData(CommonDataKeys.HOST_EDITOR);
        if (project == null || editor == null) return;
        var document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        ReadAction.nonBlocking(() -> {
                    var host = PsiDocumentManager.getInstance(project).getPsiFile(document);
                    if (host == null || !host.getLanguage().is(VernacLanguage.INSTANCE))
                        return "Open the .vernac editor and place the caret inside the affected Java body.";
                    return VernacJavaContextDiagnostics.report(project, host, offset);
                })
                .withDocumentsCommitted(project)
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), report -> {
                    var file = new LightVirtualFile("vernac-java-context.txt", report);
                    file.setWritable(false);
                    FileEditorManager.getInstance(project).openFile(file, true);
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }
}
