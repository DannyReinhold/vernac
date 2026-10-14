// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.openapi.fileTypes.LanguageFileType;
import javax.swing.Icon;
public final class VernacModuleFileType extends LanguageFileType {
    public static final VernacModuleFileType INSTANCE=new VernacModuleFileType();
    private VernacModuleFileType() {super(VernacModuleLanguage.INSTANCE);}
    @Override public String getName() {return "Vernac Module Definition";}
    @Override public String getDescription() {return "Vernac module publication contract";}
    @Override public String getDefaultExtension() {return "vmodule";}
    @Override public Icon getIcon() {return VernacFileType.INSTANCE.getIcon();}
}
