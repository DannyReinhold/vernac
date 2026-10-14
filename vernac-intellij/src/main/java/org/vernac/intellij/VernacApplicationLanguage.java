// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.lang.Language;
public final class VernacApplicationLanguage extends Language {
    public static final VernacApplicationLanguage INSTANCE=new VernacApplicationLanguage();
    private VernacApplicationLanguage() {super("VernacApplication");}
}
