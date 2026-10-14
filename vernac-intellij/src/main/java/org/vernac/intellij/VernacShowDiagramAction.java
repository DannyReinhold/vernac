// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;

import org.jetbrains.annotations.NotNull;
import org.vernac.compiler.diagram.DomainDiagrams;
import org.vernac.compiler.modules.ModuleArtifacts;
import org.vernac.compiler.pipeline.*;

import java.nio.file.*;
import java.util.*;

/** Opens a generated namespace view; rendering belongs to the IDE's Markdown extension. */
public final class VernacShowDiagramAction extends AnAction implements DumbAware {
    public VernacShowDiagramAction() {
        super(
                "Show Domain Diagram",
                "Export the current Vernac namespace and open its Markdown diagram",
                VernacFileType.INSTANCE.getIcon());
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent event) {
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        event.getPresentation()
                .setEnabledAndVisible(
                        event.getProject() != null
                                && file != null
                                && file.isInLocalFileSystem()
                                && (file.isDirectory() || file.getName().endsWith(".vernac"))
                                && DiagramSelection.from(file.toNioPath()).isPresent());
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) return;
        var selection = DiagramSelection.from(file.toNioPath());
        if (selection.isEmpty()) return;
        var selected = selection.get();
        // Capture unsaved source documents under a short read action; do not save user files.
        Map<Path, String> snapshots =
                ReadAction.compute(
                        () -> {
                            Map<Path, String> result = new HashMap<>();
                            var manager = FileDocumentManager.getInstance();
                            for (var document : manager.getUnsavedDocuments()) {
                                var source = manager.getFile(document);
                                if (source != null
                                        && source.isInLocalFileSystem()
                                        && source.getName().endsWith(".vernac")) {
                                    var path = source.toNioPath().toAbsolutePath().normalize();
                                    if (path.startsWith(selected.sourceRoot()))
                                        result.put(path, document.getText());
                                }
                            }
                            return Map.copyOf(result);
                        });
        ProgressManager.getInstance()
                .run(
                        new Task.Backgroundable(project, "Vernac domain diagram", true) {
                            @Override
                            public void run(@NotNull ProgressIndicator indicator) {
                                try {
                                    indicator.checkCanceled();
                                    var model =
                                            new VernacProjectLoader()
                                                    .load(selected.sourceRoot(), snapshots);
                                    var resolved =
                                            new ProjectTypeResolver()
                                                    .resolve(
                                                            ModuleArtifacts.merge(
                                                                    model,
                                                                    ModuleArtifacts.editorModules(
                                                                            selected
                                                                                    .sourceRoot())));
                                    indicator.checkCanceled();
                                    var page =
                                            DomainDiagrams.write(
                                                    resolved,
                                                    selected.namespace(),
                                                    selected.projectRoot()
                                                            .resolve("target/vernac-diagrams"));
                                    indicator.checkCanceled();
                                    ApplicationManager.getApplication()
                                            .invokeLater(
                                                    () -> {
                                                        if (project.isDisposed()) return;
                                                        var generated =
                                                                LocalFileSystem.getInstance()
                                                                        .refreshAndFindFileByPath(
                                                                                page.toString());
                                                        if (generated != null)
                                                            FileEditorManager.getInstance(project)
                                                                    .openFile(generated, true);
                                                    });
                                } catch (com.intellij.openapi.progress.ProcessCanceledException e) {
                                    throw e;
                                } catch (Exception e) {
                                    ApplicationManager.getApplication()
                                            .invokeLater(
                                                    () -> {
                                                        if (!project.isDisposed())
                                                            Messages.showErrorDialog(
                                                                    project,
                                                                    "Could not generate the"
                                                                        + " diagram: "
                                                                            + e.getMessage()
                                                                            + "\n"
                                                                            + "Build the Maven"
                                                                            + " project after"
                                                                            + " changing"
                                                                            + " dependencies. The"
                                                                            + " action uses its"
                                                                            + " resolved dependency"
                                                                            + " index.",
                                                                    "Vernac Domain Diagram");
                                                    });
                                }
                            }
                        });
    }
}
