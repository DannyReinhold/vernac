package org.vernac.intellij;

import com.intellij.lang.Language;

public final class VernacLanguage extends Language {

    public static final VernacLanguage INSTANCE = new VernacLanguage();

    private VernacLanguage() {
        super("Vernac");
    }
}