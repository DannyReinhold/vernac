// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.PsiPackageImpl;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Preserve the caller's live scope during native Java package-qualified resolution. */
final class VernacLivePackage extends PsiPackageImpl {
  VernacLivePackage(PsiManager manager, String name) {
    super(manager, name);
  }

  @Override
  public PsiClass @NotNull [] findClassByShortName(
      @NotNull String name, @NotNull GlobalSearchScope scope) {
    var live = VernacLiveScope.find(scope);
    if (live == null || DumbService.isDumb(getProject()))
      return super.findClassByShortName(name, scope);
    // Native completion also uses alternative resolve: calling the facade here
    // would re-enter its slow package lookup. Resolve the live candidate directly.
    var current = live.snapshot.find(getQualifiedName() + "." + name);
    var ordinary = super.findClassByShortName(name, scope);
    var filter = new VernacLiveValueFinder(getProject()).getClassesFilter(scope);
    Set<PsiClass> result = new LinkedHashSet<>();
    if (current != null) result.add(current);
    for (var candidate : ordinary)
      if (filter == null || filter.test(candidate)) result.add(candidate);
    return result.toArray(PsiClass[]::new);
  }

  @Override
  public boolean hasClassWithShortName(@NotNull String name, @NotNull GlobalSearchScope scope) {
    if (VernacLiveScope.find(scope) == null) return super.hasClassWithShortName(name, scope);
    return findClassByShortName(name, scope).length != 0;
  }
}
