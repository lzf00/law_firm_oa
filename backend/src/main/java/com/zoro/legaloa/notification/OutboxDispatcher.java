package com.zoro.legaloa.notification;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final JdbcClient jdbcClient;
    private final OutboxEventProcessor processor;

    public OutboxDispatcher(JdbcClient jdbcClient, OutboxEventProcessor processor) {
        this.jdbcClient = jdbcClient;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.jobs.outbox-delay-ms:2000}")
    public void dispatch() {
        List<UUID> eventIds = jdbcClient.sql("""
                        SELECT id
                        FROM outbox_events
                        WHERE status IN ('PENDING', 'RETRY')
                          AND available_at <= now()
                        ORDER BY available_at, created_at
                        LIMIT 50
                        """)
                .query(UUID.class)
                .list();
        for (UUID eventId : eventIds) {
            try {
                processor.process(eventId);
            } catch (Exception exception) {
                log.warn("Outbox event {} failed", eventId, exception);
                processor.recordFailure(eventId, exception);
            }
        }
    }
}
