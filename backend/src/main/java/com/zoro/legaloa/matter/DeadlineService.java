package com.zoro.legaloa.matter;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.matter.DeadlineController.CreateDeadlineRequest;
import com.zoro.legaloa.matter.DeadlineController.DeadlineActionRequest;
import com.zoro.legaloa.matter.DeadlineController.DeadlineDetailView;
import com.zoro.legaloa.matter.DeadlineController.DeadlineEventView;
import com.zoro.legaloa.matter.DeadlineController.DeadlineReminderView;
import com.zoro.legaloa.matter.DeadlineController.DeadlineView;
import com.zoro.legaloa.matter.DeadlineController.UpdateDeadlineRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeadlineService {
    private static final String DEADLINE_SELECT = """
            SELECT d.id, d.matter_id, m.matter_number, m.title AS matter_title,
                   d.title, d.due_at, d.deadline_type, d.priority, d.status,
                   d.owner_user_id, owner.display_name AS owner_name,
                   d.reminder_policy::text AS reminder_policy,
                   d.source_type, d.source_reference, d.calculation_note,
                   creator.display_name AS created_by_name,
                   d.completed_at, completed.display_name AS completed_by_name,
                   d.completion_note, d.cancelled_at,
                   cancelled.display_name AS cancelled_by_name,
                   d.cancellation_reason, d.version,
                   (SELECT COUNT(*) FROM deadline_lifecycle_events e
                    WHERE e.deadline_id = d.id) AS event_count,
                   (SELECT COUNT(*) FROM deadline_reminder_dispatches r
                    WHERE r.deadline_id = d.id) AS reminder_count,
                   m.office_id, d.created_by
            FROM deadlines d
            JOIN matters m ON m.id = d.matter_id
            JOIN users owner ON owner.id = d.owner_user_id
            JOIN users creator ON creator.id = d.created_by
            LEFT JOIN users completed ON completed.id = d.completed_by
            LEFT JOIN users cancelled ON cancelled.id = d.cancelled_by
            """;

    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;
    private final AuditService auditService;

    public DeadlineService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            OfficeAccessService officeAccessService,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.officeAccessService = officeAccessService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DeadlineView> list(UUID matterId) {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql(DEADLINE_SELECT + """
                        WHERE m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND (:allMatters OR d.matter_id = :matterId)
                          AND (
                            d.owner_user_id = :userId
                            OR d.created_by = :userId
                            OR EXISTS (
                              SELECT 1 FROM matter_members visible_mm
                              WHERE visible_mm.matter_id = m.id
                                AND visible_mm.user_id = :userId
                                AND visible_mm.left_at IS NULL
                            )
                            OR (
                              :viewAll
                              AND (:globalAccess OR m.office_id IN (:officeIds))
                            )
                          )
                        ORDER BY
                          CASE d.status
                            WHEN 'OVERDUE' THEN 0 WHEN 'OPEN' THEN 1
                            WHEN 'COMPLETED' THEN 2 ELSE 3
                          END,
                          d.due_at
                        LIMIT 300
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("allMatters", matterId == null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .param("viewAll", canViewAll(actor))
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(DeadlineService::mapDeadline)
                .list();
    }

    @Transactional(readOnly = true)
    public DeadlineDetailView detail(UUID id) {
        RequestActor actor = actorProvider.current();
        DeadlineView deadline = visibleDeadline(actor, id);
        List<DeadlineEventView> events = jdbcClient.sql("""
                        SELECT id, action, actor_user_id, actor_display_name,
                               from_status, to_status, previous_due_at, next_due_at,
                               previous_owner_user_id, next_owner_user_id,
                               note, occurred_at
                        FROM deadline_lifecycle_events
                        WHERE deadline_id = :deadlineId
                          AND organization_id = :organizationId
                        ORDER BY occurred_at, id
                        """)
                .param("deadlineId", id)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new DeadlineEventView(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_display_name"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        instant(rs, "previous_due_at"),
                        instant(rs, "next_due_at"),
                        rs.getObject("previous_owner_user_id", UUID.class),
                        rs.getObject("next_owner_user_id", UUID.class),
                        rs.getString("note"),
                        instant(rs, "occurred_at")
                ))
                .list();
        List<DeadlineReminderView> reminders = jdbcClient.sql("""
                        SELECT r.id, r.recipient_user_id, u.display_name AS recipient_name,
                               r.reminder_date, r.days_before, r.created_at
                        FROM deadline_reminder_dispatches r
                        JOIN users u ON u.id = r.recipient_user_id
                        WHERE r.deadline_id = :deadlineId
                        ORDER BY r.reminder_date DESC, r.created_at DESC
                        LIMIT 100
                        """)
                .param("deadlineId", id)
                .query((rs, rowNum) -> new DeadlineReminderView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("recipient_user_id", UUID.class),
                        rs.getString("recipient_name"),
                        rs.getDate("reminder_date").toLocalDate(),
                        rs.getInt("days_before"),
                        instant(rs, "created_at")
                ))
                .list();
        return new DeadlineDetailView(deadline, events, reminders);
    }

    @Transactional
    public DeadlineDetailView create(CreateDeadlineRequest request) {
        RequestActor actor = actorProvider.current();
        MatterDeadlineContext matter = requireMatterAccess(
                actor, request.matterId(), request.ownerUserId()
        );
        if (!matter.actorMember()
                && !(authorizationService.hasPermission(actor, "DEADLINE_MANAGE")
                && officeAccessService.scope(actor).canAccess(matter.officeId()))) {
            throw new BusinessException(
                    "DEADLINE_CREATE_DENIED",
                    "只有案件成员或期限管理员可以登记该案件期限",
                    HttpStatus.FORBIDDEN
            );
        }
        if (!matter.ownerMember()) {
            throw new BusinessException(
                    "DEADLINE_OWNER_INVALID",
                    "期限负责人必须是当前案件成员",
                    HttpStatus.BAD_REQUEST
            );
        }
        String priority = DeadlineLifecyclePolicy.priority(request.priority());
        String type = DeadlineLifecyclePolicy.type(request.deadlineType());
        String source = DeadlineLifecyclePolicy.source(request.sourceType());
        UUID id = jdbcClient.sql("""
                        INSERT INTO deadlines
                            (matter_id, title, due_at, deadline_type, priority,
                             owner_user_id, reminder_policy, created_by,
                             source_type, source_reference, calculation_note)
                        VALUES
                            (:matterId, :title, :dueAt, :deadlineType, :priority,
                             :ownerUserId, CAST(:reminderPolicy AS jsonb), :createdBy,
                             :sourceType, :sourceReference, :calculationNote)
                        RETURNING id
                        """)
                .param("matterId", request.matterId())
                .param("title", request.title().trim())
                .param("dueAt", java.sql.Timestamp.from(request.dueAt()))
                .param("deadlineType", type)
                .param("priority", priority)
                .param("ownerUserId", request.ownerUserId())
                .param("reminderPolicy", reminderPolicy(request.reminderDaysBefore()))
                .param("createdBy", actor.userId())
                .param("sourceType", source)
                .param("sourceReference", trim(request.sourceReference()))
                .param("calculationNote", trim(request.calculationNote()))
                .query(UUID.class)
                .single();
        recordEvent(
                actor, id, "CREATED", null, "OPEN",
                null, request.dueAt(), null, request.ownerUserId(),
                "Deadline registered"
        );
        auditService.success(actor, "DEADLINE_CREATE", "DEADLINE", id);
        return detail(id);
    }

    @Transactional
    public DeadlineDetailView update(UUID id, UpdateDeadlineRequest request) {
        RequestActor actor = actorProvider.current();
        DeadlineAccess access = requireMutableAccess(actor, id);
        if (!DeadlineLifecyclePolicy.canEdit(access.status())) {
            throw conflict("DEADLINE_EDIT_LOCKED", "已完成或已取消的期限不能直接编辑");
        }
        if (!access.matterId().equals(request.matterId())) {
            throw conflict("DEADLINE_MATTER_LOCKED", "期限创建后不能转移到其他案件");
        }
        MatterDeadlineContext matter = requireMatterAccess(
                actor, request.matterId(), request.ownerUserId()
        );
        if (!matter.ownerMember()) {
            throw new BusinessException(
                    "DEADLINE_OWNER_INVALID",
                    "期限负责人必须是当前案件成员",
                    HttpStatus.BAD_REQUEST
            );
        }
        String priority = DeadlineLifecyclePolicy.priority(request.priority());
        String type = DeadlineLifecyclePolicy.type(request.deadlineType());
        String source = DeadlineLifecyclePolicy.source(request.sourceType());
        int updated = jdbcClient.sql("""
                        UPDATE deadlines
                        SET title = :title,
                            due_at = :dueAt,
                            deadline_type = :deadlineType,
                            priority = :priority,
                            owner_user_id = :ownerUserId,
                            reminder_policy = CAST(:reminderPolicy AS jsonb),
                            source_type = :sourceType,
                            source_reference = :sourceReference,
                            calculation_note = :calculationNote,
                            status = CASE
                              WHEN status = 'OVERDUE' AND :dueAt > now() THEN 'OPEN'
                              ELSE status
                            END,
                            updated_at = now(),
                            version = version + 1
                        WHERE id = :id
                          AND version = :expectedVersion
                          AND status IN ('OPEN', 'OVERDUE')
                        """)
                .param("id", id)
                .param("expectedVersion", request.expectedVersion())
                .param("title", request.title().trim())
                .param("dueAt", java.sql.Timestamp.from(request.dueAt()))
                .param("deadlineType", type)
                .param("priority", priority)
                .param("ownerUserId", request.ownerUserId())
                .param("reminderPolicy", reminderPolicy(request.reminderDaysBefore()))
                .param("sourceType", source)
                .param("sourceReference", trim(request.sourceReference()))
                .param("calculationNote", trim(request.calculationNote()))
                .update();
        requireUpdated(updated);
        String nextStatus = "OVERDUE".equals(access.status())
                && request.dueAt().isAfter(Instant.now()) ? "OPEN" : access.status();
        recordEvent(
                actor, id, "UPDATED", access.status(), nextStatus,
                access.dueAt(), request.dueAt(),
                access.ownerUserId(), request.ownerUserId(),
                "Deadline details updated"
        );
        auditService.success(actor, "DEADLINE_UPDATE", "DEADLINE", id);
        return detail(id);
    }

    @Transactional
    public DeadlineDetailView complete(UUID id, DeadlineActionRequest request) {
        RequestActor actor = actorProvider.current();
        DeadlineAccess access = requireMutableAccess(actor, id);
        if (!DeadlineLifecyclePolicy.canComplete(access.status())) {
            throw conflict("DEADLINE_COMPLETE_INVALID", "当前期限状态不能完成");
        }
        int updated = jdbcClient.sql("""
                        UPDATE deadlines
                        SET status = 'COMPLETED', completed_at = now(),
                            completed_by = :actorId, completion_note = :note,
                            cancelled_at = NULL, cancelled_by = NULL,
                            cancellation_reason = NULL,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND version = :expectedVersion
                          AND status IN ('OPEN', 'OVERDUE')
                        """)
                .param("actorId", actor.userId())
                .param("note", request.note().trim())
                .param("id", id)
                .param("expectedVersion", request.expectedVersion())
                .update();
        requireUpdated(updated);
        recordEvent(
                actor, id, "COMPLETED", access.status(), "COMPLETED",
                access.dueAt(), access.dueAt(), access.ownerUserId(), access.ownerUserId(),
                request.note().trim()
        );
        auditService.success(actor, "DEADLINE_COMPLETE", "DEADLINE", id);
        return detail(id);
    }

    @Transactional
    public DeadlineDetailView cancel(UUID id, DeadlineActionRequest request) {
        RequestActor actor = actorProvider.current();
        DeadlineAccess access = requireMutableAccess(actor, id);
        if (!DeadlineLifecyclePolicy.canCancel(access.status())) {
            throw conflict("DEADLINE_CANCEL_INVALID", "当前期限状态不能取消");
        }
        int updated = jdbcClient.sql("""
                        UPDATE deadlines
                        SET status = 'CANCELLED', cancelled_at = now(),
                            cancelled_by = :actorId, cancellation_reason = :note,
                            completed_at = NULL, completed_by = NULL,
                            completion_note = NULL,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND version = :expectedVersion
                          AND status IN ('OPEN', 'OVERDUE')
                        """)
                .param("actorId", actor.userId())
                .param("note", request.note().trim())
                .param("id", id)
                .param("expectedVersion", request.expectedVersion())
                .update();
        requireUpdated(updated);
        recordEvent(
                actor, id, "CANCELLED", access.status(), "CANCELLED",
                access.dueAt(), access.dueAt(), access.ownerUserId(), access.ownerUserId(),
                request.note().trim()
        );
        auditService.success(actor, "DEADLINE_CANCEL", "DEADLINE", id);
        return detail(id);
    }

    @Transactional
    public DeadlineDetailView reopen(UUID id, DeadlineActionRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "DEADLINE_MANAGE");
        DeadlineAccess access = requireMutableAccess(actor, id);
        if (!DeadlineLifecyclePolicy.canReopen(access.status())) {
            throw conflict("DEADLINE_REOPEN_INVALID", "只有已完成或已取消的期限可以复开");
        }
        String nextStatus = access.dueAt().isBefore(Instant.now()) ? "OVERDUE" : "OPEN";
        int updated = jdbcClient.sql("""
                        UPDATE deadlines
                        SET status = :nextStatus,
                            completed_at = NULL, completed_by = NULL,
                            completion_note = NULL,
                            cancelled_at = NULL, cancelled_by = NULL,
                            cancellation_reason = NULL,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND version = :expectedVersion
                          AND status IN ('COMPLETED', 'CANCELLED')
                        """)
                .param("nextStatus", nextStatus)
                .param("id", id)
                .param("expectedVersion", request.expectedVersion())
                .update();
        requireUpdated(updated);
        recordEvent(
                actor, id, "REOPENED", access.status(), nextStatus,
                access.dueAt(), access.dueAt(), access.ownerUserId(), access.ownerUserId(),
                request.note().trim()
        );
        auditService.success(actor, "DEADLINE_REOPEN", "DEADLINE", id);
        return detail(id);
    }

    private DeadlineView visibleDeadline(RequestActor actor, UUID id) {
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql(DEADLINE_SELECT + """
                        WHERE d.id = :id
                          AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND (
                            d.owner_user_id = :userId
                            OR d.created_by = :userId
                            OR EXISTS (
                              SELECT 1 FROM matter_members mm
                              WHERE mm.matter_id = m.id
                                AND mm.user_id = :userId
                                AND mm.left_at IS NULL
                            )
                            OR (
                              :viewAll
                              AND (:globalAccess OR m.office_id IN (:officeIds))
                            )
                          )
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", canViewAll(actor))
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(DeadlineService::mapDeadline)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DEADLINE_NOT_FOUND",
                        "期限不存在或不可访问",
                        HttpStatus.NOT_FOUND
                ));
    }

    private DeadlineAccess requireMutableAccess(RequestActor actor, UUID id) {
        OfficeAccessScope scope = officeAccessService.scope(actor);
        DeadlineAccess access = jdbcClient.sql("""
                        SELECT d.id, d.matter_id, d.status, d.due_at,
                               d.owner_user_id, d.created_by, d.version, m.office_id,
                               EXISTS (
                                 SELECT 1 FROM matter_members mm
                                 WHERE mm.matter_id = m.id
                                   AND mm.user_id = :userId
                                   AND mm.left_at IS NULL
                               ) AS actor_member
                        FROM deadlines d
                        JOIN matters m ON m.id = d.matter_id
                        WHERE d.id = :id
                          AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .query((rs, rowNum) -> new DeadlineAccess(
                        rs.getObject("id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("status"),
                        instant(rs, "due_at"),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getInt("version"),
                        rs.getObject("office_id", UUID.class),
                        rs.getBoolean("actor_member")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DEADLINE_NOT_FOUND",
                        "期限不存在或不可访问",
                        HttpStatus.NOT_FOUND
                ));
        boolean direct = access.actorMember()
                || actor.userId().equals(access.ownerUserId())
                || actor.userId().equals(access.createdBy());
        boolean managed = authorizationService.hasPermission(actor, "DEADLINE_MANAGE")
                && scope.canAccess(access.officeId());
        if (!direct && !managed) {
            throw new BusinessException(
                    "DEADLINE_UPDATE_DENIED",
                    "只有案件成员、期限负责人或期限管理员可以操作",
                    HttpStatus.FORBIDDEN
            );
        }
        return access;
    }

    private MatterDeadlineContext requireMatterAccess(
            RequestActor actor,
            UUID matterId,
            UUID ownerUserId
    ) {
        return jdbcClient.sql("""
                        SELECT m.office_id,
                               EXISTS (
                                 SELECT 1 FROM matter_members mm
                                 WHERE mm.matter_id = m.id
                                   AND mm.user_id = :actorId
                                   AND mm.left_at IS NULL
                               ) AS actor_member,
                               EXISTS (
                                 SELECT 1 FROM matter_members mm
                                 JOIN users u ON u.id = mm.user_id
                                 WHERE mm.matter_id = m.id
                                   AND mm.user_id = :ownerUserId
                                   AND mm.left_at IS NULL
                                   AND u.organization_id = m.organization_id
                                   AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                               ) AS owner_member
                        FROM matters m
                        WHERE m.id = :matterId
                          AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                        """)
                .param("matterId", matterId)
                .param("organizationId", actor.organizationId())
                .param("actorId", actor.userId())
                .param("ownerUserId", ownerUserId)
                .query((rs, rowNum) -> new MatterDeadlineContext(
                        rs.getObject("office_id", UUID.class),
                        rs.getBoolean("actor_member"),
                        rs.getBoolean("owner_member")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DEADLINE_CONTEXT_INVALID",
                        "案件不存在或不可访问",
                        HttpStatus.BAD_REQUEST
                ));
    }

    private void recordEvent(
            RequestActor actor,
            UUID deadlineId,
            String action,
            String fromStatus,
            String toStatus,
            Instant previousDueAt,
            Instant nextDueAt,
            UUID previousOwner,
            UUID nextOwner,
            String note
    ) {
        jdbcClient.sql("""
                        INSERT INTO deadline_lifecycle_events
                            (organization_id, deadline_id, action,
                             actor_user_id, actor_display_name,
                             from_status, to_status,
                             previous_due_at, next_due_at,
                             previous_owner_user_id, next_owner_user_id, note)
                        VALUES
                            (:organizationId, :deadlineId, :action,
                             :actorId, :actorName,
                             :fromStatus, :toStatus,
                             :previousDueAt, :nextDueAt,
                             :previousOwner, :nextOwner, :note)
                        """)
                .param("organizationId", actor.organizationId())
                .param("deadlineId", deadlineId)
                .param("action", action)
                .param("actorId", actor.userId())
                .param("actorName", actor.displayName())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("previousDueAt", timestamp(previousDueAt))
                .param("nextDueAt", timestamp(nextDueAt))
                .param("previousOwner", previousOwner)
                .param("nextOwner", nextOwner)
                .param("note", note)
                .update();
    }

    private static DeadlineView mapDeadline(ResultSet rs, int rowNum) throws SQLException {
        return new DeadlineView(
                rs.getObject("id", UUID.class),
                rs.getObject("matter_id", UUID.class),
                rs.getString("matter_number"),
                rs.getString("matter_title"),
                rs.getString("title"),
                instant(rs, "due_at"),
                rs.getString("deadline_type"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("owner_name"),
                rs.getString("reminder_policy"),
                rs.getString("source_type"),
                rs.getString("source_reference"),
                rs.getString("calculation_note"),
                rs.getString("created_by_name"),
                instant(rs, "completed_at"),
                rs.getString("completed_by_name"),
                rs.getString("completion_note"),
                instant(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancellation_reason"),
                rs.getInt("version"),
                rs.getLong("event_count"),
                rs.getLong("reminder_count")
        );
    }

    private static String reminderPolicy(List<Integer> days) {
        List<Integer> values = days == null || days.isEmpty()
                ? List.of(7, 3, 1)
                : days.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        return "{\"daysBefore\":[" + values.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]}";
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean canViewAll(RequestActor actor) {
        return authorizationService.hasPermission(actor, "DEADLINE_VIEW_ALL")
                || authorizationService.hasPermission(actor, "DEADLINE_MANAGE");
    }

    private static void requireUpdated(int updated) {
        if (updated == 0) {
            throw conflict(
                    "DEADLINE_VERSION_CONFLICT",
                    "期限已被其他人更新，请刷新后重试"
            );
        }
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }

    private record MatterDeadlineContext(
            UUID officeId,
            boolean actorMember,
            boolean ownerMember
    ) {}

    private record DeadlineAccess(
            UUID id,
            UUID matterId,
            String status,
            Instant dueAt,
            UUID ownerUserId,
            UUID createdBy,
            int version,
            UUID officeId,
            boolean actorMember
    ) {}
}
