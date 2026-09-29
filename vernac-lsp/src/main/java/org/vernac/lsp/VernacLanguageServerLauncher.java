package org.vernac.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;

public class VernacLanguageServerLauncher {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        startServer(System.in, System.out);
    }

    public static void startServer(InputStream in, OutputStream out) throws ExecutionException, InterruptedException {
        VernacLanguageServer server = new VernacLanguageServer();
        Launcher<LanguageClient> launcher = Launcher.createLauncher(server, LanguageClient.class, in, out);

        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        launcher.startListening().get();
    }
}