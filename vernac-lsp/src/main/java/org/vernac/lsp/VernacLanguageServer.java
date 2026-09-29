package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VernacLanguageServer implements LanguageServer, LanguageClientAware {

    // Unterstützte Token-Typen (Index in dieser Liste bestimmt den Token-Typ)
    public static final List<String> TOKEN_TYPES = List.of(
            SemanticTokenTypes.Keyword,     // 0
            SemanticTokenTypes.Type,        // 1
            SemanticTokenTypes.Variable,    // 2
            SemanticTokenTypes.String,      // 3
            SemanticTokenTypes.Number,      // 4
            SemanticTokenTypes.Comment,     // 5
            SemanticTokenTypes.Function     // 6
    );
    public static final List<String> TOKEN_MODIFIERS = List.of(
            SemanticTokenModifiers.Declaration,
            SemanticTokenModifiers.Definition
    );
    private final VernacTextDocumentService documentService = new VernacTextDocumentService();
    private final VernacWorkspaceService workspaceService = new VernacWorkspaceService();

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
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
        this.documentService.setClient(client);
    }
}