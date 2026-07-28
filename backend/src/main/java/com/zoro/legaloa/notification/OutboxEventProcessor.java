package com.zoro.legaloa.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventProcessor {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OutboxEventProcessor(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(UUID eventId) {
        EventRow event = jdbcClient.sql("""
                        UPDATE outbox_events
                        SET status = 'PROCESSING', locked_at = now()
                        WHERE id = :eventId
                          AND status IN ('PENDING', 'RETRY')
                          AND available_at <= now()
                        RETURNING organization_id, event_type, payload::text
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new EventRow(
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")
                ))
                .optional()
                .orElse(null);
        if (event == null) {
            return;
        }
        if ("NOTIFICATION_CREATE".equals(event.eventType())) {
            createNotification(event.organizationId(), event.payload());
        }
        jdbcClient.sql("""
                        UPDATE outbox_events
                        SET status = 'PROCESSED', processed_at = now(),
                            locked_at = NULL, last_error = NULL
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID eventId, Exception exception) {
        String error = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        jdbcClient.sql("""
                        UPDATE outbox_events
                        SET attempts = attempts + 1,
                            status = CASE WHEN attempts + 1 >= 8 THEN 'DEAD' ELSE 'RETRY' END,
                            available_at = now() + (
                                LEAST(3600, CAST(power(2, attempts + 1) AS integer)) * interval '1 second'
                            ),
                            locked_at = NULL,
                            last_error = :error
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .param("error", error.substring(0, Math.min(error.length(), 1000)))
                .update();
    }

    private void createNotification(UUID organizationId, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            jdbcClient.sql("""
                            INSERT INTO notification_inbox
                                (organization_id, recipient_user_id, notification_type,
                                 title, content, resource_type, resource_id, action_url,
                                 priority, deduplication_key)
                            VALUES
                                (:organizationId, :recipientUserId, :notificationType,
                                 :title, :content, :resourceType, :resourceId, :actionUrl,
                                 :priority, :deduplicationKey)
                            ON CONFLICT (recipient_user_id, deduplication_key) DO NOTHING
                            """)
                    .param("organizationId", organizationId)
                    .param("recipientUserId", UUID.fromString(node.path("recipientUserId").asText()))
                    .param("notificationType", node.path("notificationType").asText())
                    .param("title", node.path("title").asText())
                    .param("content", node.path("content").asText())
                    .param("resourceType", nullableText(node, "resourceType"))
                    .param("resourceId", nullableUuid(node, "resourceId"))
                    .param("actionUrl", nullableText(node, "actionUrl"))
                    .param("priority", node.path("priority").asText("NORMAL"))
                    .param("deduplicationKey", node.path("deduplicationKey").asText())
                    .update();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid notification outbox payload", exception);
        }
    }

    private static String nullableText(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull()
                ? null
                : node.path(field).asText();
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private record EventRow(UUID organizationId, String eventType, String payload) {}
}
