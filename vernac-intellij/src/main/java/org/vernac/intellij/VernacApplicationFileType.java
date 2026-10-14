// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.openapi.fileTypes.LanguageFileType;
import javax.swing.Icon;
public final class VernacApplicationFileType extends LanguageFileType {
    public static final VernacApplicationFileType INSTANCE=new VernacApplicationFileType();
    private VernacApplicationFileType() {super(VernacApplicationLanguage.INSTANCE);}
    @Override public String getName() {return "Vernac Application Definition";}
    @Override public String getDescription() {return "Vernac application activation contract";}
    @Override public String getDefaultExtension() {return "vapplication";}
    @Override public Icon getIcon() {return VernacFileType.INSTANCE.getIcon();}
}
