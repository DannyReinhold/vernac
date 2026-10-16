// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.tooling.EditorValueObjects;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

class VernacLiveValueTest {
    private final Path generated =
            Path.of("target/generated-sources/vernac/model/domain/Count.java").toAbsolutePath();

    private EditorValueObjects.Model model(Set<String> names) {
        return new EditorValueObjects.Model(Map.of(), names, names);
    }

    @Test
    void applicationAccessViewsTrackTheirOwnersWithoutReplacingOtherPackages() {
        var api = model(Set.of("model.usecase.Choose"));
        assertTrue(VernacLiveValues.accessForType(api, "model.usecase.access.ChooseAccess"));
        assertTrue(VernacLiveValues.accessForType(api, "model.usecase.access.RemovedRead"));
        assertFalse(VernacLiveValues.accessForType(api, "model.custom.access.ChooseAccess"));
    }

    @Test
    void ownedOldApiCanBeReplacedButHandwrittenAndDependencySourcesCannot() {
        var api = model(Set.of("model.domain.Count"));
        assertTrue(
                VernacLiveValues.shouldReplace(
                        api, Set.of(generated), "model.domain.Count", generated));
        Path handwritten = Path.of("src/main/java/model/domain/Count.java").toAbsolutePath();
        assertFalse(
                VernacLiveValues.shouldReplace(
                        api, Set.of(generated), "model.domain.Count", handwritten));
        assertFalse(VernacLiveValues.shouldReplace(api, Set.of(), "model.domain.Count", generated));
    }

    @Test
    void ownedRemovedValuesAreNotKeptAsGhostClasses() {
        assertTrue(
                VernacLiveValues.shouldReplace(
                        model(Set.of("model.domain.Total")),
                        Set.of(generated),
                        "model.domain.Count",
                        generated));
    }

    @Test
    void unprojectedCurrentDomainTypesRemainAvailable() {
        var api =
                new EditorValueObjects.Model(
                        Map.of(),
                        Set.of("model.domain.Count"),
                        Set.of("model.domain.Count", "model.domain.Planning"));
        Path item = generated.resolveSibling("Planning.java");
        assertFalse(VernacLiveValues.shouldReplace(api, Set.of(item), "model.domain.Planning", item));
    }

    @Test
    void validationReadViewTracksTheCurrentValueOwner() {
        var api = model(Set.of("model.domain.Count"));
        assertTrue(VernacLiveValues.accessForType(api, "model.domain.access.CountRead"));
        assertTrue(VernacLiveValues.accessForType(api, "model.domain.access.ItemRead"));
        assertFalse(VernacLiveValues.accessForType(api, "model.domain.Count"));
    }

    @Test
    void ordinaryJavaScopesDoNotReceiveLiveClassesOrFilters() {
        var finder = new VernacLiveValueFinder(null);
        assertNull(finder.findClass("model.domain.Count", GlobalSearchScope.EMPTY_SCOPE));
        assertEquals(
                0, finder.findClasses("model.domain.Count", GlobalSearchScope.EMPTY_SCOPE).length);
        assertNull(finder.getClassesFilter(GlobalSearchScope.EMPTY_SCOPE));
    }

    @Test
    void ordinaryScopeWrappersKeepTheVernacMarkerWithoutExpandingTheirBaseScope() {
        var host = new com.intellij.testFramework.LightVirtualFile("model.vernac");
        var marked = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE, host, null);
        assertSame(
                marked,
                VernacLiveScope.find(
                        new com.intellij.psi.search.DelegatingGlobalSearchScope(marked)));
        assertNull(
                VernacLiveScope.find(
                        new com.intellij.psi.search.DelegatingGlobalSearchScope(
                                GlobalSearchScope.EMPTY_SCOPE)));
        assertTrue(marked.contains(host));
    }

    @Test
    void staleModelFilteringExcludesTechnicalAndUnrelatedJavaNames() {
        assertTrue(VernacLiveValueFinder.domainDeclaration("model.domain.ItemId"));
        assertTrue(VernacLiveValueFinder.domainDeclaration("model.domain.State"));
        assertTrue(VernacLiveValueFinder.domainDeclaration("model.domain.Names"));
        assertFalse(VernacLiveValueFinder.domainDeclaration("model.domain.__VernacBehavior_Name"));
        assertFalse(VernacLiveValueFinder.domainDeclaration("model.domain.access.ItemAccess"));
        assertFalse(VernacLiveValueFinder.domainDeclaration("model.application.Item"));
        assertFalse(VernacLiveValueFinder.domainDeclaration("model.domain.Nested.Inner"));
        assertFalse(VernacLiveValueFinder.domainDeclaration(null));
    }

    @Test
    void pluginRegistersTheScopedFinder() throws Exception {
        var xml =
                javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile());
        var entries = xml.getElementsByTagName("java.elementFinder");
        boolean registered = false;
        for (int i = 0; i < entries.getLength(); i++)
            registered |=
                    entries.item(i)
                            .getAttributes()
                            .getNamedItem("implementation")
                            .getNodeValue()
                            .equals(VernacLiveValueFinder.class.getName());
        assertTrue(registered);
    }


    @Test
    void invalidAndRemovedIdsEnumsAndCollectionsRespectTheOwnershipManifest() {
        for (String name : List.of("ItemId", "State", "Names")) {
            Path file = generated.resolveSibling(name + ".java");
            String qualified = "model.domain." + name;
            var invalid = model(Set.of(qualified));
            assertTrue(VernacLiveValues.shouldReplace(invalid, Set.of(file), qualified, file));
            assertFalse(VernacLiveValues.shouldReplace(invalid, Set.of(), qualified, file));
            assertTrue(VernacLiveValues.shouldReplace(model(Set.of()), Set.of(file), qualified, file));
        }
    }

    @Test
    void allMutableAccessRolesTrackInvalidOrRemovedOwnersWithoutHidingOtherKinds() {
        var api = model(Set.of("model.domain.Task"));
        for (String role : List.of("Read", "Write", "Access")) {
            String name = "model.domain.access.Task" + role;
            Path path = generated.resolveSibling("access").resolve("Task" + role + ".java");
            assertTrue(VernacLiveValues.accessForType(api, name));
            assertTrue(VernacLiveValues.shouldReplace(api, Set.of(path), name, path));
            assertFalse(VernacLiveValues.shouldReplace(api, Set.of(), name, path));
            assertTrue(VernacLiveValues.accessForType(model(Set.of()), name));
            var unprojected = new EditorValueObjects.Model(Map.of(), Set.of(), Set.of("model.domain.Task"));
            assertFalse(VernacLiveValues.accessForType(unprojected, name));
            assertFalse(VernacLiveValues.shouldReplace(unprojected, Set.of(path), name, path));
        }
        assertFalse(VernacLiveValues.accessForType(api, "model.domain.access.TaskWriter"));
        assertFalse(VernacLiveValues.accessForType(api, "model.adapter.outbound.access.TaskAccess"));
    }
}
