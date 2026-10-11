// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.psi.PsiDirectory;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class VernacFileTemplateHandlerTest {
    private FileTemplate template(String name, String extension) {
        return (FileTemplate) Proxy.newProxyInstance(FileTemplate.class.getClassLoader(),
                new Class<?>[]{FileTemplate.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getExtension" -> extension;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void hidesOnlyOwnedTemplatesFromTheGenericMenu() {
        var handler = new VernacFileTemplateHandler();
        assertTrue(handler.handlesTemplate(template("Vernac File", "vernac")));
        assertTrue(handler.handlesTemplate(template("Vernac Getting Started", "vernac")));
        assertFalse(handler.handlesTemplate(template("My Model", "vernac")));
        assertFalse(handler.handlesTemplate(template("Vernac File", "java")));
        assertFalse(handler.canCreate(new PsiDirectory[0]));
    }
}
