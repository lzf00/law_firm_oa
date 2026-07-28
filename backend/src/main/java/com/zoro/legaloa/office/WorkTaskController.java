package com.zoro.legaloa.office;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-tasks")
public class WorkTaskController {
    private final WorkTaskService service;

    public WorkTaskController(WorkTaskService service) {
        this.service = service;
    }

    @GetMapping
    List<WorkTaskView> list() {
        return service.list();
    }

    @PostMapping
    WorkTaskView create(@Valid @RequestBody CreateWorkTaskRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}/status")
    WorkTaskView changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeTaskStatusRequest request
    ) {
        return service.changeStatus(id, request);
    }

    @PostMapping("/{id}/comments")
    TaskCommentView comment(
            @PathVariable UUID id,
            @Valid @RequestBody AddTaskCommentRequest request
    ) {
        return service.addComment(id, request);
    }

    public record CreateWorkTaskRequest(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 5000) String description,
            @NotNull UUID ownerUserId,
            List<UUID> participantUserIds,
            @Future Instant dueAt,
            @Size(max = 32) String priority,
            @Size(max = 80) String relatedBusinessType,
            UUID relatedBusinessId
    ) {}

    public record ChangeTaskStatusRequest(@NotBlank @Size(max = 32) String status) {}

    public record AddTaskCommentRequest(@NotBlank @Size(max = 1000) String content) {}

    public record TaskCommentView(
            UUID id, String authorName, String content, Instant createdAt
    ) {}

    public record WorkTaskView(
            UUID id,
            String title,
            String description,
            String status,
            String priority,
            UUID ownerUserId,
            String ownerName,
            String assignerName,
            Instant dueAt,
            Instant completedAt,
            Instant createdAt,
            long commentCount,
            UUID officeId,
            String officeNameZh,
            String officeNameEn
    ) {}
}
