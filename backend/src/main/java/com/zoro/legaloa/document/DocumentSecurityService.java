package com.zoro.legaloa.document;

import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class DocumentSecurityService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;

    public DocumentSecurityService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
    }

    public DocumentSecurityPolicy.Decision beforeDownload(
            RequestActor actor,
            UUID documentId,
            UUID versionId
    ) {
        jdbcClient.sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                .param("userId", actor.userId())
                .query(UUID.class)
                .single();
        Integer recentDownloads = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM document_access_logs
                        WHERE user_id = :userId
                          AND action = 'DOWNLOAD' AND result = 'ALLOWED'
                          AND occurred_at >= now() - make_interval(secs => :windowSeconds)
                        """)
                .param("userId", actor.userId())
                .param("windowSeconds", DocumentSecurityPolicy.WINDOW_SECONDS)
                .query(Integer.class)
                .single();
        DocumentSecurityPolicy.Decision decision =
                DocumentSecurityPolicy.evaluate(recentDownloads);
        if (decision == DocumentSecurityPolicy.Decision.BLOCK) {
            recordAccess(documentId, versionId, actor.userId(), "DENIED");
            recordEvent(
                    actor, documentId, versionId, "DOWNLOAD_RATE_LIMITED", "HIGH",
                    recentDownloads, "{\"reason\":\"download_rate_limit\"}"
            );
            throw new BusinessException(
                    "DOCUMENT_DOWNLOAD_RATE_LIMITED",
                    "短时间内下载次数过多，请稍后重试；本次操作已记录",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
        return decision;
    }

    public void recordAllowed(
            RequestActor actor,
            UUID documentId,
            UUID versionId,
            DocumentSecurityPolicy.Decision decision
    ) {
        recordAccess(documentId, versionId, actor.userId(), "ALLOWED");
        if (decision == DocumentSecurityPolicy.Decision.ALLOW_AND_WARN) {
            recordEvent(
                    actor, documentId, versionId, "DOWNLOAD_BURST_WARNING", "MEDIUM",
                    DocumentSecurityPolicy.WARNING_THRESHOLD,
                    "{\"reason\":\"download_burst_threshold\"}"
            );
        }
    }

    public List<SecurityEventView> events(String status, int limit) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "DOCUMENT_SECURITY_VIEW");
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.toUpperCase(java.util.Locale.ROOT);
        if (normalizedStatus != null
                && !java.util.Set.of("OPEN", "REVIEWED", "CLOSED").contains(normalizedStatus)) {
            throw new BusinessException(
                    "DOCUMENT_SECURITY_STATUS_INVALID", "文档安全事件状态无效",
                    HttpStatus.BAD_REQUEST
            );
        }
        return jdbcClient.sql("""
                        SELECT e.id, e.document_id, e.document_version_id, e.user_id,
                               u.display_name AS user_name, d.logical_name AS document_name,
                               e.event_type, e.severity, e.status, e.download_count,
                               e.window_seconds, host(e.ip_address) AS ip_address,
                               e.correlation_id, e.occurred_at
                        FROM document_security_events e
                        JOIN users u ON u.id = e.user_id
                        LEFT JOIN documents d ON d.id = e.document_id
                        WHERE e.organization_id = :organizationId
                          AND (:allStatuses OR e.status = :status)
                        ORDER BY e.occurred_at DESC
                        LIMIT :limit
                        """)
                .param("organizationId", actor.organizationId())
                .param("allStatuses", normalizedStatus == null)
                .param("status", normalizedStatus == null ? "" : normalizedStatus)
                .param("limit", Math.max(1, Math.min(limit, 100)))
                .query((rs, rowNum) -> new SecurityEventView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("user_name"),
                        rs.getString("document_name"),
                        rs.getString("event_type"),
                        rs.getString("severity"),
                        rs.getString("status"),
                        rs.getInt("download_count"),
                        rs.getInt("window_seconds"),
                        rs.getString("ip_address"),
                        rs.getString("correlation_id"),
                        toInstant(rs.getTimestamp("occurred_at"))
                ))
                .list();
    }

    private void recordAccess(UUID documentId, UUID versionId, UUID userId, String result) {
        RequestMetadata request = requestMetadata();
        jdbcClient.sql("""
                        INSERT INTO document_access_logs
                            (document_id, document_version_id, user_id, action, result,
                             ip_address, user_agent)
                        VALUES
                            (:documentId, :versionId, :userId, 'DOWNLOAD', :result,
                             CAST(:ipAddress AS inet), :userAgent)
                        """)
                .param("documentId", documentId)
                .param("versionId", versionId)
                .param("userId", userId)
                .param("result", result)
                .param("ipAddress", request.ipAddress())
                .param("userAgent", request.userAgent())
                .update();
    }

    private void recordEvent(
            RequestActor actor,
            UUID documentId,
            UUID versionId,
            String eventType,
            String severity,
            int downloadCount,
            String details
    ) {
        RequestMetadata request = requestMetadata();
        jdbcClient.sql("""
                        INSERT INTO document_security_events
                            (organization_id, document_id, document_version_id, user_id,
                             event_type, severity, download_count, window_seconds,
                             ip_address, user_agent, correlation_id, details)
                        VALUES
                            (:organizationId, :documentId, :versionId, :userId,
                             :eventType, :severity, :downloadCount, :windowSeconds,
                             CAST(:ipAddress AS inet), :userAgent, :correlationId,
                             CAST(:details AS jsonb))
                        """)
                .param("organizationId", actor.organizationId())
                .param("documentId", documentId)
                .param("versionId", versionId)
                .param("userId", actor.userId())
                .param("eventType", eventType)
                .param("severity", severity)
                .param("downloadCount", downloadCount)
                .param("windowSeconds", DocumentSecurityPolicy.WINDOW_SECONDS)
                .param("ipAddress", request.ipAddress())
                .param("userAgent", request.userAgent())
                .param("correlationId", MDC.get("correlationId"))
                .param("details", details)
                .update();
    }

    private static RequestMetadata requestMetadata() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return new RequestMetadata(null, null);
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return new RequestMetadata(
                truncate(ip, 64), truncate(request.getHeader("User-Agent"), 500)
        );
    }

    private static String truncate(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maxLength));
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record RequestMetadata(String ipAddress, String userAgent) {}

    public record SecurityEventView(
            UUID id,
            UUID documentId,
            UUID documentVersionId,
            UUID userId,
            String userName,
            String documentName,
            String eventType,
            String severity,
            String status,
            int downloadCount,
            int windowSeconds,
            String ipAddress,
            String correlationId,
            Instant occurredAt
    ) {}
}
