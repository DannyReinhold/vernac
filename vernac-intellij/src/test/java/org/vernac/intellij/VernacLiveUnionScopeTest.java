// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.psi.search.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.module.Module;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduce the real host + injected-file scope composition from the installed IDE report. */
class VernacLiveUnionScopeTest {
    private final LightVirtualFile host = new LightVirtualFile("model.vernac");
    private final LightVirtualFile java = new LightVirtualFile("injected.java");

    private GlobalSearchScope injectedFile() {
        return fileScope(java);
    }

    private GlobalSearchScope fileScope(VirtualFile expected) {
        return new GlobalSearchScope() {
            @Override public boolean contains(VirtualFile file) { return file.equals(expected); }
            @Override public boolean isSearchInModuleContent(Module module) { return false; }
            @Override public boolean isSearchInLibraries() { return false; }
        };
    }

    @Test void preservesHostMarkerWhenThePlatformAddsTheInjectedFile() {
        var live = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE, host, null);
        // Same public uniteWith operation used by ResolveScopeManager.withFile.
        var actual = live.uniteWith(injectedFile());
        assertInstanceOf(UnionScope.class, actual);
        assertSame(live, VernacLiveScope.find(actual));
        assertTrue(actual.contains(host));
        assertTrue(actual.contains(java));
        assertFalse(actual.contains(new LightVirtualFile("unrelated.java")));
    }

    @Test void alsoFindsMarkerWithReversedAndNestedWrappers() {
        var live = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE, host, null);
        var union = injectedFile().uniteWith(new DelegatingGlobalSearchScope(live));
        assertSame(live, VernacLiveScope.find(new DelegatingGlobalSearchScope(union)));
    }

    @Test void severalBranchesForTheSameContextAreNotAmbiguous() {
        var first = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE, host, null);
        var second = new VernacLiveScope(injectedFile(), host, null);
        assertNotNull(VernacLiveScope.find(first.uniteWith(second)));
    }

    @Test void neverChoosesBetweenDifferentVernacHosts() {
        var first = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE, host, null);
        var second = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE,
                new LightVirtualFile("other.vernac"), null);
        assertNull(VernacLiveScope.find(first.uniteWith(second)));
    }

    @Test void ordinaryUnionsStayOrdinary() {
        var ordinary = injectedFile().uniteWith(fileScope(host));
        assertNull(VernacLiveScope.find(ordinary));
        var finder = new VernacLiveValueFinder(null);
        assertNull(finder.findClass("example.domain.EditorTest", ordinary));
        assertNull(finder.getClassesFilter(ordinary));
        assertEquals(0, new VernacLiveShortNamesCache(null)
                .getClassesByName("EditorTest", ordinary).length);
    }

    @Test void reportShowsTheBranchesInsteadOfOnlyTheUnionShell() {
        var live = new VernacLiveScope(GlobalSearchScope.EMPTY_SCOPE, host, null);
        var trace = VernacJavaContextDiagnostics.scopeChain(live.uniteWith(injectedFile()));
        assertTrue(trace.stream().anyMatch(s -> s.contains("UnionScope")));
        assertTrue(trace.stream().anyMatch(s -> s.contains("VernacLiveScope")));
    }
}
