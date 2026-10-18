// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import static org.junit.jupiter.api.Assertions.*;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;

import org.junit.jupiter.api.Test;
import org.vernac.intellij.VernacFileType;

import java.lang.reflect.Proxy;

class VernacExpressionFactoryTest {
    @Test
    void bothExplicitJavaAndImplicitVernacRequestsHaveMatchingFactories() {
        assertSame(JavaFileType.INSTANCE, new VernacCodeFragmentFactory().getFileType());
        assertSame(VernacFileType.INSTANCE, new VernacSourceCodeFragmentFactory().getFileType());
        assertFalse(new VernacCodeFragmentFactory().isContextAccepted(null));
        assertFalse(new VernacSourceCodeFragmentFactory().isContextAccepted(null));
    }

    @Test
    void preservesAnAlreadyResolvedJavaContextAndDoesNotInterceptOrdinaryJava() {
        var file =
                (PsiJavaFile)
                        Proxy.newProxyInstance(
                                PsiJavaFile.class.getClassLoader(),
                                new Class<?>[] {PsiJavaFile.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getFileType"))
                                        return JavaFileType.INSTANCE;
                                    throw new AssertionError(
                                            "Unexpected Java file access: " + method.getName());
                                });
        var element =
                (PsiElement)
                        Proxy.newProxyInstance(
                                PsiElement.class.getClassLoader(),
                                new Class<?>[] {PsiElement.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getContainingFile")) return file;
                                    throw new AssertionError(
                                            "Unexpected context access: " + method.getName());
                                });
        assertSame(element, VernacCodeFragmentFactory.context(element));
        assertFalse(new VernacCodeFragmentFactory().isContextAccepted(element));
        assertFalse(new VernacSourceCodeFragmentFactory().isContextAccepted(element));
    }
}
