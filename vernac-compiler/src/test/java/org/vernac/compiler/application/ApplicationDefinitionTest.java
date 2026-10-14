// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.application;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationDefinitionTest {
    @TempDir Path root;
    private ApplicationDefinition parse(String text) {return ApplicationDefinition.parse(root.resolve(ApplicationDefinition.FILE),text);}
    @Test void parsesEmptyAndProfileDrivenComposition() {
        parse("application app {} ").requireBuildSupport();
        var app=parse("application example.shop { activate example.billing; bind example.billing.PaymentProvider from configuration \"billing.payment-provider\"; }");
        assertEquals("example.billing",app.activations().getFirst().module());
        assertEquals("billing.payment-provider",app.bindings().getFirst().property());
        assertNull(app.bindings().getFirst().adapter());
        app.requireBuildSupport();
    }
    @Test void rejectsMalformedDuplicatesAndBlankKeys() {
        for(String body:List.of("activate example.billing; activate example.billing;",
                "bind example.Payment to example.One; bind example.Payment from configuration \"key\";",
                "bind example.Payment from configuration \" \";", "activate Billing;", "bind example.Payment to Adapter;",
                "bind example.Payment from configuration \" key\";", "activate example.billing"))
            assertThrows(SemanticValidationException.class,()->parse("application example.shop {"+body+"}"),body);
    }
    @Test void checksActualPortImplementationAndPreservesContextualKeywords() throws Exception {
        Path source=root.resolve("example/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace example; port Payment { void pay(); } port Other { void pay(); } adapter Provider implements Payment { behavior { public void pay() {} } } value Application(String); value Holder(String activate); ");
        var resolved=new VernacCompiler().analyzeProject(root,List.of());
        assertTrue(parse("application example.shop {bind example.Payment to example.Provider;}").diagnostics(resolved,List.of()).isEmpty());
        assertTrue(parse("application example.shop {bind example.Other to example.Provider;}").diagnostics(resolved,List.of()).stream().anyMatch(d->d.message().contains("implements")));
        assertFalse(parse("application example.shop {activate missing.module; bind example.Application to example.Provider;}").diagnostics(resolved,List.of()).isEmpty());
    }
}
