// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.MethodNode;
import org.vernac.compiler.pipeline.ProjectTypeResolver;
import org.vernac.compiler.pipeline.VernacProjectLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VernacModelOperationsTest {
    @TempDir Path root;

    @Test
    void previewsOnePatchAndQueriesTheResolvedResultWithoutReparsingOtherFiles() throws Exception {
        var namespace = Files.createDirectories(root.resolve("app"));
        var source = namespace.resolve("title.vernac");
        var text = """
                namespace app;
                // Keep this 😀 and the inline comment.
                value Title(String text /* heading */) behavior {
                    public String display() { return self.text(); }
                };
                """;
        Files.writeString(source, text);
        Files.writeString(namespace.resolve("locale.vernac"), "namespace app; value LocaleName(String);");
        var session = new VernacProjectLoader().openSession(root);
        var resolver = new ProjectTypeResolver();
        var before = new VernacModelQueries(resolver.resolve(session.load(Map.of())));
        var edit = VernacModelEdits.addValueField(source, text, "app.Title", "locale", "app.LocaleName", true);
        var changed = edit.applyTo(text);
        assertEquals(text.replace("/* heading */)", "/* heading */, app.LocaleName? locale)"), changed);
        assertThrows(IllegalArgumentException.class, () -> edit.applyTo(text + "\n"));

        var after = new VernacModelQueries(resolver.resolve(session.load(Map.of(source, changed))));
        assertEquals(new VernacProjectLoader.ParseStatistics(1, 1), session.statistics());
        var description = after.describeType("app.Title").orElseThrow();
        assertEquals(List.of("text", "locale"), description.fields().stream().map(VernacModelQueries.Field::name).toList());
        var field = description.fields().getLast();
        assertTrue(field.type().optional());
        assertEquals("app.LocaleName", field.type().identity().orElseThrow());
        var method = description.methods().getFirst();
        assertEquals("display", method.name());
        assertEquals("public", method.access());
        assertEquals("java.lang.String", method.returns().identity().orElseThrow());
        assertEquals(4, method.location().line());
        assertEquals(1, before.describeType("app.Title").orElseThrow().fields().size());
        assertEquals(text, Files.readString(source));
        assertTrue(after.describeType("Title").isEmpty());
    }

    @Test
    void invalidPreviewInputAndUnresolvedTypesAreRejectedAtTheirOwnBoundaries() throws Exception {
        var source = Files.createDirectories(root.resolve("app")).resolve("model.vernac");
        var text = "namespace app; value Entry(); value State = OPEN | DONE;";
        Files.writeString(source, text);
        assertThrows(IllegalArgumentException.class,
                () -> VernacModelEdits.addValueField(source, text, "app.State", "number", "int", false));
        assertThrows(IllegalArgumentException.class,
                () -> VernacModelEdits.addValueField(source, text, "app.Entry", "number", "int); id Other", false));
        var patch = VernacModelEdits.addValueField(source, text, "app.Entry", "number", "int", false);
        assertEquals(text.replace("Entry()", "Entry(int number)"), patch.applyTo(text));
        var missing = VernacModelEdits.addValueField(source, text, "app.Entry", "number", "MissingType", false);
        var session = new VernacProjectLoader().openSession(root);
        var resolved = new ProjectTypeResolver().resolve(session.load(Map.of(source, patch.applyTo(text))));
        assertEquals(List.of("OPEN", "DONE"),
                new VernacModelQueries(resolved).describeType("app.State").orElseThrow().enumConstants());
        assertThrows(SemanticValidationException.class,
                () -> new ProjectTypeResolver().resolve(session.load(Map.of(source, missing.applyTo(text)))));
    }

    @Test
    void previewsAnAggregateAndIdAsOneNewSourceForTheSameCompiler() throws Exception {
        var namespace = Files.createDirectories(root.resolve("app"));
        Files.writeString(namespace.resolve("slot.vernac"), "namespace app; value Slot(int);");
        var source = namespace.resolve("booking.vernac");
        var session = new VernacProjectLoader().openSession(root);
        session.load(Map.of());
        var text = VernacModelEdits.aggregateSource("app", "Booking", "BookingId", true,
                List.of(new VernacModelEdits.AggregateField("slot", "Slot", false, true)));
        var project = new ProjectTypeResolver().resolve(session.load(Map.of(source, text)));
        var booking = new VernacModelQueries(project).describeType("app.Booking").orElseThrow();
        assertTrue(booking.fields().getFirst().mutable());
        assertEquals("app.BookingId", booking.idType().orElseThrow().identity().orElseThrow());
        assertEquals(new VernacProjectLoader.ParseStatistics(1, 1), session.statistics());
        assertFalse(Files.exists(source));
    }

    @Test
    void describesAggregateFieldsAndMethodsByTheirVernacIdentity() throws Exception {
        var source = Files.createDirectories(root.resolve("app")).resolve("booking.vernac");
        Files.writeString(source, """
                namespace app;
                id BookingId;
                value Slot(int);
                aggregate Booking[BookingId](mut Slot slot) behavior {
                    modify void move(Slot next) vernac { self slot = next. }
                };
                """);
        var project = new ProjectTypeResolver().resolve(new VernacProjectLoader().load(root));
        var booking = new VernacModelQueries(project).describeType("app.Booking").orElseThrow();
        assertEquals("app.BookingId", booking.idType().orElseThrow().identity().orElseThrow());
        assertTrue(booking.fields().getFirst().mutable());
        assertEquals(MethodNode.Mode.MODIFY, booking.methods().getFirst().effect());
        assertEquals("void", booking.methods().getFirst().returns().identity().orElseThrow());
    }
}
