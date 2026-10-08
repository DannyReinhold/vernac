package org.vernac.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.extensions.PluginAware;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor;
import com.intellij.platform.lsp.api.customization.LspCustomization;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VernacLspIntegrationProvider
        implements LspIntegrationProvider, PluginAware {
    private Path pluginPath;


    @Override
    public void setPluginDescriptor(PluginDescriptor pluginDescriptor) {
        this.pluginPath = pluginDescriptor.getPluginPath();
    }

    @Override
    public void fileOpened(
            Project project,
            VirtualFile file,
            LspClientStarter clientStarter
    ) {
        boolean supported = file.getFileType() == VernacFileType.INSTANCE;

        if (supported) {
            clientStarter.ensureClientStarted(
                    new VernacLspClientDescriptor(project, pluginPath)
            );
        }
    }

    private static final class VernacLspClientDescriptor
            extends ProjectWideLspClientDescriptor {
        private final Path pluginPath;

        private VernacLspClientDescriptor(Project project, Path pluginPath) {
            super(project, "Vernac");
            this.pluginPath = pluginPath;
        }

        private final LspCustomization customization = new VernacLspCustomization();

        @Override
        public LspCustomization getLspCustomization() {
            return customization;
        }

        private Path locateServerJar() throws ExecutionException {
            if (pluginPath == null) {
                throw new ExecutionException(
                        "Vernac plugin installation path is unavailable"
                );
            }

            Path serverJar = pluginPath.resolve("server/vernac-lsp.jar");

            if (!Files.isRegularFile(serverJar)) {
                throw new ExecutionException(
                        "Vernac language server not found: " + serverJar
                );
            }

            return serverJar;
        }

        @Override
        public boolean isSupportedFile(VirtualFile file) {
            return file.getFileType() == VernacFileType.INSTANCE;
        }

        @Override
        public String getLanguageId(VirtualFile file) {
            return "vernac";
        }

        @Override
        public GeneralCommandLine createCommandLine()
                throws ExecutionException {
            Path serverJar = locateServerJar();

            String executable = File.separatorChar == '\\'
                    ? "java.exe"
                    : "java";

            Path java = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    executable
            );

            return new GeneralCommandLine(
                    java.toString(),
                    "-Dfile.encoding=UTF-8",
                    "-jar",
                    serverJar.toString()
            ).withCharset(StandardCharsets.UTF_8);
        }
    }
}