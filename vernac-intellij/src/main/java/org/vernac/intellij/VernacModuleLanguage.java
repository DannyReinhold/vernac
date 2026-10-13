// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.lang.Language;
public final class VernacModuleLanguage extends Language {
    public static final VernacModuleLanguage INSTANCE=new VernacModuleLanguage();
    private VernacModuleLanguage() {super("VernacModule");}
}
