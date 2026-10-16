// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.ResolveScopeManager;
import com.intellij.psi.impl.source.PsiJavaFileImpl;
import com.intellij.psi.search.GlobalSearchScope;

import org.jetbrains.annotations.NotNull;

/** Nonphysical generated Java with the same live scope for parameter and return type resolution. */
final class VernacLiveJavaFile extends PsiJavaFileImpl implements FileResolveScopeProvider {
    private final VirtualFile source;
    private final VernacLiveValues.Snapshot snapshot;

    VernacLiveJavaFile(
            FileViewProvider provider, VirtualFile source, VernacLiveValues.Snapshot snapshot) {
        super(provider);
        this.source = source;
        this.snapshot = snapshot;
        // Native class insertion compares PSI before/after its document edit. Snapshots
        // create different Java files, but the same domain type still has the same source identity.
        // Use provenance metadata, not setOriginalFile(): Java context must remain Java.
        var origin = PsiManager.getInstance(getProject()).findFile(source);
        if (origin != null) putUserData(PsiFileFactory.ORIGINAL_FILE, origin);
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public boolean ignoreReferencedElementAccessibility() {
        return false;
    }

    @Override
    public @NotNull GlobalSearchScope getFileResolveScope() {
        var base = ResolveScopeManager.getInstance(getProject()).getDefaultResolveScope(source);
        return new VernacLiveScope(base, source, snapshot);
    }
}
