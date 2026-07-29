package com.zoro.legaloa.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.common.IdempotencyService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.workflow.WorkflowController.CompleteTaskRequest;
import com.zoro.legaloa.workflow.WorkflowController.StartWorkflowRequest;
import com.zoro.legaloa.workflow.WorkflowController.TransferTaskRequest;
import com.zoro.legaloa.workflow.WorkflowController.TransferTargetView;
import com.zoro.legaloa.workflow.WorkflowController.WorkflowActionResult;
import com.zoro.legaloa.workflow.WorkflowController.WorkflowInstanceView;
import com.zoro.legaloa.workflow.WorkflowController.WorkflowTaskView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;
    private final AuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final OfficeAccessService officeAccessService;

    public WorkflowService(
            RuntimeService runtimeService,
            TaskService taskService,
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService,
            AuthorizationService authorizationService,
            IdempotencyService idempotencyService,
            OutboxService outboxService,
            OfficeAccessService officeAccessService
    ) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
        this.authorizationService = authorizationService;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.officeAccessService = officeAccessService;
    }

    @Transactional
    public WorkflowInstanceView start(StartWorkflowRequest request, String idempotencyKey) {
        RequestActor actor = actorProvider.current();
        String businessType = normalizeBusinessType(request.businessType());
        String operation = "workflow-start:" + businessType + ":" + request.businessId();
        Optional<JsonNode> replay = idempotencyService.begin(
                actor, operation, idempotencyKey, request
        );
        if (replay.isPresent()) {
            return idempotencyService.deserialize(replay.get(), WorkflowInstanceView.class);
        }
        requireBusinessAccess(actor, businessType, request.businessId());
        requireSubmissionReadiness(actor, businessType, request.businessId());
        existingRunning(actor, businessType, request.businessId()).ifPresent(existing -> {
            throw new BusinessException(
                    "WORKFLOW_ALREADY_RUNNING",
                    "该业务已有进行中的审批流程",
                    HttpStatus.CONFLICT
            );
        });

        Map<String, Object> variables = new LinkedHashMap<>();
        if (request.variables() != null) {
            variables.putAll(request.variables());
        }
        variables.put("organizationId", actor.organizationId().toString());
        variables.put("startedBy", actor.username());
        variables.put("startedByUserId", actor.userId().toString());
        String processKey = WorkflowPolicy.processKey(businessType);
        String businessKey = businessType + ":" + request.businessId();
        ProcessInstance process = runtimeService.startProcessInstanceByKey(
                processKey, businessKey, variables
        );
        UUID linkId;
        try {
            linkId = jdbcClient.sql("""
                            INSERT INTO workflow_links
                                (organization_id, business_type, business_id,
                                 process_definition_key, process_instance_id,
                                 status, started_by)
                            VALUES
                                (:organizationId, :businessType, :businessId,
                                 :processKey, :processInstanceId, 'RUNNING', :startedBy)
                            RETURNING id
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("businessType", businessType)
                    .param("businessId", request.businessId())
                    .param("processKey", processKey)
                    .param("processInstanceId", process.getId())
                    .param("startedBy", actor.userId())
                    .query(UUID.class)
                    .single();
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    "WORKFLOW_ALREADY_RUNNING",
                    "该业务已有进行中的审批流程",
                    HttpStatus.CONFLICT
            );
        }
        WorkflowLink link = new WorkflowLink(
                linkId,
                actor.organizationId(),
                businessType,
                request.businessId(),
                process.getId(),
                actor.userId(),
                "RUNNING"
        );
        recordAction(link, null, "START", actor, null, "RUNNING", null);
        transitionBusiness(link, WorkflowPolicy.reviewState(businessType), actor, "提交审批");
        syncActiveTasks(link);
        auditService.success(actor, "WORKFLOW_START", businessType, request.businessId());
        WorkflowInstanceView response = new WorkflowInstanceView(
                linkId,
                businessType,
                request.businessId(),
                processKey,
                process.getId(),
                "RUNNING",
                null,
                Instant.now()
        );
        idempotencyService.complete(actor, operation, idempotencyKey, 200, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<WorkflowTaskView> tasks() {
        RequestActor actor = actorProvider.current();
        List<String> roles = authorizationService.roles(actor);
        Map<String, Task> unique = new LinkedHashMap<>();
        taskService.createTaskQuery()
                .taskAssignee(actor.username())
                .active()
                .orderByTaskCreateTime().desc()
                .list()
                .forEach(task -> unique.put(task.getId(), task));
        if (!roles.isEmpty()) {
            taskService.createTaskQuery()
                    .taskCandidateGroupIn(roles)
                    .active()
                    .orderByTaskCreateTime().desc()
                    .list()
                    .forEach(task -> unique.putIfAbsent(task.getId(), task));
        }
        return unique.values().stream()
                .filter(task -> actor.username().equals(task.getAssignee())
                        || officeAccessService.canAccessBusiness(
                                actor,
                                findLink(task.getProcessInstanceId()).businessType(),
                                findLink(task.getProcessInstanceId()).businessId()
                        ))
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowTaskView> inbox(String status) {
        RequestActor actor = actorProvider.current();
        String normalizedStatus = status == null || status.isBlank()
                ? "PENDING" : status.toUpperCase(Locale.ROOT);
        List<String> roles = authorizationService.roles(actor);
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT at.flowable_task_id, at.task_name, wl.process_instance_id,
                               wl.business_type, wl.business_id, at.status,
                               au.username AS assignee, at.candidate_group,
                               at.created_at, at.completed_at, at.decision
                        FROM approval_tasks at
                        JOIN workflow_links wl ON wl.id = at.workflow_link_id
                        LEFT JOIN users au ON au.id = at.assignee_user_id
                        WHERE at.organization_id = :organizationId
                          AND at.status = :status
                          AND (
                              at.assignee_user_id = :userId
                              OR at.completed_by = :userId
                              OR (
                                (
                                  (:hasRoles AND at.candidate_group IN (:roles))
                                  OR :viewAll
                                )
                                AND (
                                  :globalAccess
                                  OR (
                                    wl.business_type = 'MATTER'
                                    AND EXISTS (
                                      SELECT 1 FROM matters m
                                      WHERE m.id = wl.business_id
                                        AND (
                                          m.office_id IN (:officeIds)
                                          OR EXISTS (
                                            SELECT 1 FROM matter_members mm
                                            WHERE mm.matter_id = m.id
                                              AND mm.user_id = :userId
                                              AND mm.left_at IS NULL
                                          )
                                        )
                                    )
                                  )
                                  OR (
                                    wl.business_type = 'CONTRACT'
                                    AND EXISTS (
                                      SELECT 1 FROM contracts c
                                      WHERE c.id = wl.business_id
                                        AND (
                                          EXISTS (
                                            SELECT 1 FROM contract_members cm
                                            WHERE cm.contract_id = c.id
                                              AND cm.user_id = :userId
                                          )
                                          OR COALESCE(
                                            (SELECT m.office_id
                                             FROM contract_matters cm
                                             JOIN matters m ON m.id = cm.matter_id
                                             WHERE cm.contract_id = c.id
                                             ORDER BY m.created_at
                                             LIMIT 1),
                                            (SELECT u.primary_office_id FROM users u
                                             WHERE u.id = c.created_by)
                                          ) IN (:officeIds)
                                        )
                                    )
                                  )
                                  OR (
                                    wl.business_type = 'SEAL_REQUEST'
                                    AND EXISTS (
                                      SELECT 1 FROM seal_requests s
                                      WHERE s.id = wl.business_id
                                        AND (
                                          s.requested_by = :userId
                                          OR COALESCE(
                                            (SELECT m.office_id FROM matters m
                                             WHERE m.id = s.matter_id),
                                            (SELECT m.office_id
                                             FROM contract_matters cm
                                             JOIN matters m ON m.id = cm.matter_id
                                             WHERE cm.contract_id = s.contract_id
                                             ORDER BY m.created_at
                                             LIMIT 1),
                                            (SELECT u.primary_office_id FROM users u
                                             WHERE u.id = s.requested_by)
                                          ) IN (:officeIds)
                                        )
                                    )
                                  )
                                  OR (
                                    wl.business_type = 'LEAVE_REQUEST'
                                    AND EXISTS (
                                      SELECT 1 FROM leave_requests l
                                      WHERE l.id = wl.business_id
                                        AND (
                                          l.applicant_user_id = :userId
                                          OR l.office_id IN (:officeIds)
                                        )
                                    )
                                  )
                                  OR (
                                    wl.business_type = 'EXPENSE_CLAIM'
                                    AND EXISTS (
                                      SELECT 1 FROM expense_claims e
                                      WHERE e.id = wl.business_id
                                        AND (
                                          e.applicant_user_id = :userId
                                          OR e.office_id IN (:officeIds)
                                        )
                                    )
                                  )
                                )
                              )
                          )
                        ORDER BY COALESCE(at.completed_at, at.created_at) DESC
                        LIMIT 300
                        """)
                .param("organizationId", actor.organizationId())
                .param("status", normalizedStatus)
                .param("userId", actor.userId())
                .param("hasRoles", !roles.isEmpty())
                .param("roles", roles.isEmpty() ? List.of("__NONE__") : roles)
                .param("viewAll", authorizationService.hasPermission(actor, "WORKFLOW_VIEW_ALL"))
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new WorkflowTaskView(
                        rs.getString("flowable_task_id"),
                        rs.getString("task_name"),
                        rs.getString("process_instance_id"),
                        rs.getString("business_type") + ":" + rs.getObject("business_id", UUID.class),
                        rs.getString("business_type"),
                        rs.getObject("business_id", UUID.class),
                        rs.getString("assignee"),
                        rs.getString("candidate_group"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("decision")
                ))
                .list();
    }

    @Transactional
    public WorkflowActionResult complete(
            String taskId,
            CompleteTaskRequest request,
            String idempotencyKey
    ) {
        RequestActor actor = actorProvider.current();
        String decision = normalizeDecision(request);
        if ("REJECT".equals(decision) && trimToNull(request.comment()) == null) {
            throw new BusinessException(
                    "REJECT_REASON_REQUIRED", "驳回审批必须填写原因", HttpStatus.BAD_REQUEST
            );
        }
        String operation = "workflow-task-complete:" + taskId;
        Optional<JsonNode> replay = idempotencyService.begin(
                actor, operation, idempotencyKey, Map.of(
                        "taskId", taskId,
                        "decision", decision,
                        "comment", request.comment() == null ? "" : request.comment()
                )
        );
        if (replay.isPresent()) {
            return idempotencyService.deserialize(replay.get(), WorkflowActionResult.class);
        }
        Task task = requireActionableTask(actor, taskId);
        WorkflowLink link = findLink(task.getProcessInstanceId());
        if (task.getAssignee() == null) {
            taskService.claim(taskId, actor.username());
            jdbcClient.sql("""
                            UPDATE approval_tasks
                            SET assignee_user_id = :userId, claimed_at = now(), updated_at = now()
                            WHERE flowable_task_id = :taskId
                            """)
                    .param("userId", actor.userId())
                    .param("taskId", taskId)
                    .update();
        }
        String comment = trimToNull(request.comment());
        if (comment != null) {
            taskService.addComment(taskId, task.getProcessInstanceId(), comment);
        }

        WorkflowActionResult response;
        if ("REJECT".equals(decision)) {
            runtimeService.deleteProcessInstance(
                    task.getProcessInstanceId(),
                    "Rejected by " + actor.username()
            );
            completeTaskReadModel(taskId, actor.userId(), "REJECTED", "REJECT", comment);
            finishWorkflow(link, "REJECTED", "REJECTED", actor, comment);
            recordAction(link, task, "REJECT", actor, "RUNNING", "REJECTED", comment);
            notifyInitiator(link, "审批已驳回", task.getName() + " 已由 " + actor.displayName() + " 驳回");
            response = new WorkflowActionResult(
                    link.id(), taskId, "REJECTED", "REJECT", false, null
            );
        } else {
            Map<String, Object> variables = new LinkedHashMap<>();
            if (request.variables() != null) {
                variables.putAll(request.variables());
            }
            variables.put("approved", true);
            variables.put("decision", "APPROVE");
            taskService.complete(taskId, variables);
            completeTaskReadModel(taskId, actor.userId(), "APPROVED", "APPROVE", comment);
            recordAction(link, task, "APPROVE", actor, "PENDING", "APPROVED", comment);
            ProcessInstance process = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(task.getProcessInstanceId())
                    .singleResult();
            if (process == null) {
                finishWorkflow(
                        link,
                        "COMPLETED",
                        WorkflowPolicy.approvedState(link.businessType()),
                        actor,
                        comment
                );
                notifyInitiator(
                        link,
                        "审批已通过",
                        WorkflowPolicy.businessLabel(link.businessType()) + "审批已完成"
                );
                response = new WorkflowActionResult(
                        link.id(), taskId, "COMPLETED", "APPROVE", false, null
                );
            } else {
                List<Task> next = syncActiveTasks(link);
                response = new WorkflowActionResult(
                        link.id(),
                        taskId,
                        "RUNNING",
                        "APPROVE",
                        !next.isEmpty(),
                        next.isEmpty() ? null : next.getFirst().getName()
                );
            }
        }
        auditService.success(actor, "WORKFLOW_TASK_" + decision, "WORKFLOW_LINK", link.id());
        idempotencyService.complete(actor, operation, idempotencyKey, 200, response);
        return response;
    }

    @Transactional
    public WorkflowActionResult transfer(
            String taskId,
            TransferTaskRequest request,
            String idempotencyKey
    ) {
        RequestActor actor = actorProvider.current();
        String operation = "workflow-task-transfer:" + taskId;
        Optional<JsonNode> replay = idempotencyService.begin(
                actor, operation, idempotencyKey, request
        );
        if (replay.isPresent()) {
            return idempotencyService.deserialize(replay.get(), WorkflowActionResult.class);
        }
        Task task = requireActionableTask(actor, taskId);
        WorkflowLink link = findLink(task.getProcessInstanceId());
        UserTarget target = jdbcClient.sql("""
                        SELECT id, username, display_name
                        FROM users
                        WHERE id = :userId
                          AND organization_id = :organizationId
                          AND status = 'ACTIVE' AND deleted_at IS NULL
                        """)
                .param("userId", request.targetUserId())
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new UserTarget(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "TRANSFER_TARGET_INVALID", "转交目标用户无效", HttpStatus.BAD_REQUEST
                ));
        RequestActor targetActor = new RequestActor(
                target.id(), actor.organizationId(), target.username(), target.displayName()
        );
        if (!officeAccessService.canAccessBusiness(
                targetActor, link.businessType(), link.businessId()
        )) {
            throw new BusinessException(
                    "TRANSFER_TARGET_SCOPE_DENIED",
                    "转交目标无权访问该业务所属办公室或案件",
                    HttpStatus.BAD_REQUEST
            );
        }
        taskService.setAssignee(taskId, target.username());
        jdbcClient.sql("""
                        UPDATE approval_tasks
                        SET assignee_user_id = :targetUserId, candidate_group = NULL,
                            claimed_at = now(), updated_at = now()
                        WHERE flowable_task_id = :taskId
                        """)
                .param("targetUserId", target.id())
                .param("taskId", taskId)
                .update();
        recordAction(
                link, task, "TRANSFER", actor, "PENDING", "PENDING",
                "转交给" + target.displayName() + (
                        request.comment() == null ? "" : "：" + request.comment().trim()
                )
        );
        outboxService.enqueueNotification(
                actor.organizationId(),
                target.id(),
                "APPROVAL_TRANSFERRED",
                "收到转交审批",
                actor.displayName() + " 将“" + task.getName() + "”转交给你",
                link.businessType(),
                link.businessId(),
                "/workflows?taskId=" + taskId,
                "HIGH",
                "approval-transfer:" + taskId + ":" + target.id()
        );
        WorkflowActionResult response = new WorkflowActionResult(
                link.id(), taskId, "RUNNING", "TRANSFER", true, task.getName()
        );
        auditService.success(actor, "WORKFLOW_TASK_TRANSFER", "WORKFLOW_LINK", link.id());
        idempotencyService.complete(actor, operation, idempotencyKey, 200, response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<TransferTargetView> transferTargets(String taskId) {
        RequestActor actor = actorProvider.current();
        Task task = requireActionableTask(actor, taskId);
        WorkflowLink link = findLink(task.getProcessInstanceId());
        return jdbcClient.sql("""
                        SELECT id, username, display_name
                        FROM users
                        WHERE organization_id = :organizationId
                          AND status = 'ACTIVE' AND deleted_at IS NULL
                          AND username <> COALESCE(:assignee, '')
                        ORDER BY display_name
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("assignee", task.getAssignee() == null ? "" : task.getAssignee())
                .query((rs, rowNum) -> new UserTarget(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name")
                ))
                .list()
                .stream()
                .filter(target -> officeAccessService.canAccessBusiness(
                        new RequestActor(
                                target.id(), actor.organizationId(),
                                target.username(), target.displayName()
                        ),
                        link.businessType(),
                        link.businessId()
                ))
                .map(target -> new TransferTargetView(
                        target.id(), target.username(), target.displayName()
                ))
                .toList();
    }

    @Transactional
    public void remind(String taskId) {
        RequestActor actor = actorProvider.current();
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            throw new BusinessException(
                    "WORKFLOW_TASK_NOT_FOUND", "审批任务不存在", HttpStatus.NOT_FOUND
            );
        }
        WorkflowLink link = findLink(task.getProcessInstanceId());
        if (!actor.userId().equals(link.startedBy())
                && (!authorizationService.hasPermission(actor, "WORKFLOW_VIEW_ALL")
                || !officeAccessService.canAccessBusiness(
                        actor, link.businessType(), link.businessId()
                ))) {
            throw new BusinessException(
                    "WORKFLOW_REMIND_DENIED", "只有发起人或审批管理员可以催办", HttpStatus.FORBIDDEN
            );
        }
        Boolean recentlyReminded = jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM workflow_action_logs
                          WHERE workflow_link_id = :workflowLinkId
                            AND task_id = :taskId
                            AND action = 'REMIND'
                            AND occurred_at > now() - INTERVAL '30 minutes'
                        )
                        """)
                .param("workflowLinkId", link.id())
                .param("taskId", taskId)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(recentlyReminded)) {
            throw new BusinessException(
                    "WORKFLOW_REMIND_RATE_LIMITED",
                    "同一审批任务 30 分钟内只能催办一次",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
        Set<UUID> recipients = taskRecipients(task, link);
        for (UUID recipient : recipients) {
            outboxService.enqueueNotification(
                    link.organizationId(),
                    recipient,
                    "APPROVAL_REMINDER",
                    "审批催办",
                    actor.displayName() + " 催办：“" + task.getName() + "”",
                    link.businessType(),
                    link.businessId(),
                    "/workflows?taskId=" + taskId,
                    "URGENT",
                    "approval-remind:" + taskId + ":" + recipient + ":" + java.time.LocalDate.now()
            );
        }
        recordAction(link, task, "REMIND", actor, "PENDING", "PENDING", null);
        auditService.success(actor, "WORKFLOW_TASK_REMIND", "WORKFLOW_LINK", link.id());
    }

    private Task requireActionableTask(RequestActor actor, String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null || !canAct(actor, task)) {
            throw new BusinessException(
                    "WORKFLOW_TASK_DENIED", "审批任务不存在或你无权处理", HttpStatus.FORBIDDEN
            );
        }
        return task;
    }

    private boolean canAct(RequestActor actor, Task task) {
        if (actor.username().equals(task.getAssignee())) {
            return true;
        }
        Set<String> candidateGroups = candidateGroups(task);
        if (candidateGroups.isEmpty()
                || authorizationService.roles(actor).stream().noneMatch(candidateGroups::contains)) {
            return false;
        }
        WorkflowLink link = findLink(task.getProcessInstanceId());
        return officeAccessService.canAccessBusiness(
                actor, link.businessType(), link.businessId()
        );
    }

    private List<Task> syncActiveTasks(WorkflowLink link) {
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(link.processInstanceId())
                .active()
                .list();
        for (Task task : activeTasks) {
            Set<String> groups = candidateGroups(task);
            String candidateGroup = groups.stream().sorted().findFirst().orElse(null);
            UUID assigneeUserId = findUserId(link.organizationId(), task.getAssignee()).orElse(null);
            jdbcClient.sql("""
                            INSERT INTO approval_tasks
                                (organization_id, workflow_link_id, flowable_task_id,
                                 task_definition_key, task_name, assignee_user_id,
                                 candidate_group, created_at, due_at)
                            VALUES
                                (:organizationId, :workflowLinkId, :taskId,
                                 :definitionKey, :taskName, :assigneeUserId,
                                 :candidateGroup, :createdAt, CAST(:dueAt AS timestamptz))
                            ON CONFLICT (flowable_task_id) DO UPDATE
                            SET assignee_user_id = EXCLUDED.assignee_user_id,
                                candidate_group = EXCLUDED.candidate_group,
                                due_at = EXCLUDED.due_at,
                                updated_at = now()
                            """)
                    .param("organizationId", link.organizationId())
                    .param("workflowLinkId", link.id())
                    .param("taskId", task.getId())
                    .param("definitionKey", task.getTaskDefinitionKey())
                    .param("taskName", task.getName())
                    .param("assigneeUserId", assigneeUserId)
                    .param("candidateGroup", candidateGroup)
                    .param("createdAt", java.sql.Timestamp.from(task.getCreateTime().toInstant()))
                    .param(
                            "dueAt",
                            task.getDueDate() == null
                                    ? null
                                    : java.sql.Timestamp.from(task.getDueDate().toInstant())
                    )
                    .update();
            for (UUID recipient : taskRecipients(task, link)) {
                outboxService.enqueueNotification(
                        link.organizationId(),
                        recipient,
                        "APPROVAL_PENDING",
                        "新的审批待办",
                        task.getName() + " 等待你处理",
                        link.businessType(),
                        link.businessId(),
                        "/workflows?taskId=" + task.getId(),
                        "HIGH",
                        "approval-task:" + task.getId()
                );
            }
        }
        return activeTasks;
    }

    private Set<UUID> taskRecipients(Task task, WorkflowLink link) {
        Set<UUID> recipients = new LinkedHashSet<>();
        findUserId(link.organizationId(), task.getAssignee()).ifPresent(recipients::add);
        Set<String> groups = candidateGroups(task);
        if (!groups.isEmpty()) {
            List<UserTarget> candidates = jdbcClient.sql("""
                            SELECT DISTINCT u.id
                                 , u.username, u.display_name
                            FROM users u
                            JOIN user_roles ur ON ur.user_id = u.id
                            JOIN roles r ON r.id = ur.role_id
                            WHERE u.organization_id = :organizationId
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                              AND r.code IN (:groups)
                            """)
                    .param("organizationId", link.organizationId())
                    .param("groups", groups)
                    .query((rs, rowNum) -> new UserTarget(
                            rs.getObject("id", UUID.class),
                            rs.getString("username"),
                            rs.getString("display_name")
                    ))
                    .list();
            candidates.stream()
                    .filter(candidate -> officeAccessService.canAccessBusiness(
                            new RequestActor(
                                    candidate.id(),
                                    link.organizationId(),
                                    candidate.username(),
                                    candidate.displayName()
                            ),
                            link.businessType(),
                            link.businessId()
                    ))
                    .map(UserTarget::id)
                    .forEach(recipients::add);
        }
        return recipients;
    }

    private Set<String> candidateGroups(Task task) {
        return taskService.getIdentityLinksForTask(task.getId()).stream()
                .filter(link -> "candidate".equals(link.getType()))
                .map(IdentityLink::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void finishWorkflow(
            WorkflowLink link,
            String workflowStatus,
            String businessStatus,
            RequestActor actor,
            String reason
    ) {
        jdbcClient.sql("""
                        UPDATE workflow_links
                        SET status = :status, decision = :decision,
                            completed_at = now(), updated_at = now(), version = version + 1
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                .param("status", workflowStatus)
                .param("decision", "REJECTED".equals(workflowStatus) ? "REJECT" : "APPROVE")
                .param("id", link.id())
                .update();
        if ("REJECTED".equals(workflowStatus)) {
            jdbcClient.sql("""
                            UPDATE approval_tasks
                            SET status = 'CANCELLED', completed_at = now(), updated_at = now()
                            WHERE workflow_link_id = :workflowLinkId AND status = 'PENDING'
                            """)
                    .param("workflowLinkId", link.id())
                    .update();
        }
        transitionBusiness(link, businessStatus, actor, reason);
        if ("COMPLETED".equals(workflowStatus) && "CONTRACT".equals(link.businessType())) {
            markContractVersionReviewed(link);
        }
    }

    private void markContractVersionReviewed(WorkflowLink link) {
        jdbcClient.sql("""
                        UPDATE contract_versions
                        SET status = 'REVIEWED', updated_at = now()
                        WHERE id = (
                            SELECT cv.id
                            FROM contract_versions cv
                            JOIN document_versions dv
                              ON dv.id = cv.primary_document_version_id
                            WHERE cv.contract_id = :contractId
                              AND cv.status = 'DRAFT'
                              AND dv.ingestion_status = 'AVAILABLE'
                            ORDER BY cv.version_number DESC
                            LIMIT 1
                        )
                        """)
                .param("contractId", link.businessId())
                .update();
    }

    private void completeTaskReadModel(
            String taskId,
            UUID actorId,
            String status,
            String decision,
            String comment
    ) {
        jdbcClient.sql("""
                        UPDATE approval_tasks
                        SET status = :status, completed_by = :actorId, completed_at = now(),
                            decision = :decision, comment = :comment, updated_at = now()
                        WHERE flowable_task_id = :taskId
                        """)
                .param("status", status)
                .param("actorId", actorId)
                .param("decision", decision)
                .param("comment", comment)
                .param("taskId", taskId)
                .update();
    }

    private void transitionBusiness(
            WorkflowLink link,
            String toState,
            RequestActor actor,
            String reason
    ) {
        String table = WorkflowPolicy.businessTable(link.businessType());
        String fromState = jdbcClient.sql("""
                        SELECT status FROM %s
                        WHERE id = :id AND organization_id = :organizationId
                        FOR UPDATE
                        """.formatted(table))
                .param("id", link.businessId())
                .param("organizationId", link.organizationId())
                .query(String.class)
                .single();
        if (fromState.equals(toState)) {
            return;
        }
        jdbcClient.sql("""
                        UPDATE %s
                        SET status = :toState, updated_at = now()
                        WHERE id = :id AND organization_id = :organizationId
                        """.formatted(table))
                .param("toState", toState)
                .param("id", link.businessId())
                .param("organizationId", link.organizationId())
                .update();
        jdbcClient.sql("""
                        INSERT INTO business_state_transitions
                            (business_type, business_id, from_state, to_state,
                             workflow_link_id, changed_by, reason)
                        VALUES
                            (:businessType, :businessId, :fromState, :toState,
                             :workflowLinkId, :changedBy, :reason)
                        """)
                .param("businessType", link.businessType())
                .param("businessId", link.businessId())
                .param("fromState", fromState)
                .param("toState", toState)
                .param("workflowLinkId", link.id())
                .param("changedBy", actor.userId())
                .param("reason", reason)
                .update();
    }

    private void recordAction(
            WorkflowLink link,
            Task task,
            String action,
            RequestActor actor,
            String fromStatus,
            String toStatus,
            String comment
    ) {
        jdbcClient.sql("""
                        INSERT INTO workflow_action_logs
                            (organization_id, workflow_link_id, task_id, task_name,
                             action, actor_user_id, from_status, to_status, comment)
                        VALUES
                            (:organizationId, :workflowLinkId, :taskId, :taskName,
                             :action, :actorId, :fromStatus, :toStatus, :comment)
                        """)
                .param("organizationId", link.organizationId())
                .param("workflowLinkId", link.id())
                .param("taskId", task == null ? null : task.getId())
                .param("taskName", task == null ? null : task.getName())
                .param("action", action)
                .param("actorId", actor.userId())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("comment", comment)
                .update();
    }

    private void notifyInitiator(WorkflowLink link, String title, String content) {
        outboxService.enqueueNotification(
                link.organizationId(),
                link.startedBy(),
                "APPROVAL_RESULT",
                title,
                content,
                link.businessType(),
                link.businessId(),
                "/workflows?instanceId=" + link.id(),
                "HIGH",
                "approval-result:" + link.id()
        );
    }

    private WorkflowTaskView toView(Task task) {
        WorkflowLink link = findLink(task.getProcessInstanceId());
        String group = candidateGroups(task).stream().sorted().findFirst().orElse(null);
        return new WorkflowTaskView(
                task.getId(),
                task.getName(),
                task.getProcessInstanceId(),
                link.businessType() + ":" + link.businessId(),
                link.businessType(),
                link.businessId(),
                task.getAssignee(),
                group,
                "PENDING",
                task.getCreateTime().toInstant(),
                null,
                null
        );
    }

    private Optional<UUID> findUserId(UUID organizationId, String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        SELECT id FROM users
                        WHERE organization_id = :organizationId
                          AND username = :username
                          AND status = 'ACTIVE' AND deleted_at IS NULL
                        """)
                .param("organizationId", organizationId)
                .param("username", username)
                .query(UUID.class)
                .optional();
    }

    private Optional<WorkflowLink> existingRunning(
            RequestActor actor,
            String businessType,
            UUID businessId
    ) {
        return jdbcClient.sql("""
                        SELECT id, organization_id, business_type, business_id,
                               process_instance_id, started_by, status
                        FROM workflow_links
                        WHERE organization_id = :organizationId
                          AND business_type = :businessType
                          AND business_id = :businessId
                          AND status = 'RUNNING'
                        """)
                .param("organizationId", actor.organizationId())
                .param("businessType", businessType)
                .param("businessId", businessId)
                .query(WorkflowService::mapLink)
                .optional();
    }

    private WorkflowLink findLink(String processInstanceId) {
        return jdbcClient.sql("""
                        SELECT id, organization_id, business_type, business_id,
                               process_instance_id, started_by, status
                        FROM workflow_links
                        WHERE process_instance_id = :processInstanceId
                        """)
                .param("processInstanceId", processInstanceId)
                .query(WorkflowService::mapLink)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "WORKFLOW_LINK_NOT_FOUND",
                        "审批流程缺少业务关联",
                        HttpStatus.CONFLICT
                ));
    }

    private void requireBusinessAccess(
            RequestActor actor,
            String businessType,
            UUID businessId
    ) {
        String membership = switch (businessType) {
            case "MATTER" -> """
                    EXISTS (
                        SELECT 1 FROM matter_members mm
                        WHERE mm.matter_id = b.id AND mm.user_id = :userId
                          AND mm.left_at IS NULL
                    )
                    """;
            case "CONTRACT" -> """
                    EXISTS (
                        SELECT 1 FROM contract_members cm
                        WHERE cm.contract_id = b.id AND cm.user_id = :userId
                    )
                    """;
            case "SEAL_REQUEST" -> "b.requested_by = :userId";
            case "LEAVE_REQUEST", "EXPENSE_CLAIM" -> "b.applicant_user_id = :userId";
            default -> throw new IllegalStateException();
        };
        String table = WorkflowPolicy.businessTable(businessType);
        Boolean exists = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM %s b
                            WHERE b.id = :id
                              AND b.organization_id = :organizationId
                              AND (
                                  %s
                                  OR EXISTS (
                                      SELECT 1 FROM user_roles ur
                                      JOIN roles r ON r.id = ur.role_id
                                      WHERE ur.user_id = :userId
                                        AND r.code IN ('ADMIN', 'MANAGING_PARTNER')
                                  )
                              )
                        )
                        """.formatted(table, membership))
                .param("id", businessId)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(
                    "WORKFLOW_BUSINESS_NOT_FOUND",
                    "审批业务记录不存在或你无权发起",
                    HttpStatus.NOT_FOUND
            );
        }
    }

    private void requireSubmissionReadiness(
            RequestActor actor,
            String businessType,
            UUID businessId
    ) {
        if (!"CONTRACT".equals(businessType)) {
            return;
        }
        Boolean ready = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM contracts c
                            JOIN contract_versions cv ON cv.contract_id = c.id
                            JOIN document_versions dv
                              ON dv.id = cv.primary_document_version_id
                            WHERE c.id = :contractId
                              AND c.organization_id = :organizationId
                              AND c.status IN ('DRAFT', 'REJECTED')
                              AND c.deleted_at IS NULL
                              AND cv.status IN ('DRAFT', 'REVIEWED')
                              AND dv.ingestion_status = 'AVAILABLE'
                        )
                        """)
                .param("contractId", businessId)
                .param("organizationId", actor.organizationId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(ready)) {
            throw new BusinessException(
                    "CONTRACT_REVIEW_VERSION_REQUIRED",
                    "发起合同审批前必须上传并登记一个已通过安全扫描的送审版本",
                    HttpStatus.CONFLICT
            );
        }
    }

    private String normalizeBusinessType(String businessType) {
        return WorkflowPolicy.normalizeBusinessType(businessType);
    }

    private String normalizeDecision(CompleteTaskRequest request) {
        return WorkflowPolicy.normalizeDecision(request.decision(), request.variables());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static WorkflowLink mapLink(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new WorkflowLink(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("business_type"),
                rs.getObject("business_id", UUID.class),
                rs.getString("process_instance_id"),
                rs.getObject("started_by", UUID.class),
                rs.getString("status")
        );
    }

    private record WorkflowLink(
            UUID id,
            UUID organizationId,
            String businessType,
            UUID businessId,
            String processInstanceId,
            UUID startedBy,
            String status
    ) {}

    private record UserTarget(UUID id, String username, String displayName) {}
}
