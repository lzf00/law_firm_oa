package com.zoro.legaloa.notification;

import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;

    public NotificationService(JdbcClient jdbcClient, RequestActorProvider actorProvider) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(String status, int page, int size) {
        RequestActor actor = actorProvider.current();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        boolean allStatuses = status == null || status.isBlank();
        long total = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM notification_inbox
                        WHERE organization_id = :organizationId
                          AND recipient_user_id = :userId
                          AND (:allStatuses OR status = :status)
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("allStatuses", allStatuses)
                .param("status", allStatuses ? "" : status.toUpperCase())
                .query(Long.class)
                .single();
        List<NotificationView> items = jdbcClient.sql("""
                        SELECT id, notification_type, title, content, resource_type,
                               resource_id, action_url, priority, status, read_at, created_at
                        FROM notification_inbox
                        WHERE organization_id = :organizationId
                          AND recipient_user_id = :userId
                          AND (:allStatuses OR status = :status)
                        ORDER BY created_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("allStatuses", allStatuses)
                .param("status", allStatuses ? "" : status.toUpperCase())
                .param("limit", safeSize)
                .param("offset", (safePage - 1) * safeSize)
                .query((rs, rowNum) -> new NotificationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("notification_type"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("action_url"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
        return new NotificationPage(items, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        RequestActor actor = actorProvider.current();
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM notification_inbox
                        WHERE organization_id = :organizationId
                          AND recipient_user_id = :userId
                          AND status = 'UNREAD'
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .query(Long.class)
                .single();
    }

    @Transactional
    public void markRead(UUID id) {
        RequestActor actor = actorProvider.current();
        int updated = jdbcClient.sql("""
                        UPDATE notification_inbox
                        SET status = 'READ', read_at = COALESCE(read_at, now())
                        WHERE id = :id
                          AND organization_id = :organizationId
                          AND recipient_user_id = :userId
                          AND status <> 'ARCHIVED'
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "NOTIFICATION_NOT_FOUND", "通知不存在", HttpStatus.NOT_FOUND
            );
        }
    }

    public record NotificationPage(
            List<NotificationView> items, int page, int size, long total
    ) {}

    public record NotificationView(
            UUID id,
            String notificationType,
            String title,
            String content,
            String resourceType,
            UUID resourceId,
            String actionUrl,
            String priority,
            String status,
            Instant readAt,
            Instant createdAt
    ) {}
}
