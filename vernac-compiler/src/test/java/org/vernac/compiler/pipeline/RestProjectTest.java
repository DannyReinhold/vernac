// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RestProjectTest {
    @TempDir Path root;
    private String compile(String signature, String operation) throws Exception {
        Path folder = Files.createDirectories(root.resolve("demo"));
        Files.writeString(folder.resolve("model.vernac"), """
            namespace demo;
            value Title(String);
            value Pair(Title first, Title? second);
            port Remote { %s; }
            adapter Client implements Remote {
                rest { client http; base baseUri; %s }
                schema Payload(String text);
                mapping toTitle: Title <- Payload { string <- source.text; }
                mapping toPayload: Payload <- Title { text <- source.string; }
            }
            """.formatted(signature, operation));
        return new VernacCompiler().compileProject(root).generatedFiles().toString();
    }
    @Test void resolvesRequestsAndResponsesIntoTheExistingBehaviorCompanion() throws Exception {
        String java = compile("Title lookup(Pair pair)", """
            Title lookup(Pair pair) {
                GET "/items/{key}";
                path "key" <- pair.first;
                query "q" <- pair.second;
                response Payload using toTitle;
            }
            """);
        assertThat(java).contains("__VernacBehavior_Client", "HttpRequests.at", "toTitle", "HttpJson.read", "pair.second().map(");
        assertThat(java).doesNotContain("TODO");
    }
    @Test void diagnosesPlaceholdersUnknownParametersAndOptionalPaths() {
        assertThatThrownBy(() -> compile("Title lookup(Title? title)", """
            Title lookup(Title? title) {
                GET "/items/{id}/{missing}";
                path "id" <- title;
                query "q" <- nope;
                response Payload using toTitle;
            }
            """)).hasMessageContaining("present value").hasMessageContaining("Missing REST path binding: missing")
                .hasMessageContaining("Unknown REST operation parameter: nope");
    }
    @Test void requestAndResponseMappingsMustHaveExactEndpointTypes() {
        assertThatThrownBy(() -> compile("Title lookup(Pair pair)", """
            Title lookup(Pair pair) {
                POST "/items";
                body <- pair using toPayload;
                response Payload using toPayload;
            }
            """)).hasMessageContaining("Request mapping must accept").hasMessageContaining("Response mapping must accept");
    }
    @Test void rejectsUnsupportedBodiesResultsAndAbsencePolicies() {
        assertThatThrownBy(() -> compile("Title lookup(Title title)", """
            Title lookup(Title title) { GET "/items"; body <- title using toPayload; on 403 absent; }
            """)).hasMessageContaining("GET request bodies").hasMessageContaining("require response")
                .hasMessageContaining("Only on 404").hasMessageContaining("optional port result");
        assertThatThrownBy(() -> compile("void lookup()", "void lookup() { GET \"/items\"; response Payload using toTitle; }"))
                .hasMessageContaining("Void REST operations");
    }
    @Test void catchesUnsafeRoutesAndConflictingHeadersBeforeJavac() {
        assertThatThrownBy(() -> compile("Title lookup(Title title)", """
            Title lookup(Title title) {
                GET "https://remote.example/items";
                header "Host" <- title;
                header "X-Value" <- title;
                header "x-value" <- title;
                response Payload using missing;
            }
            """)).hasMessageContaining("relative path").hasMessageContaining("transport-managed")
                .hasMessageContaining("Duplicate REST header").hasMessageContaining("Unknown REST mapping: missing");
    }
    @Test void requiresCompletePortAndUnambiguousTechnicalDependencies() {
        assertThatThrownBy(() -> compile("Title lookup(Title title)", ""))
                .hasMessageContaining("missing port operation");
        assertThatThrownBy(() -> compile("Title lookup(Pair pair)", """
            Title lookup(Pair pair) { GET "/items"; query "q" <- pair; response Payload using toTitle; }
            """)).hasMessageContaining("requires a scalar");
    }
    @Test void optionalResponseAndExternalErrorsUseSameSelfContract() throws Exception {
        assertThat(compile("Title? lookup(Title title)", """
            Title? lookup(Title title) {
                GET "/items"; response Payload using toTitle; on 404 absent;
                errors implemented by example.CarrierErrors;
            }
            """)).contains("optionalOn404", "example.CarrierErrors.lookup(self, title, __response)");
    }
    @Test void javaProjectionAndNavigationRecognizeRestOperations() throws Exception {
        compile("Title load(Title title)", "Title load(Title title) { GET \"/items\"; response Payload using toTitle; }");
        Path file = root.resolve("demo/model.vernac");
        String text = Files.readString(file).replace("mapping toTitle:",
                "mapping helper: String <- Title { java { return source.string(); } } mapping toTitle:");
        Files.writeString(file, text);
        var compiler = new VernacCompiler();
        var project = compiler.readProject(root);
        var blocks = new org.vernac.compiler.tooling.BehaviorJavaProjection().build(project, project.sources().getFirst(), text);
        assertThat(blocks.toString()).contains("helper(");
        var nav = new org.vernac.compiler.tooling.VernacTypeNavigation();
        assertThat(nav.findMember(project, java.util.Map.of(file, text), "demo.adapter.outbound.Client",
                new org.vernac.compiler.tooling.VernacTypeNavigation.Member("load", java.util.List.of("demo.domain.Title")))).isPresent();
    }

}
