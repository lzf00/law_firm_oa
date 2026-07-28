package com.zoro.legaloa.matter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @PostMapping
    DeadlineView create(@Valid @RequestBody CreateDeadlineRequest request) {
        return deadlineService.create(request);
    }

    @PutMapping("/{id}")
    DeadlineView update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateDeadlineRequest request
    ) {
        return deadlineService.update(id, request);
    }

    public record CreateDeadlineRequest(
            @NotNull UUID matterId,
            @NotBlank @Size(max = 300) String title,
            @NotNull @Future Instant dueAt,
            @NotBlank @Size(max = 80) String deadlineType,
            @NotNull UUID ownerUserId,
            String priority,
            List<@Min(0) @Max(365) Integer> reminderDaysBefore
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
            String reminderPolicy
    ) {}
}
