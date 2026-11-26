// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AbstractDocumentFormattingService;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import java.nio.file.Path;
import java.util.*;
import org.vernac.compiler.format.VernacFormatter;
/** Standard IntelliJ Reformat Code for Vernac files; Java injection text is retained verbatim. */
public final class VernacFormattingService extends AbstractDocumentFormattingService {
    public Set<Feature> getFeatures(){return Set.of(Feature.AD_HOC_FORMATTING);}
    public boolean canFormat(PsiFile file){return Set.of("vernac","vapplication","vmodule").contains(file.getFileType().getDefaultExtension());}
    public void formatDocument(Document document,List<TextRange> ranges,FormattingContext context,boolean canChangeWhiteSpaceOnly,boolean quickFormat) {
        if(ranges.stream().noneMatch(r->r.getStartOffset()==0&&r.getEndOffset()==document.getTextLength()))return;
        String extension=context.getContainingFile().getFileType().getDefaultExtension();
        var kind=switch(extension){case "vmodule"->VernacFormatter.Kind.MODULE;case "vapplication"->VernacFormatter.Kind.APPLICATION;default->VernacFormatter.Kind.MODEL;};
        var file = context.getContainingFile().getVirtualFile();
        if (kind == VernacFormatter.Kind.MODEL && file != null && file.isInLocalFileSystem()) {
            for (Path parent = Path.of(file.getPath()).getParent(); parent != null; parent = parent.getParent()) {
                if (parent.endsWith(Path.of("src", "main", "vernac"))) break;
                if (parent.endsWith(Path.of("src", "test", "vernac"))) {
                    kind = VernacFormatter.Kind.TEST;
                    break;
                }
            }
        }
        String formatted=VernacFormatter.format(document.getText(),kind);
        if(!formatted.equals(document.getText()))document.setText(formatted);
    }
}
