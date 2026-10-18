// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.*;
import com.intellij.psi.impl.ResolveScopeManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.*;

import org.jetbrains.annotations.NotNull;

/** The normal host file plus a narrowly marked Java resolve scope. */
final class VernacPsiFile extends PsiFileBase implements FileResolveScopeProvider {
    VernacPsiFile(FileViewProvider provider) {
        super(provider, VernacLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return VernacFileType.INSTANCE;
    }

    @Override
    public boolean ignoreReferencedElementAccessibility() {
        return false;
    }

    @Override
    public @NotNull GlobalSearchScope getFileResolveScope() {
        return CachedValuesManager.getCachedValue(
                this,
                () -> {
                    // Completion clones the injection host. Its light virtual file has no
                    // source root, but its original PSI still identifies the local model.
                    var virtual = getOriginalFile().getViewProvider().getVirtualFile();
                    var base =
                            ResolveScopeManager.getInstance(getProject())
                                    .getDefaultResolveScope(virtual);
                    GlobalSearchScope scope = base;
                    if (virtual.isInLocalFileSystem()) {
                        var root = VernacNamespaces.sourceRoot(virtual.toNioPath().getParent());
                        if (root.isPresent())
                            scope =
                                    new VernacLiveScope(
                                            base,
                                            virtual,
                                            getProject()
                                                    .getService(VernacLiveValues.class)
                                                    .snapshot(root.get()));
                    }
                    return CachedValueProvider.Result.create(
                            scope,
                            PsiModificationTracker.MODIFICATION_COUNT,
                            com.intellij.openapi.vfs.VirtualFileManager.getInstance());
                });
    }
}
