package com.zoro.legaloa.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final Counter processedCounter;
    private final Counter failedCounter;
    private final Timer dispatchTimer;

    public OutboxDispatcher(
            JdbcClient jdbcClient,
            OutboxEventProcessor processor,
            MeterRegistry meterRegistry
    ) {
        this.jdbcClient = jdbcClient;
        this.processor = processor;
        this.processedCounter = meterRegistry.counter("law_oa.outbox.processed");
        this.failedCounter = meterRegistry.counter("law_oa.outbox.failed");
        this.dispatchTimer = meterRegistry.timer("law_oa.job.duration", "job", "outbox");
    }

    @Scheduled(fixedDelayString = "${app.jobs.outbox-delay-ms:2000}")
    public void dispatch() {
        dispatchTimer.record(this::dispatchBatch);
    }

    private void dispatchBatch() {
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
                processedCounter.increment();
            } catch (Exception exception) {
                failedCounter.increment();
                log.warn("Outbox event {} failed", eventId, exception);
                processor.recordFailure(eventId, exception);
            }
        }
    }
}
