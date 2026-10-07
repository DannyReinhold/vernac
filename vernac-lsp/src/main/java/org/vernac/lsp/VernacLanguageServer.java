// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VernacLanguageServer implements LanguageServer, LanguageClientAware {

    // Supported token types (index in this list determines the token type)
    public static final List<String> TOKEN_TYPES = List.of(
            SemanticTokenTypes.Keyword,     // 0
            SemanticTokenTypes.Type,        // 1
            SemanticTokenTypes.Variable,    // 2
            SemanticTokenTypes.String,      // 3
            SemanticTokenTypes.Number,      // 4
            SemanticTokenTypes.Comment,     // 5
            SemanticTokenTypes.Function,    // 6
            SemanticTokenTypes.EnumMember   // 7
    );
    public static final List<String> TOKEN_MODIFIERS = List.of(
            SemanticTokenModifiers.Declaration,
            SemanticTokenModifiers.Definition
    );
    private final VernacProjectDiagnostics projects = new VernacProjectDiagnostics();
    private final VernacTextDocumentService documentService = new VernacTextDocumentService(projects);
    private final VernacWorkspaceService workspaceService = new VernacWorkspaceService(projects);
    private LanguageClient client;
    private boolean canRegisterFileWatcher;

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        projects.initialize(params);
        var workspace = params.getCapabilities() == null ? null : params.getCapabilities().getWorkspace();
        canRegisterFileWatcher = workspace != null && workspace.getDidChangeWatchedFiles() != null
                && Boolean.TRUE.equals(workspace.getDidChangeWatchedFiles().getDynamicRegistration());
        ServerCapabilities capabilities = new ServerCapabilities();
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.Full);
        sync.setSave(true);
        capabilities.setTextDocumentSync(sync);
        WorkspaceFoldersOptions folders = new WorkspaceFoldersOptions();
        folders.setSupported(true);
        folders.setChangeNotifications(true);
        WorkspaceServerCapabilities workspaceCapabilities = new WorkspaceServerCapabilities();
        workspaceCapabilities.setWorkspaceFolders(folders);
        capabilities.setWorkspace(workspaceCapabilities);
        capabilities.setCompletionProvider(new CompletionOptions(true, List.of(".", ":", "[")));
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);

        // Semantic Tokens (Syntax Highlighting)
        SemanticTokensLegend legend = new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);
        SemanticTokensWithRegistrationOptions semanticTokensOptions = new SemanticTokensWithRegistrationOptions();
        semanticTokensOptions.setLegend(legend);
        semanticTokensOptions.setFull(true);
        capabilities.setSemanticTokensProvider(semanticTokensOptions);

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public void initialized(InitializedParams params) {
        if (canRegisterFileWatcher && client != null) {
            FileSystemWatcher watcher = new FileSystemWatcher();
            watcher.setGlobPattern(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("**/*.vernac"));
            var options = new DidChangeWatchedFilesRegistrationOptions(List.of(watcher));
            client.registerCapability(new RegistrationParams(List.of(
                    new Registration("vernac-source-files", "workspace/didChangeWatchedFiles", options))))
                    .exceptionally(error -> {
                        client.logMessage(new MessageParams(MessageType.Warning,
                                "Vernac file watcher registration failed; editor events still refresh diagnostics: " + error));
                        return null;
                    });
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return documentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        this.documentService.setClient(client);
    }
}