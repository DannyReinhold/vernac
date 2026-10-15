// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.*;
import org.springframework.http.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.*;

/** Rendering and PRG mechanics; business decisions and transactions remain in usecases. */
@NullMarked
public final class MvcResponses {
    private MvcResponses() {}

    public static ModelAndView view(
            String template,
            int status,
            @Nullable String modelName,
            @Nullable Object result,
            MvcInputs inputs) {
        var response = new ModelAndView(template);
        response.setStatus(HttpStatusCode.valueOf(status));
        response.addObject("inputs", inputs.publicValues());
        if (modelName != null) response.addObject(modelName, Objects.requireNonNull(result));
        return response;
    }

    public static ModelAndView absent(String template, int status, MvcInputs inputs) {
        var response = view(template, status, null, null, inputs);
        response.addObject(
                "error",
                new InboundHttpErrors.Body("NOT_FOUND", "The requested resource was not found."));
        return response;
    }

    public static ModelAndView error(
            String template, InboundHttpErrors.Reply reply, MvcInputs inputs, Exception error) {
        if (reply.status() >= 500)
            Logger.getLogger(MvcResponses.class.getName())
                    .log(Level.SEVERE, "MVC inbound failure", error);
        var response = view(template, reply.status(), null, null, inputs);
        response.addObject("error", new InboundHttpErrors.Body(reply.code(), reply.message()));
        return response;
    }

    public static ModelAndView redisplay(
            Supplier<ModelAndView> loader,
            InboundHttpErrors.Reply reply,
            MvcInputs inputs,
            String fallback,
            Exception original) {
        try {
            var response = Objects.requireNonNull(loader.get());
            response.setStatus(HttpStatusCode.valueOf(reply.status()));
            response.addObject("inputs", inputs.publicValues());
            response.addObject("error", new InboundHttpErrors.Body(reply.code(), reply.message()));
            if (reply.status() >= 500)
                Logger.getLogger(MvcResponses.class.getName())
                        .log(Level.SEVERE, "MVC inbound failure", original);
            return response;
        } catch (Exception reload) {
            return failure(fallback, inputs, reload);
        }
    }

    public static ModelAndView failure(String fallback, MvcInputs inputs, Exception error) {
        return error(
                fallback,
                new InboundHttpErrors.Reply(
                        500, "INTERNAL_ERROR", "The operation could not be completed."),
                inputs,
                error);
    }

    public static ModelAndView redirect(
            String target, boolean post, @Nullable String notice, HttpServletRequest request) {
        var redirect = new RedirectView(target, true);
        redirect.setExposeModelAttributes(false);
        redirect.setStatusCode(post ? HttpStatus.SEE_OTHER : HttpStatus.FOUND);
        if (notice != null) {
            var flash =
                    Objects.requireNonNull(
                            RequestContextUtils.getOutputFlashMap(request), "No MVC flash context");
            flash.put("notice", notice);
        }
        return new ModelAndView(redirect);
    }
}
