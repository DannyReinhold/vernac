package org.example.tasks.adapter.inbound.web;

import jakarta.servlet.http.HttpServletResponse;

import org.example.tasks.domain.*;
import org.example.tasks.usecase.*;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.vernac.runtime.DomainValidationException;

import java.util.UUID;

/** HTTP adaptation only: business rules and transactions belong to the Vernac usecases. */
@Controller
@NullMarked
public class TaskController {
    private final ReadTasks readTasks;
    private final CreateTask createTask;
    private final CompleteTask completeTask;
    private final ReopenTask reopenTask;

    public TaskController(
            ReadTasks readTasks,
            CreateTask createTask,
            CompleteTask completeTask,
            ReopenTask reopenTask) {
        this.readTasks = readTasks;
        this.createTask = createTask;
        this.completeTask = completeTask;
        this.reopenTask = reopenTask;
    }

    @GetMapping("/")
    public String board(@RequestParam(defaultValue = "1") int page, Model model) {
        var tasks = readTasks.execute(PageNumber.of(page));
        if (page > 1 && tasks.items().isEmpty()) {
            return "redirect:/?page="
                    + Math.max(1, Math.min(Integer.MAX_VALUE, tasks.totalPages()));
        }
        model.addAttribute("tasks", tasks);
        model.addAttribute("pageNumber", page);
        model.addAttribute("taskTitle", "");
        return "board";
    }

    @PostMapping("/tasks")
    public String create(
            @RequestParam String title,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirect) {
        try {
            createTask.execute(TaskTitle.of(title.strip()));
        } catch (DomainValidationException invalid) {
            board(1, model);
            model.addAttribute("taskTitle", title);
            model.addAttribute("formError", invalid.getMessage());
            response.setStatus(400);
            return "board";
        }
        redirect.addFlashAttribute("notice", "One small step, ready to go.");
        return "redirect:/";
    }

    @PostMapping("/tasks/{id}/complete")
    public String complete(@PathVariable UUID id, RedirectAttributes redirect) {
        completeTask.execute(TaskId.of(id));
        redirect.addFlashAttribute("notice", "A small win. Nicely done.");
        return "redirect:/";
    }

    @PostMapping("/tasks/{id}/reopen")
    public String reopen(@PathVariable UUID id, RedirectAttributes redirect) {
        reopenTask.execute(TaskId.of(id));
        redirect.addFlashAttribute("notice", "Ready for another look.");
        return "redirect:/";
    }
}
