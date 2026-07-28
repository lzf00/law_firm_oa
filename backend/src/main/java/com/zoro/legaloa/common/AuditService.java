package com.zoro.legaloa.common;

import com.zoro.legaloa.identity.RequestActor;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void success(RequestActor actor, String action, String resourceType, UUID resourceId) {
        record(actor, action, resourceType, resourceId, "SUCCESS", null, Map.of());
    }

    public void failure(
            RequestActor actor,
            String action,
            String resourceType,
            UUID resourceId,
            String reason
    ) {
        record(actor, action, resourceType, resourceId, "FAILURE", reason, Map.of());
    }

    public void record(
            RequestActor actor,
            String action,
            String resourceType,
            UUID resourceId,
            String result,
            String reason,
            Map<String, Object> metadata
    ) {
        HttpServletRequest request = currentRequest();
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(metadata);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            enriched.put("correlationId", correlationId);
        }
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(enriched);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit metadata", exception);
        }
        jdbcClient.sql("""
                        INSERT INTO audit_logs
                            (organization_id, actor_user_id, action, resource_type,
                             resource_id, result, reason, ip_address, user_agent, metadata)
                        VALUES
                            (:organizationId, :actorId, :action, :resourceType,
                             :resourceId, :result, :reason, CAST(:ipAddress AS inet),
                             :userAgent, CAST(:metadata AS jsonb))
                        """)
                .param("organizationId", actor.organizationId())
                .param("actorId", actor.userId())
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("result", result)
                .param("reason", reason)
                .param("ipAddress", request == null ? null : clientIp(request))
                .param("userAgent", request == null ? null : truncate(request.getHeader("User-Agent"), 500))
                .param("metadata", metadataJson)
                .update();
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return truncate(value, 64);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(value.length(), maxLength));
    }
}
