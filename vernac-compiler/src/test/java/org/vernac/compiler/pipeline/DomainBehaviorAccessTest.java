// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.runtime.DomainValidationException;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class DomainBehaviorAccessTest {
    @TempDir Path root;
    private Map<String, String> generate(String declarations) throws Exception {
        Files.createDirectories(root.resolve("test"));
        Files.writeString(root.resolve("test/model.vernac"), "namespace test; " + declarations);
        var result = new VernacCompiler().compileProject(root);
        Map<String, String> sources = new LinkedHashMap<>();
        for (var file : result.generatedFiles()) sources.put(file.packageName() + "." + file.typeSpec().name(), file.toString());
        return sources;
    }
    private InMemoryJavaCompiler.CompilationOutput compile(Map<String, String> sources) {
        var output = InMemoryJavaCompiler.compile(sources);
        assertTrue(output.success(), output.diagnostics().toString());
        return output;
    }
    private Object point(InMemoryJavaCompiler.CompilationOutput output, int value) throws Exception {
        return output.loadClass("test.domain.Position").getMethod("of", int.class).invoke(null, value);
    }
    private String model(String kind, String body) {
        return """
            id RangeId;
            value Label(String); value Position(int);
            %s Range[RangeId](Label, mut Position start, mut Position end, mut Label? note)
            validates { require(self.start().intValue() <= self.end().intValue(), "Start must not exceed end"); }
            behavior {
                java imports { java.util.Locale; }
                read int length() { return self.end().intValue() - self.start().intValue(); }
                read String labelText() { return self.label().string().toUpperCase(Locale.ROOT) + suffix(); }
                private String suffix() { return "!"; }
                modify void startAt(int start) { self.start(Position.of(start)); }
                modify void move(int start, int end) { self.startAt(start); self.end(Position.of(end)); }
                modify int moveAndMeasure(int start, int end) { self.move(start, end); return self.length(); }
                modify void note(Label? note) { self.note(note); }
                %s
            };
            """.formatted(kind, body).replace("modify void note(Label? note)", "modify void annotate(Label? note)");
    }
    @Test void bothKindsExposeSeparateContractsAndValidateOnlyAfterOuterModify() throws Exception {
        for (String kind : List.of("entity", "aggregate")) {
            var output = compile(generate(model(kind, "")));
            var point = output.loadClass("test.domain.Position");
            var type = output.loadClass("test.domain.Range");
            var label = output.loadClass("test.domain.Label");
            var text = label.getMethod("of", String.class).invoke(null, "tour");
            Object instance = type.getMethod("create", label, point, point).invoke(null, text, point(output, 10), point(output, 20));
            assertFalse(output.loadClass("test.domain.RangeRead").isInstance(instance));
            assertFalse(output.loadClass("test.domain.RangeWrite").isInstance(instance));
            assertFalse(output.loadClass("test.domain.RangeAccess").isInstance(instance));
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("start", point));
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("suffix"));
            assertEquals("TOUR!", type.getMethod("labelText").invoke(instance));
            // Intermediate [30,20] is invalid; only the final [30,40] is checked.
            assertEquals(10, type.getMethod("moveAndMeasure", int.class, int.class).invoke(instance, 30, 40));
            var failure = assertThrows(InvocationTargetException.class,
                    () -> type.getMethod("move", int.class, int.class).invoke(instance, 70, 60));
            assertInstanceOf(DomainValidationException.class, failure.getCause());
            // Deliberately naive: failed validation retains mutations.
            assertEquals(point(output, 70), type.getMethod("start").invoke(instance));
            assertEquals(point(output, 60), type.getMethod("end").invoke(instance));
            Object fresh = type.getMethod("create", label, point, point).invoke(null, text, point(output, 1), point(output, 2));
            type.getMethod("move", int.class, int.class).invoke(fresh, 3, 4); // no leaked context
            assertEquals(Optional.empty(), type.getMethod("note").invoke(fresh));
        }
    }
    @Test void readCannotWriteCallModifyOrReadPrivateState() throws Exception {
        for (String body : List.of("self.start(15); return 1;", "self.move(1,2); return 1;", "return self.start;")) {
            var result = InMemoryJavaCompiler.compile(generate(model("entity", "read int forbidden() { " + body + " }")));
            assertFalse(result.success(), body);
        }
    }
    @Test void externalDelegatesReceiveExactViewAndWriteAccessExpires() throws Exception {
        var sources = generate(model("entity", """
            read int externalRead() implemented by external.RangeLogic;
            modify int externalWrite(int end) implemented by external.RangeLogic;
            """));
        sources.put("external.RangeLogic", """
            package external;
            import test.domain.*;
            public class RangeLogic {
                private static RangeAccess retained;
                public static int externalRead(RangeRead self) {
                    if (self instanceof RangeAccess) throw new AssertionError("Read view exposes write interface");
                    return self.length();
                }
                public static int externalWrite(RangeAccess self, int end) {
                    retained = self; self.end(Position.of(end)); return self.length();
                }
                public static void staleWrite() { retained.end(Position.of(999)); }
            }
            """);
        var output = compile(sources);
        var point = output.loadClass("test.domain.Position");
        var type = output.loadClass("test.domain.Range"); var label = output.loadClass("test.domain.Label");
        Object instance = type.getMethod("create", label, point, point).invoke(null, label.getMethod("of", String.class).invoke(null,"a"), point(output,1), point(output,2));
        assertEquals(1, type.getMethod("externalRead").invoke(instance));
        assertEquals(8, type.getMethod("externalWrite", int.class).invoke(instance, 9));
        var failure = assertThrows(InvocationTargetException.class, () -> output.loadClass("external.RangeLogic").getMethod("staleWrite").invoke(null));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(point(output, 9), type.getMethod("end").invoke(instance));
    }
    @Test void childrenAndCollectionsRetainObjectIdentityAndParentValidatesAtOuterReturn() throws Exception {
        var sources = generate("""
            id StopId; id TourId; value Units(int);
            entity Stop[StopId](mut Units units) validates { require(self.units().intValue() >= 0, "negative"); }
            behavior { modify void change(int units) { self.units(Units.of(units)); } } list Stops;
            aggregate Tour[TourId](mut Units capacity, mut Stops)
            validates { require(self.totalUnits() <= self.capacity().intValue(), "capacity"); }
            behavior {
                read int totalUnits() { return self.stops().stream().mapToInt(s -> s.units().intValue()).sum(); }
                modify void change(StopId id, int units, int capacity) {
                    self.stops().by(id).orElseThrow().change(units);
                    self.capacity(Units.of(capacity));
                }
                modify void add(Stop stop) { self.stops(self.stops().plus(stop)); }
                modify void remove(StopId id) { self.stops(self.stops().minusId(id)); }
            };
            """);
        sources.put("test.Scenario", """
            package test;
            import test.domain.*;
            public class Scenario {
                public static void run() {
                    Stop stop = Stop.create(Units.of(30));
                    Tour tour = Tour.create(Units.of(40), Stops.of(java.util.List.of(stop)));
                    tour.change(stop.id(), 50, 60);
                    if (stop.units().intValue() != 50 || tour.stops().by(stop.id()).orElseThrow() != stop) throw new AssertionError();
                    Stop other = Stop.create(Units.of(5)); tour.add(other);
                    if (tour.stops().size() != 2) throw new AssertionError();
                    tour.remove(other.id());
                    if (tour.stops().size() != 1) throw new AssertionError();
                    try { tour.change(stop.id(), 90, 80); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException expected) { }
                    if (stop.units().intValue() != 90 || tour.capacity().intValue() != 80) throw new AssertionError();
                }
            }
            """);
        compile(sources).loadClass("test.Scenario").getMethod("run").invoke(null);
    }
    @Test void rejectsContractCollisionsAndUnclassifiedMethods() {
        for (String declarations : List.of(
                "id XId; value NumberValue(int); entity X[XId](mut NumberValue n) behavior { read int n() { return 0; } };",
                "id XId; value NumberValue(int); value XRead(String); entity X[XId](NumberValue n);",
                "id XId; value NumberValue(int); entity X[XId](NumberValue n) behavior { public int f() { return 0; } };",
                "id XId; value NumberValue(int); entity X[XId](NumberValue n) behavior { read int f(int self) { return 0; } };"))
            assertThrows(SemanticValidationException.class, () -> generate(declarations));
    }

    @Test void resolvesEntityFieldsAndExternalViewSignaturesAcrossNamespaces() throws Exception {
        Files.createDirectories(root.resolve("shared"));
        Files.writeString(root.resolve("shared/types.vernac"), "namespace shared; id Key; value Title(String);");
        var sources = generate("""
            import shared.*;
            entity Task[Key](Title, mut Title? note) behavior {
                read Title titleCopy() { return self.title(); }
                modify void renameNote(Title? note) { self.note(note); }
            };
            """);
        var output = compile(sources);
        var type = output.loadClass("test.domain.Task");
        var title = output.loadClass("shared.domain.Title");
        var key = output.loadClass("shared.domain.Key");
        Object text = title.getMethod("of", String.class).invoke(null,"hello");
        Object instance = type.getMethod("create", title).invoke(null, text);
        assertEquals(Optional.empty(), type.getMethod("note").invoke(instance));
        assertEquals(text, type.getMethod("titleCopy").invoke(instance));
        type.getMethod("renameNote", title).invoke(instance, text);
        assertEquals(Optional.of(text), type.getMethod("note").invoke(instance));
        assertNotNull(type.getMethod("reconstitute", key, title, title));
    }
    @Test void reconstitutionChecksStateAndBodyExceptionsRetainChanges() throws Exception {
        var output = compile(generate(model("entity", "modify void fail() { self.start(Position.of(99)); throw new IllegalStateException(\"body\"); }")));
        var point = output.loadClass("test.domain.Position");
        var type = output.loadClass("test.domain.Range"); var label = output.loadClass("test.domain.Label"); var id = output.loadClass("test.domain.RangeId");
        Object text = label.getMethod("of", String.class).invoke(null,"a");
        Object key = id.getMethod("create").invoke(null);
        var invalid = assertThrows(InvocationTargetException.class, () -> type.getMethod("reconstitute", id, label, point, point, label).invoke(null,key,text,point(output,10),point(output,1),null));
        assertInstanceOf(DomainValidationException.class, invalid.getCause());
        Object instance = type.getMethod("create", label, point, point).invoke(null,text,point(output,1),point(output,2));
        var failure = assertThrows(InvocationTargetException.class, () -> type.getMethod("fail").invoke(instance));
        assertEquals("body", failure.getCause().getMessage());
        assertEquals(point(output, 99), type.getMethod("start").invoke(instance));
        type.getMethod("move", int.class, int.class).invoke(instance,3,4);
    }
}
