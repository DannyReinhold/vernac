// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import com.intellij.formatting.FormattingContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.junit.jupiter.api.Test;
class VernacFormattingServiceTest {
    @Test void fullFileReformatUsesTheCompilerFormatterAndSelectionDoesNotChangeUnselectedText() {
        String[] text={"namespace app;permission Rights{VIEW;}"};
        Document document=(Document)Proxy.newProxyInstance(Document.class.getClassLoader(),new Class[]{Document.class},(p,m,a)->switch(m.getName()) {
            case "getText" -> text[0];case "getTextLength" -> text[0].length();case "setText" -> {text[0]=a[0].toString();yield null;}default->throw new UnsupportedOperationException(m.getName());
        });
        PsiFile file=(PsiFile)Proxy.newProxyInstance(PsiFile.class.getClassLoader(),new Class[]{PsiFile.class},(p,m,a)->switch(m.getName()) {
            case "getFileType" -> VernacFileType.INSTANCE;case "getContainingFile" -> p;default->throw new UnsupportedOperationException(m.getName());
        });
        var service=new VernacFormattingService();assertTrue(service.canFormat(file));
        var context=FormattingContext.create(file,new TextRange(0,text[0].length()),new com.intellij.psi.codeStyle.CodeStyleSettings(false,false){},com.intellij.formatting.FormattingMode.REFORMAT);
        service.formatDocument(document,List.of(new TextRange(10,20)),context,true,false);assertEquals("namespace app;permission Rights{VIEW;}",text[0]);
        service.formatDocument(document,List.of(new TextRange(0,text[0].length())),context,true,false);
        assertEquals("namespace app;\n\npermission Rights {\n    VIEW;\n}\n",text[0]);
        String once=text[0];service.formatDocument(document,List.of(new TextRange(0,text[0].length())),context,true,false);assertEquals(once,text[0]);
    }
    @Test void standardReformatServiceIsRegisteredInThePluginDescriptor() throws Exception {
        String descriptor=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/META-INF/plugin.xml"));
        assertTrue(descriptor.contains("<formattingService implementation=\"org.vernac.intellij.VernacFormattingService\"/>"));
    }
}
