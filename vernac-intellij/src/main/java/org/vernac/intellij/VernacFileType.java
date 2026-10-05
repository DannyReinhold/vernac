package org.vernac.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

public final class VernacFileType extends LanguageFileType {

    public static final VernacFileType INSTANCE = new VernacFileType();

    private static final Icon FILE_ICON =
            IconLoader.getIcon("/icons/vernac.svg", VernacFileType.class);

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
        return FILE_ICON;
    }
}