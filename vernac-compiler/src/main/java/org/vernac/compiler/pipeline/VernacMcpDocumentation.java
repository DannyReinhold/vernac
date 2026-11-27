// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only reference pages bundled from the same checkout as the compiler. */
final class VernacMcpDocumentation {
    private static final String LOCATION = "/org/vernac/mcp/";
    private static final String PREFIX = "vernac://docs/";
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry("ai-language-reference.md", "AI Language Reference"),
            Map.entry("ai-language-details.md", "Detailed Language Reference"),
            Map.entry("ai-tools-reference.md", "AI Tools Reference"),
            Map.entry("ai/start-model.md", "Start a Vernac model"),
            Map.entry("ai/change-aggregate.md", "Change an aggregate"),
            Map.entry("ai/add-query.md", "Add a repository query"),
            Map.entry("ai/add-usecase.md", "Add a use case"),
            Map.entry("ai/test-view-action.md", "Test a rendered view action"),
            Map.entry("development/behavior-language-decision.md", "Native Behavior Language"),
            Map.entry("faq/index.md", "Vernac FAQ"));

    private record Section(String uri, String heading, String markdown) { }
    private record Match(Section section, int score) { }

    private final String version;
    private final Map<String, Section> contents = new LinkedHashMap<>();

    VernacMcpDocumentation() throws IOException {
        version = read("version.txt").strip();
        for (var path : PAGES.keySet().stream().sorted().toList()) {
            String text = read("docs/" + path);
            String uri = PREFIX + path;
            contents.put(uri, new Section(uri, PAGES.get(path), text));
            indexSections(uri, text);
        }
    }

    String version() {
        return version;
    }

    Map<String, ?> list() {
        return Map.of("resources", contents.values().stream().filter(section -> !section.uri().contains("#"))
                .map(section -> Map.of("uri", section.uri(), "name", section.heading(), "mimeType", "text/markdown",
                        "description", "Bundled with Vernac " + version)).toList());
    }

    Map<String, ?> resource(String uri) {
        var section = contents.get(uri);
        if (section == null) return null;
        return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "text/markdown", "text", section.markdown())));
    }

    Map<String, ?> search(String query) {
        String prompt = query.toLowerCase(Locale.ROOT).strip();
        if (prompt.isEmpty() || prompt.length() > 256) {
            throw new IllegalArgumentException("Expected a documentation query of 1–256 characters");
        }
        String[] words = prompt.split("\\s+");
        var matches = new ArrayList<Match>();
        for (var section : contents.values()) {
            if (!section.uri().contains("#")) continue;
            String heading = section.heading().toLowerCase(Locale.ROOT);
            String body = section.markdown().toLowerCase(Locale.ROOT);
            int score = heading.contains(prompt) ? 10 : 0;
            boolean containsAll = true;
            for (String word : words) {
                if (heading.contains(word)) score += 3;
                else if (body.contains(word)) score++;
                else containsAll = false;
            }
            if (containsAll) matches.add(new Match(section, score));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(match -> match.section().uri()));
        return Map.of("vernacVersion", version, "matches", matches.stream().limit(10)
                .map(match -> Map.of("uri", match.section().uri(), "title", match.section().heading())).toList());
    }

    private void indexSections(String uri, String markdown) {
        var headings = new ArrayList<Integer>();
        List<String> lines = markdown.lines().toList();
        String fence = "";
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line).stripLeading();
            if (!fence.isEmpty()) {
                if (text.startsWith(fence) && text.substring(fence.length()).isBlank()) fence = "";
            } else if (text.startsWith("```") || text.startsWith("~~~")) {
                fence = text.substring(0, 3);
            } else if (HEADING.matcher(lines.get(line)).matches()) {
                headings.add(line);
            }
        }
        for (int index = 0; index < headings.size(); index++) {
            int from = headings.get(index);
            int to = index + 1 < headings.size() ? headings.get(index + 1) : lines.size();
            String title = lines.get(from).replaceFirst("^#+\\s+", "");
            String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-');
            String key = URI.create(uri + "#" + slug).toASCIIString();
            for (int duplicate = 1; contents.containsKey(key); duplicate++) {
                key = URI.create(uri + "#" + slug + "-" + duplicate).toASCIIString();
            }
            contents.put(key, new Section(key, title, String.join("\n", lines.subList(from, to))));
        }
    }

    private static String read(String path) throws IOException {
        try (var stream = VernacMcpDocumentation.class.getResourceAsStream(LOCATION + path)) {
            if (stream == null) throw new IOException("Missing bundled Vernac reference " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
