package org.example.tasks.adapter.inbound.web;

import org.jspecify.annotations.NullMarked;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.vernac.runtime.AggregateNotFoundException;
import org.vernac.runtime.DomainValidationException;

/** Keep technical exception details in server logs, not in rendered pages. */
@ControllerAdvice(annotations = Controller.class)
@NullMarked
public class WebErrors {
    @ExceptionHandler({
        DomainValidationException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalid(Model model) {
        model.addAttribute("heading", "Let's try that again.");
        model.addAttribute("detail", "Check the supplied values and try again.");
        return "problem";
    }

    @ExceptionHandler(AggregateNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String missing(Model model) {
        model.addAttribute("heading", "That task is no longer here.");
        model.addAttribute("detail", "Return to your board for the latest tasks.");
        return "problem";
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String conflict(Model model) {
        model.addAttribute("heading", "Something changed while you were here.");
        model.addAttribute(
                "detail", "Someone else updated this task. Refresh the board before trying again.");
        return "problem";
    }
}
