package org.vernac.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.nio.file.Path;
import java.util.List;

public final class VernacProjectOpenActivity implements ProjectActivity {

    private static final Logger LOG =
            Logger.getInstance(VernacProjectOpenActivity.class);

    private static final Key<Path> INITIAL_MODEL =
            Key.create("org.vernac.initialModel");

    public static void openAfterProjectCreation(
            Project project,
            Path modelPath
    ) {
        project.putUserData(INITIAL_MODEL, modelPath);
    }

    @Override
    public Object execute(
            Project project,
            Continuation<? super Unit> continuation
    ) {
        Path modelPath = project.getUserData(INITIAL_MODEL);

        if (modelPath == null) {
            return Unit.INSTANCE;
        }

        project.putUserData(INITIAL_MODEL, null);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }

            // Model location: <project>/src/main/vernac/tasks.vernac
            Path projectDirectory = modelPath.getParent()
                    .getParent()
                    .getParent()
                    .getParent();

            VirtualFile pomFile = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(
                            projectDirectory.resolve("pom.xml")
                    );

            if (pomFile == null) {
                LOG.warn("Could not find generated Maven POM: "
                        + projectDirectory);
                return;
            }

            MavenProjectsManager.getInstance(project)
                    .addManagedFiles(List.of(pomFile));

            VirtualFile modelFile = LocalFileSystem.getInstance()
                    .refreshAndFindFileByNioFile(modelPath);

            if (modelFile != null) {
                FileEditorManager.getInstance(project)
                        .openFile(modelFile, true);
            } else {
                LOG.warn("Could not open initial Vernac model: " + modelPath);
            }
        });

        return Unit.INSTANCE;
    }
}