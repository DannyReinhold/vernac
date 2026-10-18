// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import static org.junit.jupiter.api.Assertions.*;

import com.intellij.debugger.SourcePosition;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

import org.junit.jupiter.api.Test;

class VernacEvaluationPositionTest {
    @Test
    void navigationRemainsVernacWhileEvaluationUsesJava() {
        var source = new Position(4, 80);
        var java = new Position(17, 600);
        var mapped = new VernacEvaluationPosition(source, java);
        assertEquals(4, mapped.getLine());
        assertEquals(80, mapped.getOffset());
        mapped.getFile();
        assertTrue(source.fileRequested);
        assertFalse(java.fileRequested);
        mapped.getElementAt();
        assertTrue(java.elementRequested);
        assertFalse(source.elementRequested);
        mapped.navigate(true);
        mapped.openEditor(true);
        assertTrue(source.navigated);
        assertTrue(source.opened);
        assertFalse(java.navigated);
        assertFalse(java.opened);
        assertTrue(mapped.canNavigate());
        assertTrue(mapped.canNavigateToSource());
    }

    private static final class Position extends SourcePosition {
        private final int line, offset;
        private boolean fileRequested, elementRequested, navigated, opened;

        Position(int line, int offset) {
            this.line = line;
            this.offset = offset;
        }

        @Override
        public PsiFile getFile() {
            fileRequested = true;
            return null;
        }

        @Override
        public PsiElement getElementAt() {
            elementRequested = true;
            return null;
        }

        @Override
        public int getLine() {
            return line;
        }

        @Override
        public int getOffset() {
            return offset;
        }

        @Override
        public Editor openEditor(boolean focus) {
            opened = true;
            return null;
        }

        @Override
        public void navigate(boolean focus) {
            navigated = true;
        }

        @Override
        public boolean canNavigate() {
            return true;
        }

        @Override
        public boolean canNavigateToSource() {
            return true;
        }
    }
}
