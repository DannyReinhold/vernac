// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationDelegateTest {
    private Map<String,String> sources(String declarations) {
        Map<String,String> result = new LinkedHashMap<>();
        new VernacCompiler().compileSource("namespace model; " + declarations).generatedFiles()
                .forEach(f -> result.put(f.packageName()+"."+f.typeSpec().name(),f.toString()));
        return result;
    }
    @Test void valuesUseSeparateReadViewWithOptionalGettersAndPublicBehavior() throws Exception {
        var sources = sources("""
            value Name(String, String? note) validates {
                require(self.upper().equals(self.string().toUpperCase(Locale.ROOT)), "upper");
                require(self.note().isEmpty() || !self.note().orElseThrow().isBlank(), "note");
            } behavior {
                java imports { java.util.Locale; }
                public String upper() { return self.string().toUpperCase(Locale.ROOT); }
            };
            """);
        sources.put("model.Scenario", """
            package model;
            import model.domain.*;
            public class Scenario {
                public static void run() {
                    Name name = Name.of("hello");
                    if (NameRead.class.isInstance(name)) throw new AssertionError();
                    if (!name.upper().equals("HELLO")) throw new AssertionError();
                    try { Name.of("hello", " "); throw new AssertionError(); }
                    catch (org.vernac.runtime.DomainValidationException expected) {
                        if (!expected.getMessage().equals("Name: note")) throw new AssertionError(expected);
                    }
                }
            }
            """);
        var result=InMemoryJavaCompiler.compile(sources);
        assertTrue(result.success(),result.diagnostics().toString());
        assertNull(result.loadClass("model.domain.__VernacValidation_Name").getEnclosingClass());
        result.loadClass("model.Scenario").getMethod("run").invoke(null);
    }
    @Test void validatorCannotUsePrivateFieldsSettersOrModifyMethods() {
        for (String expression : List.of("self.number == 1", "self.change()", "self.number(1) == 1")) {
            var source = sources("""
                id Key;
                entity Item[Key](mut int number) validates { require(%s, "bad"); }
                behavior { modify boolean change() { self.number(1); return true; } };
                """.formatted(expression));
            assertFalse(InMemoryJavaCompiler.compile(source).success(),expression);
        }
    }
    @Test void bareNamesAndGeneratedTypeCollisionsGetVernacDiagnostics() {
        for (String declaration : List.of(
                "value Name(String) validates { require(!string.isBlank(), \"blank\"); };",
                "value Name(String) validates { require(helper(), \"bad\"); } behavior { private boolean helper() { return true; } };",
                "value NameRead(int); value Name(String) validates { require(true, \"ok\"); };",
                "value __VernacValidation_Name(int); value Name(String) validates { require(true, \"ok\"); };")) {
            assertThrows(SemanticValidationException.class, () -> sources(declaration));
        }
    }
    @Test void expressionWhitespaceAndCommentsArePreserved() {
        var sources=sources("value Name(String) validates { require(self.string() /* keep space */ != null, \"type\"); };");
        assertTrue(sources.get("model.domain.__VernacValidation_Name").contains("/* keep space */ != null"));
        assertTrue(InMemoryJavaCompiler.compile(sources).success());
    }
}
