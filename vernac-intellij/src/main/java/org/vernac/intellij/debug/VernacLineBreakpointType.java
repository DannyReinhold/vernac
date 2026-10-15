// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.*;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.ui.breakpoints.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.*;
import com.sun.jdi.ReferenceType;

import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties;

import java.util.*;

/** Standard Java breakpoint handling with Vernac source positions and mapped class preparation. */
public final class VernacLineBreakpointType extends JavaLineBreakpointType {
    public VernacLineBreakpointType() {
        super("vernac-line", "Vernac Line Breakpoints");
    }

    @Override
    public String getDisplayName() {
        return "Vernac Line Breakpoints";
    }

    @Override
    public int getPriority() {
        return super.getPriority() + 10;
    }

    @Override
    public boolean canPutAt(VirtualFile file, int line, Project project) {
        return file.isInLocalFileSystem()
                && file.getName().endsWith(".vernac")
                && !VernacDebugSources.at(project, file.toNioPath(), line + 1).isEmpty();
    }

    @Override
    public JavaLineBreakpointProperties createBreakpointProperties(VirtualFile file, int line) {
        return new JavaLineBreakpointProperties();
    }

    @Override
    public List<JavaBreakpointVariant> computeVariants(Project project, XSourcePosition position) {
        return List.of();
    }

    @Override
    public Breakpoint<JavaLineBreakpointProperties> createJavaBreakpoint(
            Project project, XBreakpoint<JavaLineBreakpointProperties> breakpoint) {
        return new LineBreakpoint<JavaLineBreakpointProperties>(project, breakpoint) {
            private List<VernacDebugSources.Bound> bound() {
                var p = breakpoint.getSourcePosition();
                return p == null
                        ? List.of()
                        : VernacDebugSources.at(project, p.getFile().toNioPath(), p.getLine() + 1);
            }

            @Override
            public boolean isValid() {
                return !bound().isEmpty();
            }

            @Override
            public PsiClass getPsiClass() {
                return bound().stream()
                        .findFirst()
                        .map(
                                b ->
                                        JavaPsiFacade.getInstance(project)
                                                .findClass(
                                                        b.entry().javaClass(),
                                                        GlobalSearchScope.allScope(project)))
                        .orElse(null);
            }

            @Override
            protected JavaLineBreakpointType getXBreakpointType() {
                return VernacLineBreakpointType.this;
            }

            @Override
            protected String computeMethodName() {
                return bound().stream().findFirst().map(b -> b.entry().method()).orElse(null);
            }

            @Override
            public void createRequest(DebugProcessImpl process) {
                if (!shouldCreateRequest(process)) return;
                for (String name :
                        bound().stream().map(b -> b.entry().javaClass()).distinct().toList()) {
                    var request =
                            process.getRequestsManager().createClassPrepareRequest(this, name);
                    process.getRequestsManager().enableRequest(request);
                    for (var type : process.getVirtualMachineProxy().classesByName(name))
                        createRequestForPreparedClass(process, type);
                }
            }

            @Override
            protected boolean acceptLocation(
                    DebugProcessImpl process, ReferenceType type, com.sun.jdi.Location location) {
                return bound().stream()
                        .anyMatch(
                                b ->
                                        b.entry().javaClass().equals(type.name())
                                                && b.entry().javaLine() == location.lineNumber()
                                                && b.entry()
                                                        .method()
                                                        .equals(location.method().name())
                                                && VernacPositionManager.matches(type, b));
            }

            @Override
            public PsiElement getEvaluationElement() {
                return bound().stream()
                        .findFirst()
                        .map(
                                b -> {
                                    var file =
                                            com.intellij.openapi.vfs.VirtualFileManager
                                                    .getInstance()
                                                    .findFileByNioPath(b.java());
                                    var psi =
                                            file == null
                                                    ? null
                                                    : PsiManager.getInstance(project)
                                                            .findFile(file);
                                    return psi == null
                                            ? null
                                            : SourcePosition.createFromLine(
                                                            psi, b.entry().javaLine() - 1)
                                                    .getElementAt();
                                })
                        .orElse(null);
            }
        };
    }

    /** Let XLineBreakpoint build its position from the original file URL and line. */
    @Override
    public XSourcePosition getSourcePosition(XBreakpoint<JavaLineBreakpointProperties> breakpoint) {
        // This method is called by XBreakpoint.getSourcePosition(). Calling that accessor
        // here recurses. Returning Java coordinates would also corrupt gutter lookup and
        // request binding, which require the original Vernac position.
        // Evaluation scope belongs in getEvaluationElement / VernacEvaluationPosition.
        return null;
    }

    @Override
    public boolean matchesPosition(LineBreakpoint<?> breakpoint, SourcePosition position) {
        var p = breakpoint.getXBreakpoint().getSourcePosition();
        return p != null
                && p.getLine() == position.getLine()
                && p.getFile().equals(position.getFile().getVirtualFile());
    }
}
