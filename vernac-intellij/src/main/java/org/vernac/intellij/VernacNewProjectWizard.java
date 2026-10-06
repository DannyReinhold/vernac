// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.intellij;

import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.ide.wizard.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

public final class VernacNewProjectWizard
        implements GeneratorNewProjectWizard {

    @Override
    public String getId() {
        return "org.vernac.intellij.project";
    }

    @Override
    public String getName() {
        return "Vernac";
    }

    @Override
    public Icon getIcon() {
        return VernacFileType.INSTANCE.getIcon();
    }

    @Override
    public String getDescription() {
        return "Create a Maven project with a Vernac domain model "
                + "and an executable example test.";
    }

    @Override
    public NewProjectWizardStep createStep(WizardContext context) {
        return new NewProjectWizardChainStep<>(
                new RootNewProjectWizardStep(context)
        )
                .nextStep(NewProjectWizardBaseStep::new)
                .nextStep(TemplateStep::new);
    }

    private static final class TemplateStep
            extends AbstractNewProjectWizardStep {

        private final NewProjectWizardBaseStep baseStep;

        private TemplateStep(NewProjectWizardBaseStep baseStep) {
            super(baseStep);
            this.baseStep = baseStep;
        }

        @Override
        public void setupProject(Project project) {
            Path projectDirectory = Path.of(baseStep.getPath())
                    .resolve(baseStep.getName());

            try {
                VernacProjectTemplate.copyTo(projectDirectory, baseStep.getName());

                Path pomPath = projectDirectory.resolve("pom.xml");
                VirtualFile pomFile = LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(pomPath);

                if (pomFile == null) {
                    throw new IOException(
                            "Created pom.xml could not be found by IntelliJ: "
                                    + pomPath
                    );
                }

                MavenProjectsManager.getInstance(project)
                        .addManagedFiles(List.of(pomFile));

            } catch (IOException exception) {
                throw new UncheckedIOException(
                        "Could not create the Vernac project at "
                                + projectDirectory,
                        exception
                );
            }
        }
    }
}