// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VernacMcpServerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;

    @Test
    void negotiatesStdioAndServesBundledLanguageHelpWithoutCheckingTheWorkspace() throws Exception {
        var main = Files.createDirectories(root.resolve("src/main/vernac/proof"));
        Files.writeString(main.resolve("invalid.vernac"), "This file has no namespace");
        var server = new VernacMcpServer(ScenarioDaemonLauncher.daemon(root, "domain"));

        var replies = exchange(server, """
                {"jsonrpc":"2.0","id":"early","method":"resources/list"}
                {"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":"tools","method":"tools/list"}
                {"jsonrpc":"2.0","id":"docs","method":"resources/list"}
                {"jsonrpc":"2.0","id":"page","method":"resources/read","params":{"uri":"vernac://docs/ai-language-reference.md"}}
                {"jsonrpc":"2.0","id":"lookup","method":"tools/call","params":{"name":"search_docs","arguments":{"query":"keyword messages"}}}
                {"jsonrpc":"2.0","id":"unlisted","method":"resources/read","params":{"uri":"file:///etc/passwd"}}
                {invalid JSON
                """);
        assertEquals(8, replies.size());
        assertEquals(-32000, replies.get(0).at("/error/code").asInt());
        assertEquals("2025-06-18", replies.get(1).at("/result/protocolVersion").asText());
        var version = replies.get(1).at("/result/serverInfo/version").asText();
        assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+.*"), version);
        assertTrue(replies.get(2).at("/result/tools").size() >= 10);
        assertEquals(10, replies.get(3).at("/result/resources").size());
        assertTrue(replies.get(4).at("/result/contents/0/text").asText().contains("Working on a Vernac application"));
        assertEquals(version, replies.get(5).at("/result/structuredContent/vernacVersion").asText());
        var hit = replies.get(5).at("/result/structuredContent/matches/0/uri").asText();
        assertTrue(hit.contains("#"), hit);
        var section = exchange(server, JSON.writeValueAsString(java.util.Map.of("jsonrpc", "2.0", "id", "section",
                "method", "resources/read", "params", java.util.Map.of("uri", hit))));
        assertTrue(section.getFirst().at("/result/contents/0/text").asText().startsWith("#"));
        assertEquals(-32002, replies.get(6).at("/error/code").asInt());
        assertEquals(-32700, replies.get(7).at("/error/code").asInt());
    }

    @Test
    void runsRealScenariosAndKeepsModelEditsInsideTheSelectedOverlay() throws Exception {
        var main = Files.createDirectories(root.resolve("src/main/vernac/proof"));
        var tests = Files.createDirectories(root.resolve("src/test/vernac/proof"));
        Files.writeString(main.resolve("model.vernac"), "namespace proof; value Counter(int); value Points(int amount);");
        Files.writeString(tests.resolve("counter.vernac"),
                "namespace proof; suite Checks { scenario count { given n = Counter(1); expect n.intValue equals 1; } }");
        var server = new VernacMcpServer(ScenarioDaemonLauncher.daemon(root, "domain"));

        var replies = exchange(server, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"test_affected_saved","arguments":{"revision":0}}}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"test_affected_saved","arguments":{"revision":0}}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"open_overlay","arguments":{"revision":0}}}
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"preview_add_value_field","arguments":{"client":"1","revision":0,"path":"src/main/vernac/proof/model.vernac","type":"proof.Points","name":"bonus","fieldType":"int"}}}
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"apply_checked","arguments":{"client":"1","revision":0,"edits":[{"kind":"put","path":"src/main/vernac/proof/model.vernac","text":"namespace proof; value Counter(int); value Points(int amount, int bonus);"}]}}}
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"describe_type","arguments":{"client":"1","revision":1,"type":"proof.Points"}}}
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"check_overlay","arguments":{"client":"1","revision":0}}}
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"workspace_revision"}}
                """);

        assertEquals("2025-11-25", replies.getFirst().at("/result/protocolVersion").asText());
        assertEquals("PASSED", replies.get(1).at("/result/structuredContent/outcomes/proof.Checks.count").asText());
        assertTrue(replies.get(2).at("/result/structuredContent/outcomes").isEmpty());
        assertEquals("1", replies.get(3).at("/result/structuredContent/client").asText());
        assertTrue(replies.get(4).at("/result/structuredContent/edits/0/text").asText().contains("bonus"));
        assertTrue(replies.get(5).at("/result/structuredContent/applied").asBoolean());
        assertEquals("bonus", replies.get(6).at("/result/structuredContent/type/fields/1/name").asText());
        assertTrue(replies.get(7).at("/result/isError").asBoolean());
        assertEquals("stale_revision", replies.get(7).at("/result/structuredContent/code").asText());
        assertEquals(0, replies.get(8).at("/result/structuredContent/revision").asInt());
        assertFalse(Files.readString(main.resolve("model.vernac")).contains("bonus"));
    }

    private static List<JsonNode> exchange(VernacMcpServer server, String requests) throws Exception {
        var output = new StringWriter();
        server.serve(new StringReader(requests), output);
        return output.toString().lines().map(JSON::readTree).toList();
    }
}
