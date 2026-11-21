// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class SourceModuleVisibilityTest {
    @TempDir Path root;

    @Test void onlyExportedTypesCrossTheNamespaceBoundary() throws Exception {
        Path offers = Files.createDirectories(root.resolve("product/offers"));
        Path pages = Files.createDirectories(root.resolve("product/pages")).resolve("page.vernac");
        Files.writeString(offers.resolve("offers.vernac"),
                "namespace product.offers; value Price(long cents); value Cost(long cents); value Margin(Cost cost);");
        Path manifest = offers.resolve("vernac-module.vmodule");
        Files.writeString(manifest, "module product.offers { export product.offers.Price; }");
        var compiler = new VernacCompiler();
        Files.writeString(pages, "namespace product.pages; import product.offers.Price; value Display(Price price);");
        assertDoesNotThrow(() -> compiler.analyzeProject(root));
        for (String reference : new String[]{
                "import product.offers.Cost; value Display(Cost cost);",
                "import product.offers.*; value Display(Cost cost);",
                "value Display(product.offers.Cost cost);"}) {
            Files.writeString(pages, "namespace product.pages; " + reference);
            assertThrows(SemanticValidationException.class, () -> compiler.analyzeProject(root));
        }
        Files.writeString(manifest, "module product.offers { export product.offers.Cost; }");
        assertDoesNotThrow(() -> compiler.analyzeProject(root));
        Files.writeString(manifest, "module product.offers { export product.pages.Display; }");
        assertThrows(SemanticValidationException.class, () -> compiler.analyzeProject(root));
    }
}
