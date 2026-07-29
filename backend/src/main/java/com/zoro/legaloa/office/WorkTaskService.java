package com.zoro.legaloa.office;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.office.WorkTaskController.AddTaskCommentRequest;
import com.zoro.legaloa.office.WorkTaskController.ChangeTaskStatusRequest;
import com.zoro.legaloa.office.WorkTaskController.CreateWorkTaskRequest;
import com.zoro.legaloa.office.WorkTaskController.TaskCommentView;
import com.zoro.legaloa.office.WorkTaskController.TaskEventView;
import com.zoro.legaloa.office.WorkTaskController.TaskParticipantView;
import com.zoro.legaloa.office.WorkTaskController.UpdateWorkTaskRequest;
import com.zoro.legaloa.office.WorkTaskController.WorkTaskDetailView;
import com.zoro.legaloa.office.WorkTaskController.WorkTaskView;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkTaskService {
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
        boolean manage = authorizationService.hasPermission(actor, "TASK_MANAGE");
        boolean viewAll = manage || authorizationService.hasPermission(actor, "TASK_VIEW_ALL");
        return jdbcClient.sql("""
                        SELECT t.id, t.title, t.description, t.status, t.priority,
                               t.owner_user_id, owner.display_name AS owner_name,
                               assigner.display_name AS assigner_name,
                               t.due_at, t.completed_at, t.created_at,
                               t.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en,
                               t.related_business_type, t.related_business_id,
                               CASE
                                 WHEN t.related_business_type = 'MATTER'
                                   THEN CONCAT(m.matter_number, ' · ', m.title)
                                 ELSE t.related_business_type
                               END AS related_business_label,
                               t.version, t.completed_by,
                               completed_by.display_name AS completed_by_name,
                               t.completion_note, t.cancelled_at, t.cancelled_by,
                               cancelled_by.display_name AS cancelled_by_name,
                               t.cancellation_reason,
                               (SELECT COUNT(*) FROM work_task_comments c
                                WHERE c.task_id = t.id) AS comment_count,
                               (SELECT COUNT(*) FROM work_task_participants p
                                WHERE p.task_id = t.id) AS participant_count,
                               (SELECT COUNT(*) FROM work_task_events e
                                WHERE e.task_id = t.id) AS event_count
                        FROM work_tasks t
                        JOIN users owner ON owner.id = t.owner_user_id
                        JOIN users assigner ON assigner.id = t.assigner_user_id
                        LEFT JOIN users completed_by ON completed_by.id = t.completed_by
                        LEFT JOIN users cancelled_by ON cancelled_by.id = t.cancelled_by
                        LEFT JOIN offices o ON o.id = t.office_id
                        LEFT JOIN matters m
                          ON t.related_business_type = 'MATTER'
                         AND m.id = t.related_business_id
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
                          CASE t.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1
                               WHEN 'NORMAL' THEN 2 ELSE 3 END,
                          t.due_at NULLS LAST, t.created_at DESC
                        LIMIT 500
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(this::mapTask)
                .list();
    }

    @Transactional(readOnly = true)
    public WorkTaskDetailView detail(UUID id) {
        WorkTaskView task = findVisible(id);
        return new WorkTaskDetailView(
                task,
                jdbcClient.sql("""
                                SELECT p.user_id, u.display_name, p.participant_role, p.created_at
                                FROM work_task_participants p
                                JOIN users u ON u.id = p.user_id
                                WHERE p.task_id = :taskId
                                ORDER BY u.display_name
                                """)
                        .param("taskId", id)
                        .query((rs, rowNum) -> new TaskParticipantView(
                                rs.getObject("user_id", UUID.class),
                                rs.getString("display_name"),
                                rs.getString("participant_role"),
                                instant(rs, "created_at")
                        ))
                        .list(),
                jdbcClient.sql("""
                                SELECT c.id, u.display_name, c.content, c.created_at
                                FROM work_task_comments c
                                JOIN users u ON u.id = c.author_user_id
                                WHERE c.task_id = :taskId
                                ORDER BY c.created_at
                                """)
                        .param("taskId", id)
                        .query((rs, rowNum) -> new TaskCommentView(
                                rs.getObject("id", UUID.class),
                                rs.getString("display_name"),
                                rs.getString("content"),
                                instant(rs, "created_at")
                        ))
                        .list(),
                jdbcClient.sql("""
                                SELECT e.id, e.action, e.actor_user_id,
                                       u.display_name AS actor_name,
                                       e.from_status, e.to_status, e.note, e.occurred_at
                                FROM work_task_events e
                                JOIN users u ON u.id = e.actor_user_id
                                WHERE e.task_id = :taskId
                                ORDER BY e.occurred_at DESC, e.id DESC
                                """)
                        .param("taskId", id)
                        .query((rs, rowNum) -> new TaskEventView(
                                rs.getObject("id", UUID.class),
                                rs.getString("action"),
                                rs.getObject("actor_user_id", UUID.class),
                                rs.getString("actor_name"),
                                rs.getString("from_status"),
                                rs.getString("to_status"),
                                rs.getString("note"),
                                instant(rs, "occurred_at")
                        ))
                        .list()
        );
    }

    @Transactional
    public WorkTaskView create(CreateWorkTaskRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "TASK_CREATE");
        String priority = WorkTaskLifecyclePolicy.priority(request.priority());
        TaskOfficeContext office = resolveOfficeContext(
                actor,
                request.ownerUserId(),
                request.relatedBusinessType(),
                request.relatedBusinessId()
        );
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
                .param("dueAt", timestamp(request.dueAt()))
                .param("relatedType", normalizeRelatedType(request.relatedBusinessType()))
                .param("relatedId", request.relatedBusinessId())
                .param("officeId", office.officeId())
                .query(UUID.class)
                .single();
        replaceParticipants(actor, id, request.participantUserIds(), office);
        recordEvent(actor, id, "CREATED", null, "TODO", "任务创建");
        notifyAssignment(
                actor, id, request.ownerUserId(), request.participantUserIds(),
                request.title().trim(), priority
        );
        auditService.success(actor, "WORK_TASK_CREATE", "WORK_TASK", id);
        return findVisible(id);
    }

    @Transactional
    public WorkTaskDetailView update(UUID id, UpdateWorkTaskRequest request) {
        RequestActor actor = actorProvider.current();
        TaskAccess access = access(actor, id);
        boolean manage = authorizationService.hasPermission(actor, "TASK_MANAGE");
        if (!access.assignerId().equals(actor.userId()) && !manage) {
            throw forbidden("WORK_TASK_UPDATE_DENIED", "只有创建人或任务管理员可编辑任务");
        }
        if ("DONE".equals(access.status()) || "CANCELLED".equals(access.status())) {
            throw new BusinessException(
                    "WORK_TASK_TERMINAL_EDIT_DENIED",
                    "已完成或已取消的任务需先重新开启",
                    HttpStatus.CONFLICT
            );
        }
        TaskOfficeContext office = resolveOfficeContext(
                actor,
                request.ownerUserId(),
                request.relatedBusinessType(),
                request.relatedBusinessId()
        );
        ensureAssignableUser(actor, request.ownerUserId(), office);
        int updated = jdbcClient.sql("""
                        UPDATE work_tasks
                        SET title = :title, description = :description,
                            priority = :priority, owner_user_id = :ownerId,
                            due_at = :dueAt, related_business_type = :relatedType,
                            related_business_id = :relatedId, office_id = :officeId,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND version = :expectedVersion
                        """)
                .param("title", request.title().trim())
                .param("description", trim(request.description()))
                .param("priority", WorkTaskLifecyclePolicy.priority(request.priority()))
                .param("ownerId", request.ownerUserId())
                .param("dueAt", timestamp(request.dueAt()))
                .param("relatedType", normalizeRelatedType(request.relatedBusinessType()))
                .param("relatedId", request.relatedBusinessId())
                .param("officeId", office.officeId())
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("expectedVersion", request.expectedVersion())
                .update();
        requireUpdated(updated);
        replaceParticipants(actor, id, request.participantUserIds(), office);
        recordEvent(actor, id, "UPDATED", access.status(), access.status(), "任务信息更新");
        if (!access.ownerId().equals(request.ownerUserId())) {
            notifyAssignment(
                    actor, id, request.ownerUserId(), request.participantUserIds(),
                    request.title().trim(), WorkTaskLifecyclePolicy.priority(request.priority())
            );
        }
        auditService.success(actor, "WORK_TASK_UPDATE", "WORK_TASK", id);
        return detail(id);
    }

    @Transactional
    public WorkTaskView changeStatus(UUID id, ChangeTaskStatusRequest request) {
        RequestActor actor = actorProvider.current();
        String status = WorkTaskLifecyclePolicy.status(request.status());
        TaskAccess access = access(actor, id);
        boolean manage = authorizationService.hasPermission(actor, "TASK_MANAGE");
        if (!access.ownerId().equals(actor.userId())
                && !access.assignerId().equals(actor.userId())
                && !manage) {
            throw forbidden(
                    "WORK_TASK_UPDATE_DENIED",
                    "只有负责人、创建人或任务管理员可变更任务状态"
            );
        }
        if ("CANCELLED".equals(status)
                && !access.assignerId().equals(actor.userId())
                && !manage) {
            throw forbidden(
                    "WORK_TASK_CANCEL_DENIED",
                    "只有创建人或任务管理员可取消任务"
            );
        }
        if (!WorkTaskLifecyclePolicy.canTransition(access.status(), status)) {
            throw new BusinessException(
                    "WORK_TASK_TRANSITION_INVALID",
                    "不允许从 " + access.status() + " 变更为 " + status,
                    HttpStatus.CONFLICT
            );
        }
        String note = trim(request.note());
        if (WorkTaskLifecyclePolicy.requiresNote(access.status(), status) && note == null) {
            throw new BusinessException(
                    "WORK_TASK_STATUS_NOTE_REQUIRED",
                    "取消或重新开启任务必须填写原因",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (access.status().equals(status)) {
            return findVisible(id);
        }
        int updated = jdbcClient.sql("""
                        UPDATE work_tasks
                        SET status = :status,
                            completed_at = CASE WHEN :status = 'DONE' THEN now() ELSE NULL END,
                            completed_by = CASE WHEN :status = 'DONE' THEN :actorId ELSE NULL END,
                            completion_note = CASE WHEN :status = 'DONE' THEN :note ELSE NULL END,
                            cancelled_at = CASE WHEN :status = 'CANCELLED' THEN now() ELSE NULL END,
                            cancelled_by = CASE WHEN :status = 'CANCELLED' THEN :actorId ELSE NULL END,
                            cancellation_reason =
                              CASE WHEN :status = 'CANCELLED' THEN :note ELSE NULL END,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND version = :expectedVersion
                        """)
                .param("status", status)
                .param("actorId", actor.userId())
                .param("note", note)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("expectedVersion", request.expectedVersion())
                .update();
        requireUpdated(updated);
        String action = WorkTaskLifecyclePolicy.action(access.status(), status);
        recordEvent(actor, id, action, access.status(), status, note);
        notifyStatus(actor, access, id, status);
        auditService.success(actor, "WORK_TASK_" + action, "WORK_TASK", id);
        return findVisible(id);
    }

    @Transactional
    public TaskCommentView addComment(UUID id, AddTaskCommentRequest request) {
        RequestActor actor = actorProvider.current();
        TaskAccess access = access(actor, id);
        UUID commentId = jdbcClient.sql("""
                        INSERT INTO work_task_comments (task_id, author_user_id, content)
                        VALUES (:taskId, :authorId, :content)
                        RETURNING id
                        """)
                .param("taskId", id)
                .param("authorId", actor.userId())
                .param("content", request.content().trim())
                .query(UUID.class)
                .single();
        recordEvent(actor, id, "COMMENTED", access.status(), access.status(), request.content().trim());
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(access.ownerId());
        recipients.add(access.assignerId());
        recipients.remove(actor.userId());
        for (UUID recipient : recipients) {
            outboxService.enqueueNotification(
                    actor.organizationId(), recipient, "WORK_TASK_COMMENTED",
                    "协作任务有新评论",
                    actor.displayName() + " 评论了“" + access.title() + "”",
                    "WORK_TASK", id, "/tasks?id=" + id,
                    "NORMAL", "work-task-comment:" + commentId + ":" + recipient
            );
        }
        auditService.success(actor, "WORK_TASK_COMMENT", "WORK_TASK", id);
        return new TaskCommentView(
                commentId, actor.displayName(), request.content().trim(), Instant.now()
        );
    }

    private void notifyAssignment(
            RequestActor actor,
            UUID taskId,
            UUID ownerId,
            List<UUID> participants,
            String title,
            String priority
    ) {
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(ownerId);
        if (participants != null) {
            recipients.addAll(participants);
        }
        recipients.remove(actor.userId());
        for (UUID recipient : recipients) {
            outboxService.enqueueNotification(
                    actor.organizationId(), recipient, "WORK_TASK_ASSIGNED",
                    recipient.equals(ownerId) ? "收到新的协作任务" : "加入新的协作任务",
                    title, "WORK_TASK", taskId, "/tasks?id=" + taskId,
                    priority, "work-task-assigned:" + taskId + ":" + recipient
            );
        }
    }

    private void notifyStatus(
            RequestActor actor,
            TaskAccess access,
            UUID id,
            String status
    ) {
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(access.assignerId());
        recipients.add(access.ownerId());
        recipients.remove(actor.userId());
        for (UUID recipient : recipients) {
            outboxService.enqueueNotification(
                    actor.organizationId(), recipient, "WORK_TASK_STATUS_CHANGED",
                    "协作任务状态更新", access.title() + " → " + status,
                    "WORK_TASK", id, "/tasks?id=" + id,
                    "NORMAL", "work-task-status:" + id + ":" + status + ":" + recipient
            );
        }
    }

    private WorkTaskView findVisible(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "WORK_TASK_NOT_FOUND", "协作任务不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private TaskAccess access(RequestActor actor, UUID id) {
        boolean viewAll = authorizationService.hasPermission(actor, "TASK_VIEW_ALL")
                || authorizationService.hasPermission(actor, "TASK_MANAGE");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT t.owner_user_id, t.assigner_user_id, t.title, t.office_id,
                               t.status, t.version
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
                        rs.getObject("office_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("version")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "WORK_TASK_NOT_FOUND", "协作任务不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private TaskOfficeContext resolveOfficeContext(
            RequestActor actor,
            UUID ownerUserId,
            String relatedBusinessType,
            UUID relatedBusinessId
    ) {
        if ("MATTER".equalsIgnoreCase(trim(relatedBusinessType))
                && relatedBusinessId != null) {
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
                    .param("matterId", relatedBusinessId)
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
        if (relatedBusinessId != null || trim(relatedBusinessType) != null) {
            throw new BusinessException(
                    "WORK_TASK_BUSINESS_INVALID",
                    "当前仅支持关联案件，业务类型和业务编号必须同时填写",
                    HttpStatus.BAD_REQUEST
            );
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
                    .param("userId", ownerUserId)
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

    private void replaceParticipants(
            RequestActor actor,
            UUID taskId,
            List<UUID> participantUserIds,
            TaskOfficeContext office
    ) {
        jdbcClient.sql("DELETE FROM work_task_participants WHERE task_id = :taskId")
                .param("taskId", taskId)
                .update();
        if (participantUserIds == null) {
            return;
        }
        for (UUID participant : participantUserIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList()) {
            ensureAssignableUser(actor, participant, office);
            jdbcClient.sql("""
                            INSERT INTO work_task_participants
                                (task_id, user_id, participant_role)
                            VALUES (:taskId, :userId, 'COLLABORATOR')
                            ON CONFLICT DO NOTHING
                            """)
                    .param("taskId", taskId)
                    .param("userId", participant)
                    .update();
        }
    }

    private void recordEvent(
            RequestActor actor,
            UUID taskId,
            String action,
            String fromStatus,
            String toStatus,
            String note
    ) {
        jdbcClient.sql("""
                        INSERT INTO work_task_events
                            (organization_id, task_id, action, actor_user_id,
                             from_status, to_status, note)
                        VALUES
                            (:organizationId, :taskId, :action, :actorId,
                             :fromStatus, :toStatus, :note)
                        """)
                .param("organizationId", actor.organizationId())
                .param("taskId", taskId)
                .param("action", action)
                .param("actorId", actor.userId())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("note", note)
                .update();
    }

    private WorkTaskView mapTask(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new WorkTaskView(
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
                rs.getString("office_name_en"),
                rs.getString("related_business_type"),
                rs.getObject("related_business_id", UUID.class),
                rs.getString("related_business_label"),
                rs.getInt("version"),
                rs.getLong("participant_count"),
                rs.getLong("event_count"),
                rs.getObject("completed_by", UUID.class),
                rs.getString("completed_by_name"),
                rs.getString("completion_note"),
                instant(rs, "cancelled_at"),
                rs.getObject("cancelled_by", UUID.class),
                rs.getString("cancelled_by_name"),
                rs.getString("cancellation_reason")
        );
    }

    private static void requireUpdated(int updated) {
        if (updated == 0) {
            throw new BusinessException(
                    "WORK_TASK_VERSION_CONFLICT",
                    "任务已被其他人更新，请刷新后重试",
                    HttpStatus.CONFLICT
            );
        }
    }

    private static BusinessException forbidden(String code, String message) {
        return new BusinessException(code, message, HttpStatus.FORBIDDEN);
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static String normalizeRelatedType(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record TaskAccess(
            UUID ownerId,
            UUID assignerId,
            String title,
            UUID officeId,
            String status,
            int version
    ) {}

    private record TaskOfficeContext(UUID officeId, UUID matterId) {}
}
