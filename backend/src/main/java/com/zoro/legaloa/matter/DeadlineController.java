package com.zoro.legaloa.matter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deadlines")
public class DeadlineController {
    private final DeadlineService deadlineService;

    public DeadlineController(DeadlineService deadlineService) {
        this.deadlineService = deadlineService;
    }

    @GetMapping
    List<DeadlineView> list(@RequestParam(required = false) UUID matterId) {
        return deadlineService.list(matterId);
    }

    @GetMapping("/{id}")
    DeadlineDetailView detail(@PathVariable UUID id) {
        return deadlineService.detail(id);
    }

    @PostMapping
    DeadlineDetailView create(@Valid @RequestBody CreateDeadlineRequest request) {
        return deadlineService.create(request);
    }

    @PutMapping("/{id}")
    DeadlineDetailView update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeadlineRequest request
    ) {
        return deadlineService.update(id, request);
    }

    @PostMapping("/{id}/complete")
    DeadlineDetailView complete(
            @PathVariable UUID id,
            @Valid @RequestBody DeadlineActionRequest request
    ) {
        return deadlineService.complete(id, request);
    }

    @PostMapping("/{id}/cancel")
    DeadlineDetailView cancel(
            @PathVariable UUID id,
            @Valid @RequestBody DeadlineActionRequest request
    ) {
        return deadlineService.cancel(id, request);
    }

    @PostMapping("/{id}/reopen")
    DeadlineDetailView reopen(
            @PathVariable UUID id,
            @Valid @RequestBody DeadlineActionRequest request
    ) {
        return deadlineService.reopen(id, request);
    }

    public record CreateDeadlineRequest(
            @NotNull UUID matterId,
            @NotBlank @Size(max = 300) String title,
            @NotNull @Future Instant dueAt,
            @NotBlank @Size(max = 80) String deadlineType,
            @NotNull UUID ownerUserId,
            @Size(max = 32) String priority,
            @Size(max = 20) List<@Min(0) @Max(365) Integer> reminderDaysBefore,
            @Size(max = 32) String sourceType,
            @Size(max = 500) String sourceReference,
            @Size(max = 4000) String calculationNote
    ) {}

    public record UpdateDeadlineRequest(
            @NotNull @Min(0) Integer expectedVersion,
            @NotNull UUID matterId,
            @NotBlank @Size(max = 300) String title,
            @NotNull @Future Instant dueAt,
            @NotBlank @Size(max = 80) String deadlineType,
            @NotNull UUID ownerUserId,
            @Size(max = 32) String priority,
            @Size(max = 20) List<@Min(0) @Max(365) Integer> reminderDaysBefore,
            @Size(max = 32) String sourceType,
            @Size(max = 500) String sourceReference,
            @Size(max = 4000) String calculationNote
    ) {}

    public record DeadlineActionRequest(
            @NotNull @Min(0) Integer expectedVersion,
            @NotBlank @Size(max = 1000) String note
    ) {}

    public record DeadlineView(
            UUID id,
            UUID matterId,
            String matterNumber,
            String matterTitle,
            String title,
            Instant dueAt,
            String deadlineType,
            String priority,
            String status,
            UUID ownerUserId,
            String ownerName,
            String reminderPolicy,
            String sourceType,
            String sourceReference,
            String calculationNote,
            String createdByName,
            Instant completedAt,
            String completedByName,
            String completionNote,
            Instant cancelledAt,
            String cancelledByName,
            String cancellationReason,
            int version,
            long eventCount,
            long reminderCount
    ) {}

    public record DeadlineEventView(
            UUID id,
            String action,
            UUID actorUserId,
            String actorDisplayName,
            String fromStatus,
            String toStatus,
            Instant previousDueAt,
            Instant nextDueAt,
            UUID previousOwnerUserId,
            UUID nextOwnerUserId,
            String note,
            Instant occurredAt
    ) {}

    public record DeadlineReminderView(
            UUID id,
            UUID recipientUserId,
            String recipientName,
            LocalDate reminderDate,
            int daysBefore,
            Instant createdAt
    ) {}

    public record DeadlineDetailView(
            DeadlineView deadline,
            List<DeadlineEventView> events,
            List<DeadlineReminderView> reminders
    ) {}
}
