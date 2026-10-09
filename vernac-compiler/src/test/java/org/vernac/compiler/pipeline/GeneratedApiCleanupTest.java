// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.*;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.generator.ValidationConditions;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedApiCleanupTest {
    @Test void failureConditionsOnlyRemoveAnUnambiguousOuterNegation() {
        assertEquals("self.string().isBlank()", ValidationConditions.failure("!self.string().isBlank()"));
        assertEquals("(a && b)", ValidationConditions.failure("!(a && b)"));
        assertEquals("!a", ValidationConditions.failure("!!a"));
        for (String condition : List.of("!a && b", "!a || b", "!a == b", "!a & b", "!a | b", "!a ^ b", "a >= b", "(!a)"))
            assertEquals("!(" + condition + ")", ValidationConditions.failure(condition));
        assertEquals("self.größe().isBlank()", ValidationConditions.failure("!self.größe().isBlank()"));
        assertEquals("/* keep */ self.ok()", ValidationConditions.failure("! /* keep */ self.ok()"));
    }
    @Test void technicalInterfacesAndDomainNamesCoexistAndCodeExecutes() throws Exception {
        var compiled = new VernacCompiler().compileSource("""
            namespace model;
            id StopId; id TourId;
            value StopRead(String);
            value StopAccess(String);
            value NameRead(String);
            value Name(String, NameRead? extra) validates { require(!self.string().isBlank(), "blank"); };
            entity Stop[StopId](Name, mut Name? note, StopAccess? extra, StopRead? readValue) behavior {
                modify void annotate(Name? note) { self.note(note); }
            };
            aggregate Tour[TourId](Stop);
            """);
        Map<String,String> files = new LinkedHashMap<>();
        compiled.generatedFiles().forEach(f -> files.put(f.packageName()+"."+f.typeSpec().name(),f.toString()));
        assertTrue(files.containsKey("model.domain.access.StopRead"));
        assertTrue(files.containsKey("model.domain.StopRead"));
        assertTrue(files.containsKey("model.domain.access.NameRead"));
        assertTrue(files.containsKey("model.domain.NameRead"));
        assertFalse(files.get("model.domain.Stop").contains("boolean validate"));
        assertFalse(files.get("model.domain.Tour").contains("boolean validate"));
        assertTrue(files.get("model.domain.__VernacValidation_Name").contains("if (self.string().isBlank())"));
        files.put("model.Scenario", """
            package model;
            import model.domain.*;
            public class Scenario {
                public static void run() {
                    Name name = Name.of("main");
                    Stop stop = Stop.create(name);
                    if (!stop.toString().equals("Stop[id=" + stop.id() + ", name=" + name + ", note=null, extra=null, readValue=null]")) throw new AssertionError(stop);
                    stop.annotate(Name.of("note"));
                    if (!stop.toString().contains("note=Name[")) throw new AssertionError(stop);
                    Tour tour = Tour.create(stop);
                    if (!tour.toString().contains("stop=" + stop) || !tour.toString().contains("createdAt=")) throw new AssertionError(tour);
                    if (tour.toString().contains("persistenceState=")) throw new AssertionError(tour);
                    try { Stop.create(null); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException e) {
                        if (!e.getMessage().equals("Stop.name must not be null")) throw new AssertionError(e);
                    }
                }
            }
            """);
        var output = InMemoryJavaCompiler.compile(files);
        assertTrue(output.success(), output.diagnostics().toString());
        output.loadClass("model.Scenario").getMethod("run").invoke(null);
    }
    @Test void reservesOnlyTheImplementationPrefixAndRejectsDuplicateOutputs() {
        for (String declaration : List.of("id __VernacId;", "value Name(String) list __VernacNames;"))
            assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource("namespace model; " + declaration));
        var file = JavaFile.builder("model.domain", TypeSpec.classBuilder("Same").build()).build();
        assertThrows(SemanticValidationException.class, () -> GeneratedTypeNames.check(List.of(file, file)));
        assertDoesNotThrow(() -> GeneratedTypeNames.check(List.of(file,
                JavaFile.builder("model.domain.access", TypeSpec.classBuilder("Same").build()).build())));
    }
}
