package com.zoro.legaloa.notification;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    NotificationService.NotificationPage list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return notificationService.list(status, page, size);
    }

    @GetMapping("/unread-count")
    Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCount());
    }

    @PatchMapping("/{id}/read")
    void markRead(@PathVariable UUID id) {
        notificationService.markRead(id);
    }
}
