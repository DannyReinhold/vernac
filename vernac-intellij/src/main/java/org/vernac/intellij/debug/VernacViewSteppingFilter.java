// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.engine.*;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.sun.jdi.*;
import com.sun.jdi.request.StepRequest;

/** Only skip verified facade reads; never skip modify wrappers or callback-bearing runtime code. */
public final class VernacViewSteppingFilter implements ExtraSteppingFilter {
    @Override
    public boolean isApplicable(SuspendContext context) {
        try {
            var frame = context.getFrameProxy().getStackFrame();
            var location = frame.location();
            var object = frame.thisObject();
            if (object == null
                    || !VernacViewRenderer.verified(
                            context.getDebugProcess().getProject(), object.referenceType()))
                return false;
            return skippable(object, location.method());
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static boolean skippable(ObjectReference receiver, Method method) {
        return VernacViewInspector.contract(receiver.referenceType()).stream()
                .flatMap(c -> c.getters().stream())
                .anyMatch(
                        m ->
                                m.name().equals(method.name())
                                        && m.signature().equals(method.signature())
                                        && VernacViewInspector.property(
                                                        receiver, m.name(), m.signature())
                                                .isPresent());
    }

    @Override
    public int getStepRequestDepth(SuspendContext context) {
        return StepRequest.STEP_OUT;
    }
}
