// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.openapi.fileTypes.LanguageFileType;

import org.vernac.intellij.VernacFileType;

/** Matches requests whose implicit source file type is Vernac; produced fragments remain Java. */
public final class VernacSourceCodeFragmentFactory extends VernacCodeFragmentFactory {
    @Override
    public LanguageFileType getFileType() {
        return VernacFileType.INSTANCE;
    }
}
