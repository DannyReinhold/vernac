// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.*;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiManager;
import com.sun.jdi.*;
import com.sun.jdi.request.ClassPrepareRequest;

import org.vernac.compiler.debug.BehaviorSourceMap;

import java.util.*;

final class VernacPositionManager implements PositionManager {
    private final DebugProcess process;

    VernacPositionManager(DebugProcess process) {
        this.process = process;
    }

    static boolean matches(ReferenceType type, VernacDebugSources.Bound bound) {
        try {
            var field = type.fieldByName(BehaviorSourceMap.HASH_FIELD);
            return field != null
                    && type.getValue(field) instanceof StringReference hash
                    && hash.value().equals(bound.entry().runtimeHash());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public SourcePosition getSourcePosition(Location location) throws NoDataException {
        if (VernacDebugSettings.get(process.getProject()).generatedJava)
            throw NoDataException.INSTANCE;
        var candidates =
                VernacDebugSources.all(process.getProject()).stream()
                        .filter(
                                bound -> {
                                    var e = bound.entry();
                                    return e.javaClass().equals(location.declaringType().name())
                                            && e.method().equals(location.method().name())
                                            && e.javaLine() == location.lineNumber()
                                            && VernacDebugSources.current(bound)
                                            && matches(location.declaringType(), bound);
                                })
                        .distinct()
                        .toList();
        if (candidates.size() == 1) {
            var bound = candidates.getFirst();
            var position =
                    ReadAction.computeBlocking(
                            () -> {
                                var file =
                                        VirtualFileManager.getInstance()
                                                .findFileByNioPath(bound.source());
                                var psi =
                                        file == null
                                                ? null
                                                : PsiManager.getInstance(process.getProject())
                                                        .findFile(file);
                                if (psi == null) return null;
                                var source =
                                        SourcePosition.createFromLine(
                                                psi, bound.entry().sourceLine() - 1);
                                var java =
                                        VernacEvaluationContext.position(
                                                process.getProject(), bound);
                                return java == null
                                        ? source
                                        : new VernacEvaluationPosition(source, java);
                            });
            if (position != null) return position;
        }
        throw NoDataException.INSTANCE;
    }

    private List<VernacDebugSources.Bound> entries(SourcePosition position) throws NoDataException {
        var file = position.getFile().getVirtualFile();
        if (file == null || !file.isInLocalFileSystem()) throw NoDataException.INSTANCE;
        var entries =
                VernacDebugSources.at(
                        process.getProject(), file.toNioPath(), position.getLine() + 1);
        if (entries.isEmpty()) throw NoDataException.INSTANCE;
        return entries;
    }

    @Override
    public List<ReferenceType> getAllClasses(SourcePosition position) throws NoDataException {
        List<ReferenceType> result = new ArrayList<>();
        for (var bound : entries(position))
            for (var type :
                    process.getVirtualMachineProxy().classesByName(bound.entry().javaClass()))
                if (matches(type, bound) && !result.contains(type)) result.add(type);
        return result;
    }

    @Override
    public List<Location> locationsOfLine(ReferenceType type, SourcePosition position)
            throws NoDataException {
        List<Location> result = new ArrayList<>();
        for (var bound : entries(position))
            if (bound.entry().javaClass().equals(type.name()) && matches(type, bound))
                try {
                    for (var location : type.locationsOfLine(bound.entry().javaLine()))
                        if (location.method().name().equals(bound.entry().method())
                                && !result.contains(location)) result.add(location);
                } catch (AbsentInformationException ignored) {
                }
        return result;
    }

    @Override
    public ClassPrepareRequest createPrepareRequest(
            ClassPrepareRequestor requestor, SourcePosition position) throws NoDataException {
        var names = entries(position).stream().map(b -> b.entry().javaClass()).distinct().toList();
        if (names.size() != 1) throw NoDataException.INSTANCE;
        return process.getRequestsManager().createClassPrepareRequest(requestor, names.getFirst());
    }
}
