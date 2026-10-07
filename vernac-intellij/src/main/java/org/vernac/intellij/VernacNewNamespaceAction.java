// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDirectory;
import com.intellij.util.IncorrectOperationException;

import java.nio.file.Path;
import java.util.Arrays;

public final class VernacNewNamespaceAction extends AnAction implements DumbAware {
    @Override
    public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(AnActionEvent event) {
        IdeView view = event.getData(LangDataKeys.IDE_VIEW);
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && view != null
                && Arrays.stream(view.getDirectories()).anyMatch(directory ->
                VernacNamespaces.sourceRoot(directory.getVirtualFile().toNioPath()).isPresent()));
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        IdeView view = event.getData(LangDataKeys.IDE_VIEW);
        if (project == null || view == null) return;
        PsiDirectory selected = view.getOrChooseDirectory();
        if (selected == null) return;
        Path selectedPath = selected.getVirtualFile().toNioPath();
        Path root = VernacNamespaces.sourceRoot(selectedPath).orElse(null);
        if (root == null) return;
        PsiDirectory rootDirectory = selected;
        while (!rootDirectory.getVirtualFile().toNioPath().equals(root)) {
            rootDirectory = rootDirectory.getParentDirectory();
            if (rootDirectory == null) return;
        }
        String prefix;
        try { prefix = VernacNamespaces.namespace(root, selectedPath) + "."; }
        catch (IllegalArgumentException e) { prefix = ""; }
        String name = Messages.showInputDialog(project, "Fully qualified Vernac namespace:",
                "New Vernac Namespace", Messages.getQuestionIcon(), prefix, new InputValidatorEx() {
                    @Override public String getErrorText(String input) {
                        try { VernacNamespaces.requireNamespace(input); return null; }
                        catch (IllegalArgumentException e) { return e.getMessage(); }
                    }
                    @Override public boolean checkInput(String input) { return getErrorText(input) == null; }
                    @Override public boolean canClose(String input) { return checkInput(input); }
                });
        if (name == null) return;
        PsiDirectory base = rootDirectory;
        try {
            VernacNamespaces.requireNamespace(name);
            PsiDirectory created = WriteCommandAction.writeCommandAction(project)
                    .withName("Create Vernac Namespace").compute(() -> {
                        PsiDirectory current = base;
                        for (String segment : name.split("\\.")) {
                            PsiDirectory child = current.findSubdirectory(segment);
                            current = child == null ? current.createSubdirectory(segment) : child;
                        }
                        return current;
                    });
            view.selectElement(created);
        } catch (IncorrectOperationException | IllegalArgumentException e) {
            Messages.showErrorDialog(project, e.getMessage(), "Could Not Create Vernac Namespace");
        }
    }
}
