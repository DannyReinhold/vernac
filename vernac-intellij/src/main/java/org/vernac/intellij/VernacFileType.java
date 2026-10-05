package org.vernac.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;

import javax.swing.*;

public final class VernacFileType extends LanguageFileType {

    public static final VernacFileType INSTANCE = new VernacFileType();

    private VernacFileType() {
        super(VernacLanguage.INSTANCE);
    }

    @Override
    public String getName() {
        return "Vernac";
    }

    @Override
    public String getDescription() {
        return "Vernac domain model";
    }

    @Override
    public String getDefaultExtension() {
        return "vernac";
    }

    @Override
    public Icon getIcon() {
        return null;
    }
}