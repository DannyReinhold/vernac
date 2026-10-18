// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.IncorrectOperationException;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.tooling.VernacTypeNavigation;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

class VernacTypeReferenceTest {
    @Test
    void nativeReferenceUsesOnlyTheTypeNameUtf16RangeAndDoesNotOfferPartialRename() {
        String text = "// 🧭\nvalue Pair(example.types.ÜberName);";
        var node =
                (ASTNode)
                        Proxy.newProxyInstance(
                                ASTNode.class.getClassLoader(),
                                new Class<?>[] {ASTNode.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getText", "getChars" -> text;
                                            case "getTextLength" -> text.length();
                                            case "getStartOffset" -> 0;
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected AST access: "
                                                                    + method.getName());
                                        });
        var host = new VernacInjectionHost(node);
        var range =
                new TextRange(
                        text.indexOf("ÜberName"), text.indexOf("ÜberName") + "ÜberName".length());
        var ref =
                new VernacTypeReferenceContributor.TypeReference(
                        host,
                        range,
                        "example.types.ÜberName",
                        new VernacTypeNavigation.Target(Path.of("model.vernac"), "ÜberName", 0, 8),
                        null);
        assertSame(host, ref.getElement());
        assertEquals(range, ref.getRangeInElement());
        assertEquals("ÜberName", ref.getValue());
        assertEquals("example.types.ÜberName", ref.getCanonicalText());
        assertTrue(ref.isSoft());
        assertEquals(0, ref.getVariants().length);
        assertThrows(IncorrectOperationException.class, () -> ref.handleElementRename("Changed"));
        assertThrows(IncorrectOperationException.class, () -> ref.bindToElement(host));
        var javaRef =
                new VernacTypeReferenceContributor.JavaTypeReference(
                        host, range, "java.lang.String");
        assertEquals("java.lang.String", javaRef.getCanonicalText());
        assertEquals(range, javaRef.getRangeInElement());
        assertTrue(javaRef.isSoft());
        assertEquals(0, javaRef.getVariants().length);
        assertThrows(
                IncorrectOperationException.class, () -> javaRef.handleElementRename("Renamed"));
        assertThrows(IncorrectOperationException.class, () -> javaRef.bindToElement(host));
    }

    @Test
    void virtualContractTargetsAreNavigableAndEquivalentByFileAndRange() {
        String text = "namespace example; value Name(String);";
        var virtual = new LightVirtualFile("contract.vernac", text);
        var provider =
                (FileViewProvider)
                        Proxy.newProxyInstance(
                                FileViewProvider.class.getClassLoader(),
                                new Class<?>[] {FileViewProvider.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getVirtualFile")) return virtual;
                                    throw new AssertionError(method.getName());
                                });
        var file =
                (PsiFile)
                        Proxy.newProxyInstance(
                                PsiFile.class.getClassLoader(),
                                new Class<?>[] {PsiFile.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getViewProvider" -> provider;
                                            case "getText" -> text;
                                            case "getTextLength" -> text.length();
                                            case "isValid" -> true;
                                            default -> throw new AssertionError(method.getName());
                                        });
        int start = text.indexOf("Name");
        var first = new VernacTypeNavigationTarget(file, "Name", start, start + 4);
        var second = new VernacTypeNavigationTarget(file, "Name", start, start + 4);
        assertTrue(first.canNavigate());
        assertTrue(first.canNavigateToSource());
        assertTrue(first.isEquivalentTo(second));
        assertThrows(IncorrectOperationException.class, () -> first.setName("Changed"));
        assertEquals("Name", first.getText());
        assertFalse(
                first.isEquivalentTo(
                        new VernacTypeNavigationTarget(file, "Other", start, start + 4)));
    }
}
