// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.WorkspaceService;

public class VernacWorkspaceService implements WorkspaceService {
    private final VernacProjectDiagnostics projects;

    VernacWorkspaceService(VernacProjectDiagnostics projects) { this.projects = projects; }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) { }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { projects.refresh(); }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) { projects.foldersChanged(params); }
}
