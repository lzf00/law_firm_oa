package com.zoro.legaloa.archive;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/archives")
public class ArchiveController {
    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @GetMapping
    List<ArchiveVolumeView> list() {
        return archiveService.list();
    }

    @PostMapping
    ArchiveVolumeView create(@Valid @RequestBody CreateArchiveVolumeRequest request) {
        return archiveService.create(request);
    }

    @PostMapping("/{archiveId}/items")
    ArchiveVolumeView addItem(
            @PathVariable UUID archiveId,
            @Valid @RequestBody AddArchiveItemRequest request
    ) {
        return archiveService.addItem(archiveId, request);
    }

    public record CreateArchiveVolumeRequest(
            @NotBlank @Size(max = 100) String archiveNumber,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 80) String retentionPolicyCode
    ) {}

    public record AddArchiveItemRequest(@NotNull UUID documentId) {}

    public record ArchiveVolumeView(
            UUID id,
            String archiveNumber,
            String title,
            String retentionPolicyCode,
            String status,
            Instant archivedAt,
            String createdByName,
            Instant createdAt,
            int itemCount
    ) {}
}
