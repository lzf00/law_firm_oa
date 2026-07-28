package com.zoro.legaloa.office;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/announcements")
public class AnnouncementController {
    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    List<AnnouncementView> list() {
        return service.list();
    }

    @PostMapping
    AnnouncementView create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/publish")
    AnnouncementView publish(@PathVariable UUID id) {
        return service.publish(id);
    }

    @PostMapping("/{id}/read")
    AnnouncementView markRead(@PathVariable UUID id) {
        return service.markRead(id);
    }

    public record CreateAnnouncementRequest(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 500) String summary,
            @NotBlank @Size(max = 10000) String content,
            @Size(max = 80) String category,
            @Size(max = 32) String priority,
            @Size(max = 32) String audienceType,
            List<UUID> targetIds,
            @Future Instant expiresAt,
            UUID officeId
    ) {}

    public record AnnouncementView(
            UUID id,
            String title,
            String summary,
            String content,
            String category,
            String priority,
            String status,
            String audienceType,
            String publisherName,
            Instant publishedAt,
            Instant expiresAt,
            long readCount,
            boolean read,
            UUID officeId,
            String officeNameZh,
            String officeNameEn
    ) {}
}
