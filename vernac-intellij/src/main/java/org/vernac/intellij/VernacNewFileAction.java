// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.ide.IdeView;
import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class VernacNewFileAction extends CreateFileFromTemplateAction implements DumbAware {
    public VernacNewFileAction() {
        super("Vernac File", "Create a Vernac model in the selected namespace", VernacFileType.INSTANCE.getIcon());
    }

    @Override
    protected boolean isAvailable(DataContext context) {
        if (!super.isAvailable(context)) return false;
        IdeView view = context.getData(LangDataKeys.IDE_VIEW);
        return view != null && Arrays.stream(view.getDirectories()).anyMatch(directory -> {
            try { namespace(directory); return true; }
            catch (IllegalArgumentException e) { return false; }
        });
    }

    @Override
    protected void buildDialog(Project project, PsiDirectory directory, CreateFileFromTemplateDialog.Builder builder) {
        builder.setTitle("New Vernac File")
                .addKind("Empty Model", VernacFileType.INSTANCE.getIcon(), "Vernac File")
                .addKind("Getting Started", VernacFileType.INSTANCE.getIcon(), "Vernac Getting Started");
    }

    @Override
    protected String getActionName(PsiDirectory directory, String newName, String templateName) {
        return "Create Vernac File";
    }

    @Override
    protected PsiFile createFile(String name, String templateName, PsiDirectory directory) {
        try {
            String stem = VernacNamespaces.fileStem(name);
            String namespace = namespace(directory);
            var template = FileTemplateManager.getInstance(directory.getProject()).getInternalTemplate(templateName);
            return createFileFromTemplate(stem, template, directory, null, true, Map.of(),
                    Map.of("NAMESPACE", namespace));
        } catch (IllegalArgumentException e) {
            throw new IncorrectOperationException(e.getMessage(), e);
        }
    }

    private static String namespace(PsiDirectory directory) {
        Path path = directory.getVirtualFile().toNioPath();
        Path root = VernacNamespaces.sourceRoot(path).orElseThrow(() ->
                new IllegalArgumentException("Select a namespace below src/main/vernac."));
        return VernacNamespaces.namespace(root, path);
    }
}
