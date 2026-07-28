package com.zoro.legaloa.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OperationalMetrics {
    private final JdbcClient jdbcClient;
    private final Map<String, AtomicLong> gauges = new LinkedHashMap<>();
    private final Counter refreshFailures;

    public OperationalMetrics(JdbcClient jdbcClient, MeterRegistry meterRegistry) {
        this.jdbcClient = jdbcClient;
        this.refreshFailures = meterRegistry.counter("law_oa.metrics.refresh.failures");
        register(meterRegistry, "outbox.pending");
        register(meterRegistry, "outbox.dead");
        register(meterRegistry, "documents.scan_queue");
        register(meterRegistry, "notifications.unread");
        register(meterRegistry, "security_events.open_high");
        register(meterRegistry, "deadlines.overdue");
        register(meterRegistry, "workflow.active_tasks");
    }

    @Scheduled(fixedDelayString = "${app.jobs.metrics-refresh:PT1M}")
    public void refresh() {
        try {
            set("outbox.pending", count("""
                    SELECT count(*) FROM outbox_events
                    WHERE status IN ('PENDING', 'RETRY')
                    """));
            set("outbox.dead", count("""
                    SELECT count(*) FROM outbox_events WHERE status = 'DEAD'
                    """));
            set("documents.scan_queue", count("""
                    SELECT count(*) FROM document_versions
                    WHERE ingestion_status IN ('QUARANTINED', 'SCANNING')
                    """));
            set("notifications.unread", count("""
                    SELECT count(*) FROM notifications WHERE read_at IS NULL
                    """));
            set("security_events.open_high", count("""
                    SELECT count(*) FROM document_security_events
                    WHERE severity = 'HIGH' AND status = 'OPEN'
                    """));
            set("deadlines.overdue", count("""
                    SELECT count(*) FROM deadlines WHERE status = 'OVERDUE'
                    """));
            set("workflow.active_tasks", count("""
                    SELECT count(*) FROM ACT_RU_TASK
                    """));
        } catch (RuntimeException exception) {
            refreshFailures.increment();
        }
    }

    private void register(MeterRegistry meterRegistry, String suffix) {
        AtomicLong value = new AtomicLong();
        gauges.put(suffix, value);
        Gauge.builder("law_oa." + suffix, value, AtomicLong::get)
                .description("Law OA operational backlog gauge")
                .register(meterRegistry);
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private void set(String suffix, long value) {
        gauges.get(suffix).set(value);
    }
}
