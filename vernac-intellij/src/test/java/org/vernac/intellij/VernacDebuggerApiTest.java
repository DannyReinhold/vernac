// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import com.intellij.debugger.ui.tree.render.ClassRenderer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** Targeted binary regression guard; this is not a substitute for the full Plugin Verifier. */
class VernacDebuggerApiTest {
    static boolean referencesRejectedRendererApi(byte[] bytecode) {
        // The ASCII method name occurs verbatim in a class-file UTF-8 constant, for
        // direct references and reflective lookups alike. Scan only compiled classes,
        // never source comments or resources; conservatively disallow this symbol.
        return new String(bytecode, StandardCharsets.ISO_8859_1).contains("setIsApplicableChecker");
    }

    @Test
    void guardDetectsTheHistoricalInternalSetterInCompiledCode() throws IOException {
        assertTrue(
                referencesRejectedRendererApi(
                        resource(
                                "/org/vernac/intellij/VernacDebuggerApiTest$RejectedRendererFixture.class")));
    }

    @Test
    void currentRendererUsesPublicApplicabilityWithoutTheInternalSetter() throws IOException {
        byte[] bytecode = resource("/org/vernac/intellij/debug/VernacViewRenderer.class");
        assertFalse(referencesRejectedRendererApi(bytecode));
        String symbols = new String(bytecode, StandardCharsets.ISO_8859_1);
        assertTrue(symbols.contains("isApplicable"));
        assertTrue(symbols.contains("(Lcom/sun/jdi/Type;)Z"));
    }

    private byte[] resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            assertNotNull(input, "Missing compiled regression fixture: " + name);
            return input.readAllBytes();
        }
    }

    /** Intentional rejected call, never instantiated or included in the plugin distribution. */
    private static final class RejectedRendererFixture extends ClassRenderer {
        RejectedRendererFixture() {
            setIsApplicableChecker(type -> CompletableFuture.completedFuture(true));
        }
    }
}
