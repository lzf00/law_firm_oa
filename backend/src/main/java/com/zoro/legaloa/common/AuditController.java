package com.zoro.legaloa.common;

import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;

    public AuditController(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    AuditPage list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "AUDIT_VIEW");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String safeAction = action == null ? "" : action.trim();
        String safeResourceType = resourceType == null ? "" : resourceType.trim();
        UUID safeActorId = actorUserId == null ? new UUID(0, 0) : actorUserId;
        long total = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE organization_id = :organizationId
                          AND (:allActions OR action = :action)
                          AND (:allResourceTypes OR resource_type = :resourceType)
                          AND (:allActors OR actor_user_id = :actorUserId)
                        """)
                .param("organizationId", actor.organizationId())
                .param("allActions", safeAction.isBlank())
                .param("action", safeAction)
                .param("allResourceTypes", safeResourceType.isBlank())
                .param("resourceType", safeResourceType)
                .param("allActors", actorUserId == null)
                .param("actorUserId", safeActorId)
                .query(Long.class)
                .single();
        List<AuditView> items = jdbcClient.sql("""
                        SELECT al.id, al.actor_user_id, u.display_name AS actor_name,
                               al.action, al.resource_type, al.resource_id, al.result,
                               al.reason, host(al.ip_address) AS ip_address, al.user_agent,
                               al.metadata::text, al.occurred_at
                        FROM audit_logs al
                        LEFT JOIN users u ON u.id = al.actor_user_id
                        WHERE al.organization_id = :organizationId
                          AND (:allActions OR al.action = :action)
                          AND (:allResourceTypes OR al.resource_type = :resourceType)
                          AND (:allActors OR al.actor_user_id = :actorUserId)
                        ORDER BY al.occurred_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("organizationId", actor.organizationId())
                .param("allActions", safeAction.isBlank())
                .param("action", safeAction)
                .param("allResourceTypes", safeResourceType.isBlank())
                .param("resourceType", safeResourceType)
                .param("allActors", actorUserId == null)
                .param("actorUserId", safeActorId)
                .param("limit", safeSize)
                .param("offset", (safePage - 1) * safeSize)
                .query((rs, rowNum) -> new AuditView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("result"),
                        rs.getString("reason"),
                        rs.getString("ip_address"),
                        rs.getString("user_agent"),
                        rs.getString("metadata"),
                        rs.getTimestamp("occurred_at").toInstant()
                ))
                .list();
        return new AuditPage(items, safePage, safeSize, total);
    }

    public record AuditPage(List<AuditView> items, int page, int size, long total) {}

    public record AuditView(
            UUID id,
            UUID actorUserId,
            String actorName,
            String action,
            String resourceType,
            UUID resourceId,
            String result,
            String reason,
            String ipAddress,
            String userAgent,
            String metadata,
            Instant occurredAt
    ) {}
}
