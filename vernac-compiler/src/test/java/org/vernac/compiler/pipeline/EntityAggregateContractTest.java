// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityAggregateContractTest {
    private InMemoryJavaCompiler.CompilationOutput compile(String source, String scenario) throws Exception {
        Map<String,String> files = new LinkedHashMap<>();
        new VernacCompiler().compileSource("namespace test; " + source).generatedFiles()
                .forEach(f -> files.put(f.packageName()+"."+f.typeSpec().name(), f.toString()));
        files.put("test.Scenario", scenario);
        var result = InMemoryJavaCompiler.compile(files);
        assertTrue(result.success(), result.diagnostics().toString());
        result.loadClass("test.Scenario").getMethod("run").invoke(null);
        return result;
    }
    @Test void factoriesMetadataAndNestedModificationsFollowTheContract() throws Exception {
        var result = compile("""
            id StopId; id TourId;
            value Units(int); value Label(String);
            entity Draft[StopId](Label? note);
            aggregate EmptyTour[TourId]();
            entity Stop[StopId](Label, mut Units, Label? note)
            validates { require(self.units().intValue() >= 0, "negative"); }
            behavior { modify void change(Units units) { self.units(units); } } list Stops;
            aggregate Tour[TourId](Label, mut Stops, Label? note)
            validates { require(self.total() <= 100, "capacity"); }
            behavior {
                read int total() { return self.stops().stream().mapToInt(s -> s.units().intValue()).sum(); }
                modify void change(StopId id, Units units) { self.stops().by(id).orElseThrow().change(units); }
                modify void replace(Stops stops) { self.stops(stops); }
                modify void fail(StopId id) { self.change(id, Units.of(99)); throw new IllegalStateException("body"); }
            };
            """, """
            package test;
            import test.domain.*;
            import test.domain.access.*;
            import java.time.Instant;
            import java.util.List;
            public class Scenario {
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void run() {
                    Label label = Label.of("tour");
                    check(Draft.create().note().isEmpty());
                    check(EmptyTour.create().persistenceState().version() == 0);
                    Stop stop = Stop.create(label, Units.of(1));
                    Stop another = Stop.create(label, Units.of(1));
                    check(!stop.id().equals(another.id())); check(stop.note().isEmpty());
                    Tour fresh = Tour.create(label, Stops.of(List.of(stop)));
                    check(fresh.createdAt().equals(fresh.updatedAt()));
                    check(fresh.persistenceState().version() == 0);
                    Instant created = Instant.parse("2000-01-01T00:00:00Z");
                    Instant updated = Instant.parse("2001-01-01T00:00:00Z");
                    Tour tour = Tour.reconstitute(fresh.id(), label, fresh.stops(), null, created, updated, 7L);
                    check(tour.id().equals(fresh.id())); check(tour.createdAt().equals(created));
                    check(tour.updatedAt().equals(updated)); check(tour.persistenceState().version() == 7);
                    tour.change(stop.id(), Units.of(1)); check(tour.updatedAt().equals(updated));
                    tour.replace(Stops.of(List.of(stop))); check(tour.updatedAt().equals(updated));
                    tour.change(stop.id(), Units.of(2));
                    check(stop.units().intValue() == 2); check(tour.updatedAt().isAfter(updated));
                    check(tour.createdAt().equals(created)); check(tour.persistenceState().version() == 7);
                    Instant successful = tour.updatedAt();
                    try { tour.change(stop.id(), Units.of(101)); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException expected) { }
                    check(stop.units().intValue() == 101); check(tour.updatedAt().equals(successful));
                    // Explicitly repair only for this test. Applications should discard the failed graph.
                    tour.change(stop.id(), Units.of(3)); successful = tour.updatedAt();
                    try { tour.fail(stop.id()); throw new AssertionError(); }
                    catch (IllegalStateException expected) { }
                    check(stop.units().intValue() == 99); check(tour.updatedAt().equals(successful));
                    // No owner tracking: a direct child call cannot touch the parent timestamp.
                    stop.change(Units.of(4)); check(tour.updatedAt().equals(successful));
                    Stop replacement = Stop.reconstitute(stop.id(), label, Units.of(5), null);
                    tour.replace(Stops.of(List.of(replacement)));
                    check(tour.stops().by(stop.id()).orElseThrow() == replacement);
                    Instant beforePersistence = tour.updatedAt();
                    tour.persistenceState().version(8L);
                    check(tour.persistenceState().version() == 8); check(tour.updatedAt().equals(beforePersistence));
                    try { Stop.reconstitute(stop.id(), label, Units.of(-1), null); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException expected) { }
                    try { Tour.reconstitute(tour.id(), label, Stops.of(List.of(Stop.create(label, Units.of(101)))), null, created, updated, 8); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException expected) { }
                    try { Stop.create(null, Units.of(1)); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException expected) { }
                }
            }
            """);
        Class<?> tour = result.loadClass("test.domain.Tour"), stop = result.loadClass("test.domain.Stop");
        assertThrows(NoSuchMethodException.class, () -> tour.getMethod("version"));
        assertThrows(NoSuchMethodException.class, () -> tour.getMethod("withVersion", long.class));
        assertThrows(NoSuchMethodException.class, () -> stop.getMethod("createdAt"));
        assertThrows(NoSuchMethodException.class, () -> stop.getMethod("persistenceState"));
        assertThrows(NoSuchMethodException.class, () -> result.loadClass("test.domain.access.TourRead").getMethod("persistenceState"));
    }
    @Test void directAndCollectionCyclesAcrossNamespacesAreDiagnosed(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("a")); Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/a.vernac"), "namespace a; import b.Bs; id AId; entity A[AId](Bs children);");
        Files.writeString(root.resolve("b/b.vernac"), "namespace b; import a.A; id BId; entity B[BId](A? parent) list Bs;");
        var failure = assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileProject(root));
        assertTrue(failure.getMessage().contains("a.A.children -> b.B.parent -> a.A"), failure.getMessage());
        Files.writeString(root.resolve("b/b.vernac"), "namespace b; import a.AId; id BId; entity B[BId](AId? parent) list Bs;");
        assertDoesNotThrow(() -> new VernacCompiler().compileProject(root));
        assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource("namespace test; id NodeId; entity Node[NodeId](Nodes children) list Nodes;"));
        assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource("namespace test; id NodeId; entity Node[NodeId](Node? parent);"));
    }
    @Test void mutableFieldsRejectBuiltinsAndAggregatesButAcceptDomainValues() {
        for (String field : List.of("String text", "int count", "Integer? count", "Other child", "Others children")) {
            String text = "namespace test; id Key; aggregate Other[Key]() list Others; entity Model[Key]("+field+");";
            assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource(text), field);
        }
        assertDoesNotThrow(() -> new VernacCompiler().compileSource("namespace test; id Key list Keys; value Label(String) set Labels; value State = OPEN | CLOSED; entity Model[Key](Key, Keys, Label, Labels, State);") );
    }
}
