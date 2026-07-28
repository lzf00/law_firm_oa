package com.zoro.legaloa.office;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.office.WorkTaskController.AddTaskCommentRequest;
import com.zoro.legaloa.office.WorkTaskController.ChangeTaskStatusRequest;
import com.zoro.legaloa.office.WorkTaskController.CreateWorkTaskRequest;
import com.zoro.legaloa.office.WorkTaskController.TaskCommentView;
import com.zoro.legaloa.office.WorkTaskController.WorkTaskView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkTaskService {
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STATUSES = Set.of("TODO", "IN_PROGRESS", "DONE", "CANCELLED");
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final OfficeAccessService officeAccessService;

    public WorkTaskService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            AuditService auditService,
            OutboxService outboxService,
            OfficeAccessService officeAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.officeAccessService = officeAccessService;
    }

    @Transactional(readOnly = true)
    public List<WorkTaskView> list() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT t.id, t.title, t.description, t.status, t.priority,
                               t.owner_user_id, owner.display_name AS owner_name,
                               assigner.display_name AS assigner_name,
                               t.due_at, t.completed_at, t.created_at,
                               t.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en,
                               (SELECT COUNT(*) FROM work_task_comments c
                                WHERE c.task_id = t.id) AS comment_count
                        FROM work_tasks t
                        JOIN users owner ON owner.id = t.owner_user_id
                        JOIN users assigner ON assigner.id = t.assigner_user_id
                        LEFT JOIN offices o ON o.id = t.office_id
                        WHERE t.organization_id = :organizationId
                          AND (
                            t.owner_user_id = :userId
                            OR t.assigner_user_id = :userId
                            OR EXISTS (
                              SELECT 1 FROM work_task_participants p
                              WHERE p.task_id = t.id AND p.user_id = :userId
                            )
                            OR (
                              :viewAll
                              AND (:globalAccess OR t.office_id IN (:officeIds))
                            )
                          )
                        ORDER BY
                          CASE t.status WHEN 'IN_PROGRESS' THEN 0 WHEN 'TODO' THEN 1
                               WHEN 'DONE' THEN 2 ELSE 3 END,
                          t.due_at NULLS LAST, t.created_at DESC
                        LIMIT 300
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", authorizationService.hasPermission(actor, "TASK_VIEW_ALL"))
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new WorkTaskView(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getString("priority"),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("owner_name"),
                        rs.getString("assigner_name"),
                        instant(rs, "due_at"),
                        instant(rs, "completed_at"),
                        instant(rs, "created_at"),
                        rs.getLong("comment_count"),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en")
                ))
                .list();
    }

    @Transactional
    public WorkTaskView create(CreateWorkTaskRequest request) {
        RequestActor actor = actorProvider.current();
        String priority = normalize(request.priority(), "NORMAL", PRIORITIES, "任务优先级无效");
        TaskOfficeContext office = resolveOfficeContext(actor, request);
        ensureAssignableUser(actor, request.ownerUserId(), office);
        UUID id = jdbcClient.sql("""
                        INSERT INTO work_tasks
                            (organization_id, title, description, priority,
                             assigner_user_id, owner_user_id, due_at,
                             related_business_type, related_business_id, office_id)
                        VALUES
                            (:organizationId, :title, :description, :priority,
                             :assignerId, :ownerId, :dueAt,
                             :relatedType, :relatedId, :officeId)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("title", request.title().trim())
                .param("description", trim(request.description()))
                .param("priority", priority)
                .param("assignerId", actor.userId())
                .param("ownerId", request.ownerUserId())
                .param("dueAt", request.dueAt() == null
                        ? null : java.sql.Timestamp.from(request.dueAt()))
                .param("relatedType", trim(request.relatedBusinessType()))
                .param("relatedId", request.relatedBusinessId())
                .param("officeId", office.officeId())
                .query(UUID.class)
                .single();
        List<UUID> participants = request.participantUserIds() == null
                ? List.of() : request.participantUserIds();
        for (UUID participant : participants.stream().distinct().toList()) {
            ensureAssignableUser(actor, participant, office);
            jdbcClient.sql("""
                            INSERT INTO work_task_participants (task_id, user_id, participant_role)
                            VALUES (:taskId, :userId, 'COLLABORATOR')
                            ON CONFLICT DO NOTHING
                            """)
                    .param("taskId", id).param("userId", participant).update();
        }
        outboxService.enqueueNotification(
                actor.organizationId(), request.ownerUserId(), "WORK_TASK_ASSIGNED",
                "收到新的协作任务", request.title().trim(),
                "WORK_TASK", id, "/tasks?id=" + id,
                priority, "work-task-assigned:" + id
        );
        auditService.success(actor, "WORK_TASK_CREATE", "WORK_TASK", id);
        return findVisible(id);
    }

    @Transactional
    public WorkTaskView changeStatus(UUID id, ChangeTaskStatusRequest request) {
        RequestActor actor = actorProvider.current();
        String status = normalize(request.status(), null, STATUSES, "任务状态无效");
        TaskAccess access = access(actor, id);
        if (!access.ownerId().equals(actor.userId())
                && !access.assignerId().equals(actor.userId())
                && !authorizationService.hasPermission(actor, "TASK_VIEW_ALL")) {
            throw new BusinessException(
                    "WORK_TASK_UPDATE_DENIED", "只有负责人或创建人可变更任务状态",
                    HttpStatus.FORBIDDEN
            );
        }
        if ("CANCELLED".equals(status)
                && !access.assignerId().equals(actor.userId())
                && !authorizationService.hasPermission(actor, "TASK_VIEW_ALL")) {
            throw new BusinessException(
                    "WORK_TASK_CANCEL_DENIED", "只有创建人可取消任务", HttpStatus.FORBIDDEN
            );
        }
        jdbcClient.sql("""
                        UPDATE work_tasks
                        SET status = :status,
                            completed_at = CASE WHEN :status = 'DONE' THEN now() ELSE NULL END,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("status", status)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        if (!actor.userId().equals(access.assignerId())) {
            outboxService.enqueueNotification(
                    actor.organizationId(), access.assignerId(), "WORK_TASK_STATUS_CHANGED",
                    "协作任务状态更新", access.title() + " → " + status,
                    "WORK_TASK", id, "/tasks?id=" + id,
                    "NORMAL", "work-task-status:" + id + ":" + status
            );
        }
        auditService.success(actor, "WORK_TASK_STATUS_" + status, "WORK_TASK", id);
        return findVisible(id);
    }

    @Transactional
    public TaskCommentView addComment(UUID id, AddTaskCommentRequest request) {
        RequestActor actor = actorProvider.current();
        access(actor, id);
        UUID commentId = jdbcClient.sql("""
                        INSERT INTO work_task_comments (task_id, author_user_id, content)
                        VALUES (:taskId, :authorId, :content)
                        RETURNING id
                        """)
                .param("taskId", id)
                .param("authorId", actor.userId())
                .param("content", request.content().trim())
                .query(UUID.class).single();
        auditService.success(actor, "WORK_TASK_COMMENT", "WORK_TASK", id);
        return new TaskCommentView(commentId, actor.displayName(), request.content().trim(), Instant.now());
    }

    private WorkTaskView findVisible(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "WORK_TASK_NOT_FOUND", "协作任务不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private TaskAccess access(RequestActor actor, UUID id) {
        boolean viewAll = authorizationService.hasPermission(actor, "TASK_VIEW_ALL");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT t.owner_user_id, t.assigner_user_id, t.title, t.office_id
                        FROM work_tasks t
                        WHERE t.id = :id AND t.organization_id = :organizationId
                          AND (
                            t.owner_user_id = :userId OR t.assigner_user_id = :userId
                            OR EXISTS (
                              SELECT 1 FROM work_task_participants p
                              WHERE p.task_id = t.id AND p.user_id = :userId
                            )
                            OR (
                              :viewAll
                              AND (:globalAccess OR t.office_id IN (:officeIds))
                            )
                          )
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new TaskAccess(
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("assigner_user_id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("office_id", UUID.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "WORK_TASK_NOT_FOUND", "协作任务不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private TaskOfficeContext resolveOfficeContext(
            RequestActor actor,
            CreateWorkTaskRequest request
    ) {
        if ("MATTER".equalsIgnoreCase(trim(request.relatedBusinessType()))
                && request.relatedBusinessId() != null) {
            OfficeAccessScope scope = officeAccessService.scope(actor);
            return jdbcClient.sql("""
                            SELECT m.office_id, m.id AS matter_id
                            FROM matters m
                            WHERE m.id = :matterId
                              AND m.organization_id = :organizationId
                              AND m.deleted_at IS NULL
                              AND (
                                :globalAccess
                                OR EXISTS (
                                  SELECT 1 FROM matter_members mm
                                  WHERE mm.matter_id = m.id
                                    AND mm.user_id = :userId
                                    AND mm.left_at IS NULL
                                )
                              )
                            """)
                    .param("matterId", request.relatedBusinessId())
                    .param("organizationId", actor.organizationId())
                    .param("globalAccess", scope.globalAccess())
                    .param("userId", actor.userId())
                    .query((rs, rowNum) -> new TaskOfficeContext(
                            rs.getObject("office_id", UUID.class),
                            rs.getObject("matter_id", UUID.class)
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            "WORK_TASK_BUSINESS_INVALID",
                            "关联案件不存在或无权访问",
                            HttpStatus.BAD_REQUEST
                    ));
        }
        OfficeAccessScope scope = officeAccessService.scope(actor);
        if (scope.globalAccess()) {
            UUID ownerOfficeId = jdbcClient.sql("""
                            SELECT u.primary_office_id
                            FROM users u
                            JOIN offices o ON o.id = u.primary_office_id
                            WHERE u.id = :userId
                              AND u.organization_id = :organizationId
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                              AND o.status = 'ACTIVE'
                            """)
                    .param("userId", request.ownerUserId())
                    .param("organizationId", actor.organizationId())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            "WORK_TASK_OWNER_INVALID",
                            "任务负责人无效或尚未分配办公室",
                            HttpStatus.BAD_REQUEST
                    ));
            return new TaskOfficeContext(ownerOfficeId, null);
        }
        return new TaskOfficeContext(
                officeAccessService.resolveAccessibleOffice(actor, null),
                null
        );
    }

    private void ensureAssignableUser(
            RequestActor actor,
            UUID userId,
            TaskOfficeContext office
    ) {
        Boolean exists;
        if (office.matterId() != null) {
            exists = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM users u
                                JOIN matter_members mm ON mm.user_id = u.id
                                WHERE u.id = :userId
                                  AND u.organization_id = :organizationId
                                  AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                                  AND mm.matter_id = :matterId
                                  AND mm.left_at IS NULL
                            )
                            """)
                    .param("userId", userId)
                    .param("organizationId", actor.organizationId())
                    .param("matterId", office.matterId())
                    .query(Boolean.class)
                    .single();
        } else if (officeAccessService.scope(actor).globalAccess()) {
            exists = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM users
                                WHERE id = :userId AND organization_id = :organizationId
                                  AND status = 'ACTIVE' AND deleted_at IS NULL
                            )
                            """)
                    .param("userId", userId)
                    .param("organizationId", actor.organizationId())
                    .query(Boolean.class)
                    .single();
        } else {
            officeAccessService.requireUserInOffice(actor, userId, office.officeId());
            exists = true;
        }
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(
                    "WORK_TASK_OWNER_INVALID", "任务负责人不属于关联案件或办公室",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static String normalize(
            String value, String fallback, Set<String> accepted, String message
    ) {
        String normalized = value == null ? fallback : value.toUpperCase(Locale.ROOT);
        if (normalized == null || !accepted.contains(normalized)) {
            throw new BusinessException("ENUM_INVALID", message, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record TaskAccess(UUID ownerId, UUID assignerId, String title, UUID officeId) {}

    private record TaskOfficeContext(UUID officeId, UUID matterId) {}
}
