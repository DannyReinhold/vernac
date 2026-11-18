// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.vernac.compiler.pipeline.EditorSourceParser;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.modules.ModuleArtifacts;

/** Resolves logical MVC views in the application's conventional template directory. */
public final class MvcTemplateReferences {
    private MvcTemplateReferences() {}

    public record Reference(int start, int end, Path target) {}

    public static List<Reference> find(Path sourceRoot, String text) {
        var parsed = new EditorSourceParser().analyze("editor.vernac", text, new ArrayList<>());
        if (parsed.isEmpty()) return List.of();
        var directory = ModuleArtifacts.projectDirectory(sourceRoot)
                .resolve("src/main/resources/templates");
        var result = new ArrayList<Reference>();
        for (var declaration : parsed.get().declarations()) {
            var adapter = declaration.inboundHttpAdapterDefinition();
            if (adapter == null || !"mvc".equals(adapter.transport.getText())) continue;
            add(result, directory, text, adapter.errorView);
            for (var endpoint : adapter.inboundEndpoint()) {
                add(result, directory, text, endpoint.view);
                add(result, directory, text, endpoint.absentView);
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Reference> result, Path directory, String text, Token token) {
        if (token == null) return;
        String name = token.getText();
        if (!name.matches("\\\"[\\p{L}\\p{N}_-]+(?:/[\\p{L}\\p{N}_-]+)*\\\"")) return;
        Path target = directory.resolve(name.substring(1, name.length() - 1) + ".html");
        if (Files.isRegularFile(target))
            result.add(new Reference(text.offsetByCodePoints(0, token.getStartIndex() + 1),
                    text.offsetByCodePoints(0, token.getStopIndex()), target));
    }
}
