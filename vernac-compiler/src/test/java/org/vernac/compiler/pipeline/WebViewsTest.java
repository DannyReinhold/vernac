// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WebViewsTest {
    @TempDir Path directory;

    private static final String SOURCE = """
            namespace house;
            id RoomId;
            value RoomName(String);
            aggregate Room[RoomId](RoomName name);
            value RoomSummary(String name);
            usecase Read() returns RoomSummary behavior { execute { return RoomSummary.of("Kitchen"); } }
            usecase Add(RoomName name) behavior { execute {} }

            component Name(String value) { text value; }
            view Rooms(RoomModel rooms) {
                page "Rooms & details" layout workspace theme "/house.css" {
                    heading "Rooms";
                    include Name(rooms.name);
                    form submit HouseWeb.add {
                        textField name label "Room name";
                        button "Save" variant primary;
                    }
                }
            }

            adapter HouseWeb inbound mvc uses Read read, Add add {
                base "/rooms";
                error view "problem";
                schema RoomModel(String name);
                mapping projectRoom: RoomModel <- RoomSummary { name <- source.name; }
                index {
                    GET "/";
                    execute read {}
                    render Rooms model rooms as RoomModel using projectRoom;
                }
                add {
                    POST "/";
                    form String name;
                    execute add { name <- form.name; }
                    redirect index message "Saved.";
                    errors { when validation then redisplay index status 422 "INVALID" "Check the name."; }
                }
            }
            """;

    @Test
    void rendersTheMappedModelAndBindsFormsToNamedEndpoints() throws IOException {
        var file = directory.resolve("house/rooms.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        var compiler = new VernacCompiler();
        var project = compiler.analyzeProject(directory);
        String html = project.webTemplates().get("vernac-generated/house/Rooms.html");
        assertNotNull(html);
        assertTrue(html.contains("Rooms &amp; details"));
        assertTrue(html.contains("th:text=\"$" + "{rooms.name()}\""));
        assertTrue(html.contains("th:action=\"@{/rooms}\""));
        assertTrue(html.contains("name=\"name\""));
        var generated = compiler.compileProject(directory).generatedFiles().toString();
        assertTrue(generated.contains("HouseWebMappings.projectRoom(__result)"));
        assertTrue(generated.contains("MvcResponses.redirect(\"/rooms\""));
    }

    @Test
    void navigationAndFormatterUseTheSameViewSyntax() throws IOException {
        var file = directory.resolve("house/rooms.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        var compiler = new VernacCompiler();
        var refs = new org.vernac.compiler.tooling.VernacTypeReferences().build(
                compiler.readProject(directory), file, java.util.Map.of(file, SOURCE));
        assertTrue(refs.stream().anyMatch(ref -> ref.target().name().equals("Rooms")
                && SOURCE.substring(ref.start(), ref.end()).equals("Rooms")));
        assertTrue(refs.stream().anyMatch(ref -> ref.target().name().equals("Name")));
        assertTrue(refs.stream().anyMatch(ref -> ref.target().name().equals("index")));
        String formatted = org.vernac.compiler.format.VernacFormatter.format(SOURCE);
        assertEquals(formatted, org.vernac.compiler.format.VernacFormatter.format(formatted));
        assertDoesNotThrow(() -> compiler.compileSource(formatted));
    }

    @Test
    void rejectsAggregateModelsAndUnknownFieldAndActionBindings() {
        var compiler = new VernacCompiler();
        for (String invalid : new String[] {
                SOURCE.replace("RoomModel rooms", "Room rooms"),
                SOURCE.replace("rooms.name", "rooms.unknown"),
                SOURCE.replace("HouseWeb.add", "HouseWeb.index"),
                SOURCE.replace("textField name", "textField unknown"),
                SOURCE.replace("render Rooms", "render Missing")
        }) {
            assertThrows(SemanticValidationException.class, () -> compiler.compileSource(invalid));
        }
    }
}
