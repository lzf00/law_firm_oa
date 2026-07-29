package com.zoro.legaloa.office;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.office.ExpenseController.CreateExpenseRequest;
import com.zoro.legaloa.office.ExpenseController.ExpenseItemRequest;
import com.zoro.legaloa.office.ExpenseController.ExpenseItemView;
import com.zoro.legaloa.office.ExpenseController.ExpenseView;
import com.zoro.legaloa.office.ExpenseController.ReceiptCheckRequest;
import com.zoro.legaloa.office.ExpenseController.ReceiptCheckView;
import com.zoro.legaloa.workflow.WorkflowController.StartWorkflowRequest;
import com.zoro.legaloa.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final WorkflowService workflowService;
    private final OfficeAccessService officeAccessService;
    private final DocumentAccessService documentAccessService;

    public ExpenseService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            AuditService auditService,
            OutboxService outboxService,
            WorkflowService workflowService,
            OfficeAccessService officeAccessService,
            DocumentAccessService documentAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.workflowService = workflowService;
        this.officeAccessService = officeAccessService;
        this.documentAccessService = documentAccessService;
    }

    @Transactional(readOnly = true)
    public List<ExpenseView> list() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        List<ExpenseBase> claims = jdbcClient.sql("""
                        SELECT e.id, e.claim_number, e.applicant_user_id,
                               u.display_name AS applicant_name, e.title, e.purpose,
                               e.matter_id, m.matter_number, e.total_amount, e.currency,
                               e.status, e.paid_at, e.created_at, e.office_id,
                               o.name_zh AS office_name_zh, o.name_en AS office_name_en,
                               review.task_definition_key AS review_stage,
                               review.task_name AS review_task_name,
                               review.candidate_group AS review_group
                        FROM expense_claims e
                        JOIN users u ON u.id = e.applicant_user_id
                        LEFT JOIN matters m ON m.id = e.matter_id
                        LEFT JOIN offices o ON o.id = e.office_id
                        LEFT JOIN LATERAL (
                          SELECT task.task_definition_key, task.task_name,
                                 task.candidate_group
                          FROM workflow_links link
                          JOIN approval_tasks task ON task.workflow_link_id = link.id
                          WHERE link.organization_id = e.organization_id
                            AND link.business_type = 'EXPENSE_CLAIM'
                            AND link.business_id = e.id
                            AND link.status = 'RUNNING'
                            AND task.status = 'PENDING'
                          ORDER BY task.created_at DESC
                          LIMIT 1
                        ) review ON TRUE
                        WHERE e.organization_id = :organizationId
                          AND (
                            e.applicant_user_id = :userId
                            OR (
                              :viewAll
                              AND (:globalAccess OR e.office_id IN (:officeIds))
                            )
                          )
                        ORDER BY e.created_at DESC
                        LIMIT 300
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", authorizationService.hasPermission(actor, "EXPENSE_VIEW_ALL"))
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new ExpenseBase(
                        rs.getObject("id", UUID.class),
                        rs.getString("claim_number"),
                        rs.getObject("applicant_user_id", UUID.class),
                        rs.getString("applicant_name"),
                        rs.getString("title"),
                        rs.getString("purpose"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getTimestamp("paid_at") == null
                                ? null : rs.getTimestamp("paid_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en"),
                        rs.getString("review_stage"),
                        rs.getString("review_task_name"),
                        rs.getString("review_group")
                )).list();
        return claims.stream().map(base -> new ExpenseView(
                base.id(), base.claimNumber(), base.applicantUserId(), base.applicantName(),
                base.title(), base.purpose(), base.matterId(), base.matterNumber(),
                base.totalAmount(), base.currency(), base.status(), base.paidAt(),
                base.createdAt(), base.officeId(), base.officeNameZh(),
                base.officeNameEn(), base.reviewStage(), base.reviewTaskName(),
                base.reviewGroup(), items(base.id())
        )).toList();
    }

    @Transactional
    public ExpenseView create(CreateExpenseRequest request) {
        RequestActor actor = actorProvider.current();
        ExpenseOffice office;
        if (request.matterId() != null) {
            OfficeAccessScope scope = officeAccessService.scope(actor);
            office = jdbcClient.sql("""
                            SELECT m.office_id, m.billing_currency
                            FROM matters m
                            WHERE m.id = :id AND m.organization_id = :organizationId
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
                    .param("id", request.matterId())
                    .param("organizationId", actor.organizationId())
                    .param("globalAccess", scope.globalAccess())
                    .param("userId", actor.userId())
                    .query((rs, rowNum) -> new ExpenseOffice(
                            rs.getObject("office_id", UUID.class),
                            rs.getString("billing_currency")
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            "EXPENSE_MATTER_INVALID", "关联案件无效或无权访问",
                            HttpStatus.BAD_REQUEST
                    ));
        } else {
            UUID officeId = officeAccessService.resolveAccessibleOffice(actor, null);
            office = jdbcClient.sql("""
                            SELECT id, default_currency
                            FROM offices
                            WHERE id = :officeId AND organization_id = :organizationId
                            """)
                    .param("officeId", officeId)
                    .param("organizationId", actor.organizationId())
                    .query((rs, rowNum) -> new ExpenseOffice(
                            rs.getObject("id", UUID.class),
                            rs.getString("default_currency")
                    ))
                    .single();
        }
        Map<UUID, ReceiptContext> receipts = validateReceipts(actor, request);
        BigDecimal total = request.items().stream()
                .map(ExpenseItemRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String number = "EX-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID id = jdbcClient.sql("""
                        INSERT INTO expense_claims
                            (organization_id, claim_number, applicant_user_id,
                             matter_id, title, purpose, total_amount, currency, office_id)
                        VALUES
                            (:organizationId, :claimNumber, :applicantId,
                             :matterId, :title, :purpose, :totalAmount, :currency, :officeId)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("claimNumber", number)
                .param("applicantId", actor.userId())
                .param("matterId", request.matterId())
                .param("title", request.title().trim())
                .param("purpose", request.purpose().trim())
                .param("totalAmount", total)
                .param("currency", office.currency())
                .param("officeId", office.officeId())
                .query(UUID.class).single();
        for (ExpenseItemRequest item : request.items()) {
            ReceiptContext receipt = item.receiptDocumentId() == null
                    ? null : receipts.get(item.receiptDocumentId());
            jdbcClient.sql("""
                            INSERT INTO expense_items
                                (expense_claim_id, category, occurred_on,
                                 description, amount, receipt_document_id,
                                 receipt_document_version_id, receipt_sha256)
                            VALUES
                                (:claimId, :category, :occurredOn,
                                 :description, :amount, :receiptDocumentId,
                                 :receiptVersionId, :receiptSha256)
                            """)
                    .param("claimId", id)
                    .param("category", item.category().trim())
                    .param("occurredOn", item.occurredOn())
                    .param("description", item.description().trim())
                    .param("amount", item.amount())
                    .param("receiptDocumentId", item.receiptDocumentId())
                    .param("receiptVersionId", receipt == null ? null : receipt.versionId())
                    .param("receiptSha256", receipt == null ? null : receipt.sha256())
                    .update();
        }
        auditService.success(actor, "EXPENSE_CREATE", "EXPENSE_CLAIM", id);
        return findVisible(id);
    }

    @Transactional(readOnly = true)
    public ReceiptCheckView checkReceipt(ReceiptCheckRequest request) {
        RequestActor actor = actorProvider.current();
        if (!officeAccessService.canAccessBusiness(actor, "MATTER", request.matterId())) {
            throw new BusinessException(
                    "EXPENSE_MATTER_INVALID",
                    "关联案件无效或无权访问",
                    HttpStatus.BAD_REQUEST
            );
        }
        boolean duplicate = Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1
                          FROM expense_items item
                          JOIN expense_claims claim ON claim.id = item.expense_claim_id
                          WHERE claim.organization_id = :organizationId
                            AND claim.status NOT IN ('REJECTED', 'CANCELLED')
                            AND item.receipt_sha256 = :sha256
                        )
                        """)
                .param("organizationId", actor.organizationId())
                .param("sha256", request.sha256().toLowerCase(java.util.Locale.ROOT))
                .query(Boolean.class).single());
        return new ReceiptCheckView(duplicate);
    }

    @Transactional
    public ExpenseView submit(UUID id, String idempotencyKey) {
        RequestActor actor = actorProvider.current();
        ExpenseView claim = findVisible(id);
        if (!claim.applicantUserId().equals(actor.userId()) || !"DRAFT".equals(claim.status())) {
            throw new BusinessException(
                    "EXPENSE_SUBMIT_INVALID", "只有申请人可以提交草稿", HttpStatus.CONFLICT
            );
        }
        workflowService.start(
                new StartWorkflowRequest(
                        "EXPENSE_CLAIM", id,
                        Map.of("totalAmount", claim.totalAmount())
                ),
                idempotencyKey
        );
        auditService.success(actor, "EXPENSE_SUBMIT", "EXPENSE_CLAIM", id);
        return findVisible(id);
    }

    @Transactional
    public ExpenseView markPaid(UUID id) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "EXPENSE_PAY");
        findVisible(id);
        int updated = jdbcClient.sql("""
                        UPDATE expense_claims
                        SET status = 'PAID', paid_at = now(), paid_by = :paidBy,
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND status = 'APPROVED'
                        """)
                .param("paidBy", actor.userId())
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "EXPENSE_PAY_INVALID", "只有已审批的报销单可以登记付款", HttpStatus.CONFLICT
            );
        }
        ExpenseView claim = findVisible(id);
        outboxService.enqueueNotification(
                actor.organizationId(), claim.applicantUserId(), "EXPENSE_PAID",
                "报销已付款", claim.claimNumber() + " 已完成付款登记",
                "EXPENSE_CLAIM", id, "/expenses?id=" + id,
                "NORMAL", "expense-paid:" + id
        );
        auditService.success(actor, "EXPENSE_PAID", "EXPENSE_CLAIM", id);
        return claim;
    }

    private List<ExpenseItemView> items(UUID claimId) {
        return jdbcClient.sql("""
                        SELECT item.id, item.category, item.occurred_on,
                               item.description, item.amount, item.receipt_document_id,
                               item.receipt_document_version_id,
                               version.original_filename AS receipt_filename
                        FROM expense_items item
                        LEFT JOIN document_versions version
                          ON version.id = item.receipt_document_version_id
                        WHERE item.expense_claim_id = :claimId
                        ORDER BY item.occurred_on, item.created_at
                        """)
                .param("claimId", claimId)
                .query((rs, rowNum) -> new ExpenseItemView(
                        rs.getObject("id", UUID.class),
                        rs.getString("category"),
                        rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("description"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("receipt_document_id", UUID.class),
                        rs.getObject("receipt_document_version_id", UUID.class),
                        rs.getString("receipt_filename")
                )).list();
    }

    private Map<UUID, ReceiptContext> validateReceipts(
            RequestActor actor,
            CreateExpenseRequest request
    ) {
        Map<UUID, ReceiptContext> receipts = new HashMap<>();
        Set<String> requestHashes = new HashSet<>();
        for (ExpenseItemRequest item : request.items()) {
            UUID documentId = item.receiptDocumentId();
            if (documentId == null) {
                continue;
            }
            documentAccessService.requireDocumentRead(actor, documentId, false);
            ReceiptContext receipt = jdbcClient.sql("""
                            SELECT document.matter_id, version.id AS version_id,
                                   version.original_filename, version.sha256,
                                   version.ingestion_status
                            FROM documents document
                            JOIN document_versions version
                              ON version.id = document.current_version_id
                            WHERE document.id = :documentId
                              AND document.organization_id = :organizationId
                              AND document.deleted_at IS NULL
                            """)
                    .param("documentId", documentId)
                    .param("organizationId", actor.organizationId())
                    .query((rs, rowNum) -> new ReceiptContext(
                            rs.getObject("matter_id", UUID.class),
                            rs.getObject("version_id", UUID.class),
                            rs.getString("original_filename"),
                            rs.getString("sha256").trim(),
                            rs.getString("ingestion_status")
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            "EXPENSE_RECEIPT_INVALID",
                            "报销票据不存在或不可用",
                            HttpStatus.BAD_REQUEST
                    ));
            if (request.matterId() == null
                    || !request.matterId().equals(receipt.matterId())
                    || !"AVAILABLE".equals(receipt.ingestionStatus())) {
                throw new BusinessException(
                        "EXPENSE_RECEIPT_CONTEXT_INVALID",
                        "报销票据必须是本案件已通过安全扫描的文件",
                        HttpStatus.BAD_REQUEST
                );
            }
            jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtext(:sha256))")
                    .param("sha256", receipt.sha256())
                    .query((rs, rowNum) -> Boolean.TRUE)
                    .single();
            boolean duplicate = !requestHashes.add(receipt.sha256())
                    || Boolean.TRUE.equals(jdbcClient.sql("""
                                    SELECT EXISTS (
                                      SELECT 1
                                      FROM expense_items item
                                      JOIN expense_claims claim
                                        ON claim.id = item.expense_claim_id
                                      WHERE claim.organization_id = :organizationId
                                        AND claim.status NOT IN ('REJECTED', 'CANCELLED')
                                        AND item.receipt_sha256 = :sha256
                                    )
                                    """)
                            .param("organizationId", actor.organizationId())
                            .param("sha256", receipt.sha256())
                            .query(Boolean.class).single());
            if (duplicate) {
                throw new BusinessException(
                        "EXPENSE_RECEIPT_DUPLICATE",
                        "该票据已用于其他有效报销单",
                        HttpStatus.CONFLICT
                );
            }
            receipts.put(documentId, receipt);
        }
        return receipts;
    }

    private ExpenseView findVisible(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "EXPENSE_NOT_FOUND", "报销申请不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private record ExpenseBase(
            UUID id,
            String claimNumber,
            UUID applicantUserId,
            String applicantName,
            String title,
            String purpose,
            UUID matterId,
            String matterNumber,
            BigDecimal totalAmount,
            String currency,
            String status,
            java.time.Instant paidAt,
            java.time.Instant createdAt,
            UUID officeId,
            String officeNameZh,
            String officeNameEn,
            String reviewStage,
            String reviewTaskName,
            String reviewGroup
    ) {}

    private record ExpenseOffice(UUID officeId, String currency) {}
    private record ReceiptContext(
            UUID matterId,
            UUID versionId,
            String filename,
            String sha256,
            String ingestionStatus
    ) {}
}
