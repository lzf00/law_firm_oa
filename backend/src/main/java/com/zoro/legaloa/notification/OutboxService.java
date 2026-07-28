package com.zoro.legaloa.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.common.CorrelationIdFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public UUID enqueueNotification(
            UUID organizationId,
            UUID recipientUserId,
            String notificationType,
            String title,
            String content,
            String resourceType,
            UUID resourceId,
            String actionUrl,
            String priority,
            String deduplicationKey
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipientUserId", recipientUserId);
        payload.put("notificationType", notificationType);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("resourceType", resourceType);
        payload.put("resourceId", resourceId);
        payload.put("actionUrl", actionUrl);
        payload.put("priority", priority);
        payload.put("deduplicationKey", deduplicationKey);
        return enqueue(organizationId, resourceType, resourceId, "NOTIFICATION_CREATE", payload);
    }

    public UUID enqueue(
            UUID organizationId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Object payload
    ) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize outbox payload", exception);
        }
        return jdbcClient.sql("""
                        INSERT INTO outbox_events
                            (organization_id, aggregate_type, aggregate_id, event_type,
                             payload, correlation_id)
                        VALUES
                            (:organizationId, :aggregateType, :aggregateId, :eventType,
                             CAST(:payload AS jsonb), :correlationId)
                        RETURNING id
                        """)
                .param("organizationId", organizationId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json)
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .query(UUID.class)
                .single();
    }
}
