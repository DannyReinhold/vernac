// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import com.intellij.formatting.FormattingContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;
class VernacFormattingServiceTest {
    @Test void fullFileReformatUsesTheCompilerFormatterAndSelectionDoesNotChangeUnselectedText() {
        String[] text={"namespace app;permission Rights{VIEW;}"};
        Document document = document(text);
        PsiFile file = file(null);
        var service=new VernacFormattingService();assertTrue(service.canFormat(file));
        var context=FormattingContext.create(file,new TextRange(0,text[0].length()),new com.intellij.psi.codeStyle.CodeStyleSettings(false,false){},com.intellij.formatting.FormattingMode.REFORMAT);
        service.formatDocument(document,List.of(new TextRange(10,20)),context,true,false);assertEquals("namespace app;permission Rights{VIEW;}",text[0]);
        service.formatDocument(document,List.of(new TextRange(0,text[0].length())),context,true,false);
        assertEquals("namespace app;\n\npermission Rights {\n    VIEW;\n}\n",text[0]);
        String once=text[0];service.formatDocument(document,List.of(new TextRange(0,text[0].length())),context,true,false);assertEquals(once,text[0]);
    }
    @Test void testSourceUsesTheScenarioFormatter() {
        String[] text = {"namespace app;suite Tasks{scenario opens{expect true equals true;}}"};
        var document = document(text);
        var file = file(new LightVirtualFile("tasks.vernac") {
            @Override public String getPath() { return "/app/src/test/vernac/app/tasks.vernac"; }
            @Override public boolean isInLocalFileSystem() { return true; }
        });
        var range = new TextRange(0, text[0].length());
        var context = FormattingContext.create(file, range,
                new com.intellij.psi.codeStyle.CodeStyleSettings(false, false) {},
                com.intellij.formatting.FormattingMode.REFORMAT);
        new VernacFormattingService().formatDocument(document, List.of(range), context, true, false);
        assertTrue(text[0].contains("        expect true equals true;"), text[0]);
    }

    private static Document document(String[] text) {
        return (Document) Proxy.newProxyInstance(Document.class.getClassLoader(), new Class[]{Document.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getText" -> text[0];
                    case "getTextLength" -> text[0].length();
                    case "setText" -> { text[0] = args[0].toString(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PsiFile file(VirtualFile virtualFile) {
        return (PsiFile) Proxy.newProxyInstance(PsiFile.class.getClassLoader(), new Class[]{PsiFile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFileType" -> VernacFileType.INSTANCE;
                    case "getContainingFile" -> proxy;
                    case "getVirtualFile" -> virtualFile;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void standardReformatServiceIsRegisteredInThePluginDescriptor() throws Exception {
        String descriptor=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/META-INF/plugin.xml"));
        assertTrue(descriptor.contains("<formattingService implementation=\"org.vernac.intellij.VernacFormattingService\"/>"));
    }
}
