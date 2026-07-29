package com.zoro.legaloa.matter;

import com.zoro.legaloa.notification.OutboxService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeadlineJob {
    private final JdbcClient jdbcClient;
    private final OutboxService outboxService;

    public DeadlineJob(JdbcClient jdbcClient, OutboxService outboxService) {
        this.jdbcClient = jdbcClient;
        this.outboxService = outboxService;
    }

    @Scheduled(cron = "${app.jobs.deadline-cron:0 5 * * * *}")
    @Transactional
    public void updateAndRemind() {
        jdbcClient.sql("""
                        WITH transitioned AS (
                            UPDATE deadlines
                            SET status = 'OVERDUE',
                                updated_at = now(),
                                version = version + 1
                            WHERE status = 'OPEN' AND due_at < now()
                            RETURNING id, matter_id, due_at, owner_user_id
                        )
                        INSERT INTO deadline_lifecycle_events
                            (organization_id, deadline_id, action, actor_display_name,
                             from_status, to_status, previous_due_at, next_due_at,
                             previous_owner_user_id, next_owner_user_id, note)
                        SELECT m.organization_id, t.id, 'OVERDUE_MARKED', 'SYSTEM',
                               'OPEN', 'OVERDUE', t.due_at, t.due_at,
                               t.owner_user_id, t.owner_user_id,
                               'Automatically marked overdue by deadline scheduler'
                        FROM transitioned t
                        JOIN matters m ON m.id = t.matter_id
                        """)
                .update();
        List<ReminderCandidate> candidates = jdbcClient.sql("""
                        SELECT d.id, m.organization_id, d.owner_user_id, d.title,
                               d.due_at::date AS due_date,
                               (d.due_at::date - current_date) AS days_before,
                               d.matter_id
                        FROM deadlines d
                        JOIN matters m ON m.id = d.matter_id
                        WHERE d.status IN ('OPEN', 'OVERDUE')
                          AND (
                              (d.status = 'OVERDUE' AND d.due_at::date < current_date)
                              OR EXISTS (
                                  SELECT 1
                                  FROM jsonb_array_elements_text(
                                      COALESCE(d.reminder_policy->'daysBefore', '[]'::jsonb)
                                  ) configured(day)
                                  WHERE configured.day::integer =
                                      (d.due_at::date - current_date)
                              )
                          )
                        ORDER BY d.due_at
                        """)
                .query((rs, rowNum) -> new ReminderCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("title"),
                        rs.getDate("due_date").toLocalDate(),
                        rs.getInt("days_before"),
                        rs.getObject("matter_id", UUID.class)
                ))
                .list();
        for (ReminderCandidate candidate : candidates) {
            int inserted = jdbcClient.sql("""
                            INSERT INTO deadline_reminder_dispatches
                                (deadline_id, recipient_user_id, reminder_date, days_before)
                            VALUES (:deadlineId, :recipientUserId, current_date, :daysBefore)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("deadlineId", candidate.deadlineId())
                    .param("recipientUserId", candidate.recipientUserId())
                    .param("daysBefore", candidate.daysBefore())
                    .update();
            if (inserted == 0) {
                continue;
            }
            String content = candidate.daysBefore() < 0
                    ? "期限已逾期：" + candidate.title()
                    : candidate.title() + " 将于 " + candidate.dueDate() + " 到期";
            UUID outboxId = outboxService.enqueueNotification(
                    candidate.organizationId(),
                    candidate.recipientUserId(),
                    candidate.daysBefore() < 0 ? "DEADLINE_OVERDUE" : "DEADLINE_REMINDER",
                    candidate.daysBefore() < 0 ? "案件期限逾期" : "案件期限提醒",
                    content,
                    "MATTER",
                    candidate.matterId(),
                    "/deadlines?matterId=" + candidate.matterId(),
                    candidate.daysBefore() <= 1 ? "URGENT" : "HIGH",
                    "deadline:" + candidate.deadlineId() + ":" + LocalDate.now()
            );
            jdbcClient.sql("""
                            UPDATE deadline_reminder_dispatches
                            SET outbox_event_id = :outboxId
                            WHERE deadline_id = :deadlineId
                              AND recipient_user_id = :recipientUserId
                              AND reminder_date = current_date
                              AND days_before = :daysBefore
                            """)
                    .param("outboxId", outboxId)
                    .param("deadlineId", candidate.deadlineId())
                    .param("recipientUserId", candidate.recipientUserId())
                    .param("daysBefore", candidate.daysBefore())
                    .update();
        }
    }

    private record ReminderCandidate(
            UUID deadlineId,
            UUID organizationId,
            UUID recipientUserId,
            String title,
            LocalDate dueDate,
            int daysBefore,
            UUID matterId
    ) {}
}
