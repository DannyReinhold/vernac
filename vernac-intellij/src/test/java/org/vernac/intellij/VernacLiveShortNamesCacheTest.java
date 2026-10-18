// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacProjectLoader;
import org.vernac.compiler.tooling.EditorValueObjects;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VernacLiveShortNamesCacheTest {
    @TempDir Path root;

    @Test void offersCurrentVisibleValuesWithoutGeneratingJava() throws Exception {
        Path local = root.resolve("sample/model.vernac");
        Path shared = root.resolve("shared/values.vernac");
        Files.createDirectories(local.getParent());
        Files.createDirectories(shared.getParent());
        Files.writeString(local, "namespace sample; import shared.Imported; value Existing(int);");
        Files.writeString(shared, "namespace shared; value Imported(int); value Hidden(int);");
        var project = new VernacProjectLoader().loadForAnalysis(root, Map.of(local,
                "namespace sample; import shared.Imported; value Existing(int); value EditorTest(int);"), new ArrayList<>());
        var api = new EditorValueObjects().build(project);
        assertEquals(Set.of("sample.domain.Existing", "sample.domain.EditorTest", "shared.domain.Imported"),
                VernacLiveScope.completionClasses(project, api, local));
        assertFalse(Files.exists(root.resolve("target")));
    }

    @Test void invalidValuesAndTechnicalReadViewsAreNotOffered() throws Exception {
        Path source = root.resolve("sample/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace sample; value Valid(int) validates { require(self.intValue() > 0); }; value Invalid(Missing);");
        var project = new VernacProjectLoader().loadForAnalysis(root, Map.of(), new ArrayList<>());
        var api = new EditorValueObjects().build(project);
        assertEquals(Set.of("sample.domain.Valid"), VernacLiveScope.completionClasses(project, api, source));
    }

    @Test void unscopedAndOrdinaryJavaLookupsStayEmpty() {
        var cache = new VernacLiveShortNamesCache(null);
        assertEquals(0, cache.getAllClassNames().length);
        assertEquals(0, cache.getClassesByName("EditorTest", GlobalSearchScope.EMPTY_SCOPE).length);
        assertTrue(cache.processAllClassNames(name -> fail("Must not expose live names"), GlobalSearchScope.EMPTY_SCOPE, null));
    }

    @Test void nativeMatcherAcceptsBothReportedPrefixes() {
        assertTrue(new com.intellij.codeInsight.completion.impl.CamelHumpMatcher("EdTes", false).prefixMatches("EditorTest"));
        assertTrue(new com.intellij.codeInsight.completion.impl.CamelHumpMatcher("EditorT", false).prefixMatches("EditorTest"));
    }

    @Test void cacheIsRegisteredInTheNativeJavaExtensionPoint() throws Exception {
        assertTrue(Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))
                .contains("<java.shortNamesCache implementation=\"org.vernac.intellij.VernacLiveShortNamesCache\"/>"));
    }

    @Test void classNamesAreDeduplicatedForNativePrefixMatching() {
        assertEquals(Set.of("EditorTest", "Existing"), VernacLiveShortNamesCache.classNames(
                List.of("one.domain.EditorTest", "two.domain.EditorTest", "one.domain.Existing")));
    }

    @Test void offersIdsEnumsAndEachVisibleNamedCollection() throws Exception {
        Path source = root.resolve("sample/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace sample; id TaskId list TaskIds; value State = OPEN | DONE set States; value Label(String) list Labels, set UniqueLabels;");
        var project = new VernacProjectLoader().loadForAnalysis(root, Map.of(), new ArrayList<>());
        var api = new EditorValueObjects().build(project);
        assertEquals(Set.of("sample.domain.TaskId", "sample.domain.TaskIds", "sample.domain.State",
            "sample.domain.States", "sample.domain.Label", "sample.domain.Labels", "sample.domain.UniqueLabels"),
            VernacLiveScope.completionClasses(project, api, source));
    }

    @Test void mutableTypesAndCollectionsAreSuggestedButTechnicalViewsAreNot() throws Exception {
        Path source = root.resolve("sample/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace sample; id LineId; id OrderId; entity Line[LineId]() list Lines; aggregate Order[OrderId](Lines) list Orders;");
        var project = new VernacProjectLoader().loadForAnalysis(root, Map.of(), new ArrayList<>());
        var api = new EditorValueObjects().build(project);
        assertEquals(Set.of("sample.domain.LineId", "sample.domain.OrderId", "sample.domain.Line", "sample.domain.Lines",
            "sample.domain.Order", "sample.domain.Orders"), VernacLiveScope.completionClasses(project, api, source));
        assertTrue(api.values().containsKey("sample.domain.access.OrderAccess"));
    }
}
