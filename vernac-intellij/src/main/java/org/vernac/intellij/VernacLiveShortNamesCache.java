// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.util.Processor;
import com.intellij.util.indexing.IdFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Native Java class-name completion for live domain APIs, confined to Vernac Java scopes. */
public final class VernacLiveShortNamesCache extends PsiShortNamesCache {
    private final Project project;

    public VernacLiveShortNamesCache(Project project) {
        this.project = project;
    }

    @Override
    public PsiClass @NotNull [] getClassesByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        var live = VernacLiveScope.find(scope);
        if (live == null || DumbService.isDumb(project)) return PsiClass.EMPTY_ARRAY;
        return live.completionClasses().stream()
                .filter(n -> simpleName(n).equals(name))
                .map(live.snapshot::find)
                .filter(Objects::nonNull)
                .toArray(PsiClass[]::new);
    }

    @Override
    public boolean processAllClassNames(@NotNull Processor<? super String> processor,
            @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        var live = VernacLiveScope.find(scope);
        if (live == null || DumbService.isDumb(project)) return true;
        for (String name : classNames(live.completionClasses()))
            if (!processor.process(name)) return false;
        return true;
    }

    static Set<String> classNames(Collection<String> qualifiedNames) {
        Set<String> names = new TreeSet<>();
        qualifiedNames.forEach(n -> names.add(simpleName(n)));
        return names;
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    // Unscoped searches and ordinary Java files must not see editor-only classes.
    @Override public String @NotNull [] getAllClassNames() { return new String[0]; }
    @Override public String @NotNull [] getAllMethodNames() { return new String[0]; }
    @Override public String @NotNull [] getAllFieldNames() { return new String[0]; }
    @Override public PsiMethod @NotNull [] getMethodsByName(@NotNull String name, @NotNull GlobalSearchScope scope) { return PsiMethod.EMPTY_ARRAY; }
    @Override public PsiMethod @NotNull [] getMethodsByNameIfNotMoreThan(@NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) { return PsiMethod.EMPTY_ARRAY; }
    @Override public PsiField @NotNull [] getFieldsByName(@NotNull String name, @NotNull GlobalSearchScope scope) { return PsiField.EMPTY_ARRAY; }
    @Override public PsiField @NotNull [] getFieldsByNameIfNotMoreThan(@NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) { return PsiField.EMPTY_ARRAY; }
    @Override public boolean processMethodsWithName(@NotNull String name, @NotNull GlobalSearchScope scope, @NotNull Processor<? super PsiMethod> processor) { return true; }
}
