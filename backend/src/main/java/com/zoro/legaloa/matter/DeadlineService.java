package com.zoro.legaloa.matter;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.matter.DeadlineController.CreateDeadlineRequest;
import com.zoro.legaloa.matter.DeadlineController.DeadlineView;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeadlineService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;

    public DeadlineService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DeadlineView> list(UUID matterId) {
        RequestActor actor = actorProvider.current();
        return jdbcClient.sql("""
                        SELECT d.id, d.matter_id, m.matter_number, m.title AS matter_title,
                               d.title, d.due_at, d.deadline_type, d.priority, d.status,
                               d.owner_user_id, u.display_name AS owner_name,
                               d.reminder_policy::text AS reminder_policy
                        FROM deadlines d
                        JOIN matters m ON m.id = d.matter_id
                        JOIN users u ON u.id = d.owner_user_id
                        WHERE m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND (:allMatters OR d.matter_id = :matterId)
                          AND (
                            EXISTS (
                              SELECT 1 FROM matter_members visible_mm
                              WHERE visible_mm.matter_id = m.id
                                AND visible_mm.user_id = :userId
                                AND visible_mm.left_at IS NULL
                            )
                            OR EXISTS (
                              SELECT 1 FROM user_roles ur
                              JOIN roles r ON r.id = ur.role_id
                              WHERE ur.user_id = :userId
                                AND r.code IN ('ADMIN', 'MANAGING_PARTNER')
                            )
                          )
                        ORDER BY
                          CASE d.status WHEN 'OPEN' THEN 0 WHEN 'OVERDUE' THEN 1 ELSE 2 END,
                          d.due_at
                        LIMIT 300
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("allMatters", matterId == null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .query((rs, rowNum) -> new DeadlineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getString("matter_title"),
                        rs.getString("title"),
                        rs.getTimestamp("due_at").toInstant(),
                        rs.getString("deadline_type"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("owner_name"),
                        rs.getString("reminder_policy")
                ))
                .list();
    }

    @Transactional
    public DeadlineView create(CreateDeadlineRequest request) {
        RequestActor actor = actorProvider.current();
        String priority = request.priority() == null ? "NORMAL" : request.priority().toUpperCase(Locale.ROOT);
        UUID id = jdbcClient.sql("""
                        INSERT INTO deadlines
                            (matter_id, title, due_at, deadline_type, priority,
                             owner_user_id, reminder_policy, created_by)
                        SELECT m.id, :title, :dueAt, :deadlineType, :priority,
                               u.id, CAST(:reminderPolicy AS jsonb), :createdBy
                        FROM matters m
                        JOIN users u ON u.id = :ownerUserId
                        WHERE m.id = :matterId AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND u.organization_id = m.organization_id
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                        RETURNING id
                        """)
                .param("matterId", request.matterId())
                .param("organizationId", actor.organizationId())
                .param("title", request.title().trim())
                .param("dueAt", java.sql.Timestamp.from(request.dueAt()))
                .param("deadlineType", request.deadlineType().trim())
                .param("priority", priority)
                .param("ownerUserId", request.ownerUserId())
                .param("reminderPolicy", reminderPolicy(request.reminderDaysBefore()))
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DEADLINE_CONTEXT_INVALID",
                        "案件或期限负责人无效",
                        HttpStatus.BAD_REQUEST
                ));
        auditService.success(actor, "DEADLINE_CREATE", "DEADLINE", id);
        return list(request.matterId()).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public DeadlineView update(UUID id, CreateDeadlineRequest request) {
        RequestActor actor = actorProvider.current();
        String priority = request.priority() == null
                ? "NORMAL" : request.priority().toUpperCase(Locale.ROOT);
        int updated = jdbcClient.sql("""
                        UPDATE deadlines d
                        SET matter_id = :matterId,
                            title = :title,
                            due_at = :dueAt,
                            deadline_type = :deadlineType,
                            priority = :priority,
                            owner_user_id = :ownerUserId,
                            reminder_policy = CAST(:reminderPolicy AS jsonb),
                            updated_at = now()
                        WHERE d.id = :id
                          AND EXISTS (
                              SELECT 1 FROM matters current_matter
                              WHERE current_matter.id = d.matter_id
                                AND current_matter.organization_id = :organizationId
                                AND current_matter.deleted_at IS NULL
                                AND EXISTS (
                                    SELECT 1 FROM matter_members current_member
                                    WHERE current_member.matter_id = current_matter.id
                                      AND current_member.user_id = :actorId
                                      AND current_member.left_at IS NULL
                                )
                          )
                          AND EXISTS (
                              SELECT 1 FROM matters target_matter
                              WHERE target_matter.id = :matterId
                                AND target_matter.organization_id = :organizationId
                                AND target_matter.deleted_at IS NULL
                                AND EXISTS (
                                    SELECT 1 FROM matter_members target_member
                                    WHERE target_member.matter_id = target_matter.id
                                      AND target_member.user_id = :actorId
                                      AND target_member.left_at IS NULL
                                )
                          )
                          AND EXISTS (
                              SELECT 1 FROM users u
                              WHERE u.id = :ownerUserId
                                AND u.organization_id = :organizationId
                                AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          )
                        """)
                .param("id", id)
                .param("matterId", request.matterId())
                .param("organizationId", actor.organizationId())
                .param("actorId", actor.userId())
                .param("title", request.title().trim())
                .param("dueAt", java.sql.Timestamp.from(request.dueAt()))
                .param("deadlineType", request.deadlineType().trim())
                .param("priority", priority)
                .param("ownerUserId", request.ownerUserId())
                .param("reminderPolicy", reminderPolicy(request.reminderDaysBefore()))
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "DEADLINE_CONTEXT_INVALID", "期限、案件或负责人无效，或当前用户无权编辑",
                    HttpStatus.BAD_REQUEST
            );
        }
        auditService.success(actor, "DEADLINE_UPDATE", "DEADLINE", id);
        return list(request.matterId()).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static String reminderPolicy(List<Integer> days) {
        List<Integer> values = days == null || days.isEmpty()
                ? List.of(7, 3, 1)
                : days.stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        return "{\"daysBefore\":[" + values.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }
}
