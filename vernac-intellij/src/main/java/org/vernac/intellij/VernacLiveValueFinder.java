// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Predicate;

/** Resolve live domain APIs only in Vernac Java scopes; never replace handwritten classes. */
public final class VernacLiveValueFinder extends PsiElementFinder {
    private final Project project;

    public VernacLiveValueFinder(Project project) {
        this.project = project;
    }

    @Override
    public PsiClass findClass(@NotNull String name, @NotNull GlobalSearchScope scope) {
        var live = VernacLiveScope.find(scope);
        return live == null || DumbService.isDumb(project) ? null : live.snapshot.find(name);
    }

    @Override
    public PsiClass @NotNull [] findClasses(
            @NotNull String name, @NotNull GlobalSearchScope scope) {
        var found = findClass(name, scope);
        return found == null ? PsiClass.EMPTY_ARRAY : new PsiClass[] {found};
    }

    @Override
    public PsiPackage findPackage(@NotNull String name) {
        if (DumbService.isDumb(project)) return null;
        boolean published = project.getService(VernacLiveValues.class).knownSnapshots().stream()
                .flatMap(s -> s.api.values().keySet().stream())
                .anyMatch(n -> n.startsWith(name + "."));
        // The facade caches packages before the first editor model may be loaded.
        // Wrap existing physical domain packages eagerly, without inventing packages
        // or loading project models during global Java package lookup.
        boolean physicalDomain = !published && domainPackage(name)
                && com.intellij.psi.impl.file.impl.JavaFileManager.getInstance(project)
                        .findPackage(name) != null;
        return published || physicalDomain
                ? new VernacLivePackage(PsiManager.getInstance(project), name) : null;
    }

    @Override
    public PsiClass @NotNull [] getClasses(
            @NotNull PsiPackage pkg, @NotNull GlobalSearchScope scope) {
        var live = VernacLiveScope.find(scope);
        if (live == null || DumbService.isDumb(project)) return PsiClass.EMPTY_ARRAY;
        return live.snapshot.api.values().keySet().stream()
                .filter(n -> packageName(n).equals(pkg.getQualifiedName()))
                .map(live.snapshot::find)
                .filter(Objects::nonNull)
                .toArray(PsiClass[]::new);
    }

    @Override
    public @NotNull Set<String> getClassNames(
            @NotNull PsiPackage pkg, @NotNull GlobalSearchScope scope) {
        var live = VernacLiveScope.find(scope);
        if (live == null || DumbService.isDumb(project)) return Set.of();
        Set<String> result = new HashSet<>();
        live.snapshot.api.values().keySet().stream()
                .filter(n -> packageName(n).equals(pkg.getQualifiedName()))
                .forEach(n -> result.add(n.substring(n.lastIndexOf('.') + 1)));
        return result;
    }

    @Override
    public PsiPackage @NotNull [] getSubPackages(
            @NotNull PsiPackage pkg, @NotNull GlobalSearchScope scope) {
        var live = VernacLiveScope.find(scope);
        if (live == null || DumbService.isDumb(project)) return PsiPackage.EMPTY_ARRAY;
        String prefix = pkg.getQualifiedName().isEmpty() ? "" : pkg.getQualifiedName() + ".";
        Set<String> names = new TreeSet<>();
        for (String name : live.snapshot.api.values().keySet()) {
            String owner = packageName(name);
            if (!owner.startsWith(prefix)) continue;
            int dot = owner.indexOf('.', prefix.length());
            String child = dot < 0 ? owner : owner.substring(0, dot);
            if (!child.equals(pkg.getQualifiedName())) names.add(child);
        }
        return names.stream()
                .map(n -> new VernacLivePackage(PsiManager.getInstance(project), n))
                .toArray(PsiPackage[]::new);
    }

    @Override
    public Predicate<PsiClass> getClassesFilter(@NotNull GlobalSearchScope scope) {
        var live = VernacLiveScope.find(scope);
        if (live == null || DumbService.isDumb(project)) return null;
        return candidate -> {
            var file = candidate.getContainingFile();
            var virtual = file == null ? null : file.getVirtualFile();
            return virtual == null
                    || !virtual.isInLocalFileSystem()
                    || !(domainDeclaration(candidate.getQualifiedName())
                            || live.snapshot.api.values().containsKey(candidate.getQualifiedName())
                            || VernacLiveValues.accessForType(live.snapshot.api, candidate.getQualifiedName()))
                    || !live.snapshot.replaces(candidate.getQualifiedName(), virtual.toNioPath());
        };
    }

    // Restrict stale-file suppression to compiler-owned public model names.
    // Technical behavior/access classes remain available unless explicitly projected.
    static boolean domainDeclaration(String name) {
        if (name == null) return false;
        int domain = name.lastIndexOf(".domain.");
        if (domain < 0) return false;
        String simple = name.substring(domain + ".domain.".length());
        return !simple.isEmpty() && !simple.contains(".") && !simple.startsWith("__Vernac");
    }

    private static boolean domainPackage(String name) {
        return name.endsWith(".domain") || name.endsWith(".domain.access");
    }

    private static String packageName(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }
}
