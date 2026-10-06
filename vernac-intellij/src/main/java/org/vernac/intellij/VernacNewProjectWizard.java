// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.intellij;

import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.ide.wizard.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.builder.Panel;
import kotlin.Unit;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;

import javax.lang.model.SourceVersion;
import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

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
        private final JBTextField artifactIdField = new JBTextField();
        private final JBTextField groupIdField = new JBTextField("org.example");
        private final JBTextField packageField = new JBTextField("org.example.tasks");
        private final JComboBox<Sdk> jdkField = new JComboBox<>();
        private String lastSuggestedArtifactId;

        private TemplateStep(NewProjectWizardBaseStep baseStep) {
            super(baseStep);
            this.baseStep = baseStep;

            lastSuggestedArtifactId =
                    VernacProjectTemplate.suggestArtifactId(baseStep.getName());

            artifactIdField.setText(lastSuggestedArtifactId);
            artifactIdField.setColumns(30);
            groupIdField.setColumns(30);
            packageField.setColumns(30);

            baseStep.getNameProperty().afterChange(name -> {
                String suggestion =
                        VernacProjectTemplate.suggestArtifactId(name);

                if (artifactIdField.getText().equals(lastSuggestedArtifactId)) {
                    artifactIdField.setText(suggestion);
                }

                lastSuggestedArtifactId = suggestion;
                return Unit.INSTANCE;
            });

            configureJdkSelection();
        }

        private static boolean isSupportedJdk(Sdk sdk) {
            return sdk != null
                    && sdk.getSdkType() instanceof JavaSdk
                    && JavaSdk.getInstance().isOfVersionOrHigher(
                    sdk, JavaSdkVersion.JDK_21
            );
        }

        private static ValidationInfo validateArtifactId(JBTextField field) {
            String value = field.getText().strip();

            if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                return new ValidationInfo(
                        "Start with a letter or digit; "
                                + "use letters, digits, dots, hyphens or underscores.",
                        field
                );
            }

            return null;
        }

        private static ValidationInfo validateGroupId(JBTextField field) {
            String value = field.getText().strip();

            if (!value.matches(
                    "[A-Za-z0-9_][A-Za-z0-9_-]*"
                            + "(\\.[A-Za-z0-9_][A-Za-z0-9_-]*)*"
            )) {
                return new ValidationInfo(
                        "Use non-empty dot-separated segments "
                                + "containing letters, digits, underscores or hyphens.",
                        field
                );
            }

            return null;
        }

        private static ValidationInfo validatePackage(JBTextField field) {
            String value = field.getText().strip();

            if (!SourceVersion.isName(value, SourceVersion.RELEASE_21)) {
                return new ValidationInfo(
                        "Enter a valid Java package name without Java keywords.",
                        field
                );
            }

            return null;
        }

        private void configureJdkSelection() {
            jdkField.setRenderer(new DefaultListCellRenderer() {
                @Override
                public java.awt.Component getListCellRendererComponent(
                        JList<?> list,
                        Object value,
                        int index,
                        boolean isSelected,
                        boolean cellHasFocus
                ) {
                    super.getListCellRendererComponent(
                            list, value, index, isSelected, cellHasFocus
                    );

                    if (value instanceof Sdk sdk) {
                        setText(sdk.getName() + " — " + sdk.getVersionString());
                    } else {
                        setText("Select a registered JDK (Java 21 or newer)");
                    }

                    return this;
                }
            });

            for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
                if (isSupportedJdk(sdk)) {
                    jdkField.addItem(sdk);
                }
            }

            Sdk preferred = getContext().getProjectJdk();

            if (isSupportedJdk(preferred)) {
                jdkField.setSelectedItem(preferred);
            }
        }

        private ValidationInfo validateJdk() {
            if (!isSupportedJdk((Sdk) jdkField.getSelectedItem())) {
                return new ValidationInfo(
                        "Select a JDK with Java 21 or newer. "
                                + "Register one in Project Structure → SDKs if needed.",
                        jdkField
                );
            }

            return null;
        }

        @Override
        public void setupUI(Panel builder) {
            builder.row("GroupId:", row -> {
                row.cell(groupIdField)
                        .validationOnInput((validation, field) -> validateGroupId(field))
                        .validationOnApply((validation, field) -> validateGroupId(field));

                return Unit.INSTANCE;
            });

            builder.row("ArtifactId:", row -> {
                row.cell(artifactIdField)
                        .validationOnInput((validation, field) -> validateArtifactId(field))
                        .validationOnApply((validation, field) -> validateArtifactId(field));

                return Unit.INSTANCE;
            });

            builder.row("Java package:", row -> {
                row.cell(packageField)
                        .validationOnInput((validation, field) -> validatePackage(field))
                        .validationOnApply((validation, field) -> validatePackage(field));

                return Unit.INSTANCE;
            });

            builder.row("JDK:", row -> {
                row.cell(jdkField)
                        .validationOnInput((validation, field) -> validateJdk())
                        .validationOnApply((validation, field) -> validateJdk());

                return Unit.INSTANCE;
            });
        }

        @Override
        public void setupProject(Project project) {
            Sdk selectedJdk = (Sdk) jdkField.getSelectedItem();

            if (!isSupportedJdk(selectedJdk)) {
                throw new IllegalStateException(
                        "A JDK with Java 21 or newer must be selected."
                );
            }
            Path projectDirectory = Path.of(baseStep.getPath()).resolve(baseStep.getName());

            try {
                VernacProjectTemplate.copyTo(
                        projectDirectory,
                        baseStep.getName(),
                        groupIdField.getText().strip(),
                        artifactIdField.getText().strip(),
                        packageField.getText().strip()
                );

                Path pomPath = projectDirectory.resolve("pom.xml");
                VirtualFile pomFile = LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(pomPath);

                if (pomFile == null) {
                    throw new IOException(
                            "Created pom.xml could not be found by IntelliJ: "
                                    + pomPath
                    );
                }

                WriteAction.run(() ->
                        ProjectRootManager.getInstance(project)
                                .setProjectSdk(selectedJdk)
                );

                MavenRunner.getInstance(project)
                        .getSettings()
                        .setJreName(MavenRunnerSettings.USE_PROJECT_JDK);

                VernacProjectOpenActivity.openAfterProjectCreation(
                        project,
                        projectDirectory.resolve("src/main/vernac/tasks.vernac")
                );

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