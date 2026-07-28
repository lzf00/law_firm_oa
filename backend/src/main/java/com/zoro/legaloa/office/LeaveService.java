package com.zoro.legaloa.office;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.office.LeaveController.CreateLeaveRequest;
import com.zoro.legaloa.office.LeaveController.LeaveView;
import com.zoro.legaloa.workflow.WorkflowController.StartWorkflowRequest;
import com.zoro.legaloa.workflow.WorkflowService;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveService {
    private static final Set<String> TYPES = Set.of(
            "ANNUAL", "PERSONAL", "SICK", "MARRIAGE", "MATERNITY", "OTHER"
    );
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final WorkflowService workflowService;
    private final OfficeAccessService officeAccessService;

    public LeaveService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            AuditService auditService,
            WorkflowService workflowService,
            OfficeAccessService officeAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.workflowService = workflowService;
        this.officeAccessService = officeAccessService;
    }

    @Transactional(readOnly = true)
    public List<LeaveView> list() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT l.id, l.request_number, l.applicant_user_id,
                               u.display_name AS applicant_name, l.leave_type,
                               l.start_at, l.end_at, l.duration_hours, l.reason,
                               l.emergency_contact, l.status, l.created_at,
                               l.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en
                        FROM leave_requests l
                        JOIN users u ON u.id = l.applicant_user_id
                        LEFT JOIN offices o ON o.id = l.office_id
                        WHERE l.organization_id = :organizationId
                          AND (
                            l.applicant_user_id = :userId
                            OR (
                              :viewAll
                              AND (:globalAccess OR l.office_id IN (:officeIds))
                            )
                          )
                        ORDER BY l.created_at DESC
                        LIMIT 300
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", authorizationService.hasPermission(actor, "LEAVE_VIEW_ALL"))
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new LeaveView(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_number"),
                        rs.getObject("applicant_user_id", UUID.class),
                        rs.getString("applicant_name"),
                        rs.getString("leave_type"),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant(),
                        rs.getBigDecimal("duration_hours"),
                        rs.getString("reason"),
                        rs.getString("emergency_contact"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en")
                )).list();
    }

    @Transactional
    public LeaveView create(CreateLeaveRequest request) {
        RequestActor actor = actorProvider.current();
        String type = request.leaveType().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new BusinessException(
                    "LEAVE_TYPE_INVALID", "请假类型无效", HttpStatus.BAD_REQUEST
            );
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BusinessException(
                    "LEAVE_TIME_INVALID", "结束时间必须晚于开始时间", HttpStatus.BAD_REQUEST
            );
        }
        UUID officeId = officeAccessService.resolveAccessibleOffice(actor, null);
        String number = "LV-" + LocalDate.now().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID id = jdbcClient.sql("""
                        INSERT INTO leave_requests
                            (organization_id, request_number, applicant_user_id,
                             leave_type, start_at, end_at, duration_hours,
                             reason, emergency_contact, office_id)
                        VALUES
                            (:organizationId, :requestNumber, :applicantId,
                             :leaveType, :startAt, :endAt, :durationHours,
                             :reason, :emergencyContact, :officeId)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("requestNumber", number)
                .param("applicantId", actor.userId())
                .param("leaveType", type)
                .param("startAt", java.sql.Timestamp.from(request.startAt()))
                .param("endAt", java.sql.Timestamp.from(request.endAt()))
                .param("durationHours", request.durationHours())
                .param("reason", request.reason().trim())
                .param("emergencyContact", trim(request.emergencyContact()))
                .param("officeId", officeId)
                .query(UUID.class).single();
        auditService.success(actor, "LEAVE_CREATE", "LEAVE_REQUEST", id);
        return findVisible(id);
    }

    @Transactional
    public LeaveView submit(UUID id, String idempotencyKey) {
        RequestActor actor = actorProvider.current();
        LeaveView leave = findVisible(id);
        if (!leave.applicantUserId().equals(actor.userId()) || !"DRAFT".equals(leave.status())) {
            throw new BusinessException(
                    "LEAVE_SUBMIT_INVALID", "只有申请人可以提交草稿", HttpStatus.CONFLICT
            );
        }
        try {
            workflowService.start(
                    new StartWorkflowRequest(
                            "LEAVE_REQUEST", id,
                            Map.of("durationHours", leave.durationHours())
                    ),
                    idempotencyKey
            );
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "LEAVE_TIME_CONFLICT",
                    "该时间段已有生效中的请假申请",
                    HttpStatus.CONFLICT
            );
        }
        auditService.success(actor, "LEAVE_SUBMIT", "LEAVE_REQUEST", id);
        return findVisible(id);
    }

    private LeaveView findVisible(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "LEAVE_NOT_FOUND", "请假申请不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
