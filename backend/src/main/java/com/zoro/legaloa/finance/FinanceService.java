package com.zoro.legaloa.finance;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.common.PagePolicy;
import com.zoro.legaloa.common.PageResponse;
import com.zoro.legaloa.finance.FinanceController.AgingBucket;
import com.zoro.legaloa.finance.FinanceController.CollectionRequest;
import com.zoro.legaloa.finance.FinanceController.CollectionView;
import com.zoro.legaloa.finance.FinanceController.DraftInvoiceRequest;
import com.zoro.legaloa.finance.FinanceController.EngagementRequest;
import com.zoro.legaloa.finance.FinanceController.EngagementView;
import com.zoro.legaloa.finance.FinanceController.FinanceReport;
import com.zoro.legaloa.finance.FinanceController.InvoiceLineView;
import com.zoro.legaloa.finance.FinanceController.InvoiceView;
import com.zoro.legaloa.finance.FinanceController.PaymentRequest;
import com.zoro.legaloa.finance.FinanceController.PaymentView;
import com.zoro.legaloa.finance.FinanceController.TimeEntryRequest;
import com.zoro.legaloa.finance.FinanceController.TimeEntryView;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
    private static final Set<String> FEE_TYPES =
            Set.of("HOURLY", "FIXED", "RETAINER", "CONTINGENCY", "HYBRID");
    private static final Set<String> PAYMENT_METHODS =
            Set.of("BANK_TRANSFER", "CHEQUE", "CASH", "CARD", "OTHER");
    private static final Set<String> COLLECTION_TYPES =
            Set.of("CALL", "EMAIL", "LETTER", "MEETING", "PROMISE_TO_PAY", "DISPUTE");

    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;
    private final AuditService auditService;

    public FinanceService(
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
    public List<EngagementView> engagements() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "FINANCE_VIEW");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT e.id, e.office_id, e.matter_id, m.matter_number,
                               e.engagement_number, e.title, e.currency, e.status,
                               f.fee_type, f.rate_amount, f.cap_amount, f.tax_rate,
                               e.effective_from, e.effective_to, e.approved_at, e.updated_at
                        FROM engagements e
                        JOIN matters m ON m.id = e.matter_id
                        LEFT JOIN LATERAL (
                          SELECT * FROM fee_terms ft
                          WHERE ft.engagement_id = e.id
                          ORDER BY ft.effective_from DESC LIMIT 1
                        ) f ON TRUE
                        WHERE e.organization_id = :organizationId
                          AND (:globalAccess OR e.office_id IN (:officeIds))
                        ORDER BY e.updated_at DESC
                        LIMIT 1000
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(FinanceService::mapEngagement).list();
    }

    @Transactional
    public EngagementView createEngagement(EngagementRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "FINANCE_MANAGE");
        String feeType = code(request.feeType());
        if (!FEE_TYPES.contains(feeType)) {
            throw invalid("FEE_TYPE_INVALID");
        }
        MatterBilling matter = jdbcClient.sql("""
                        SELECT id, office_id, billing_currency, matter_number
                        FROM matters
                        WHERE id = :id AND organization_id = :organizationId
                          AND deleted_at IS NULL
                        """)
                .param("id", request.matterId())
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new MatterBilling(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("billing_currency"),
                        rs.getString("matter_number")
                ))
                .optional().orElseThrow(() -> new BusinessException(
                        "MATTER_NOT_FOUND", "案件不存在", HttpStatus.NOT_FOUND
                ));
        UUID officeId = request.officeId() == null ? matter.officeId() : request.officeId();
        officeAccessService.resolveManagedOffice(actor, officeId);
        if (!officeId.equals(matter.officeId())) {
            throw new BusinessException(
                    "ENGAGEMENT_OFFICE_MISMATCH", "委托办公室必须与案件办公室一致",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (request.effectiveTo() != null
                && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw invalid("ENGAGEMENT_DATE_INVALID");
        }
        if (request.clientId() != null) {
            boolean clientExists = Boolean.TRUE.equals(jdbcClient.sql("""
                            SELECT EXISTS (
                              SELECT 1 FROM clients c
                              JOIN parties p ON p.id = c.party_id
                              WHERE c.id = :clientId
                                AND p.organization_id = :organizationId
                                AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
                            )
                            """)
                    .param("clientId", request.clientId())
                    .param("organizationId", actor.organizationId())
                    .query(Boolean.class).single());
            if (!clientExists) {
                throw new BusinessException(
                        "ENGAGEMENT_CLIENT_INVALID",
                        "委托客户不存在或已停用",
                        HttpStatus.BAD_REQUEST
                );
            }
        }
        String number = "ENG-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + shortId();
        UUID id = jdbcClient.sql("""
                        INSERT INTO engagements
                            (organization_id, office_id, matter_id, client_id,
                             engagement_number, title, currency, effective_from,
                             effective_to, created_by)
                        VALUES
                            (:organizationId, :officeId, :matterId, :clientId,
                             :number, :title, :currency, :effectiveFrom,
                             :effectiveTo, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("matterId", matter.id())
                .param("clientId", request.clientId())
                .param("number", number)
                .param("title", request.title().trim())
                .param("currency", matter.currency())
                .param("effectiveFrom", request.effectiveFrom())
                .param("effectiveTo", request.effectiveTo())
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        jdbcClient.sql("""
                        INSERT INTO fee_terms
                            (engagement_id, fee_type, rate_amount, cap_amount,
                             tax_rate, effective_from, effective_to)
                        VALUES
                            (:engagementId, :feeType, :rateAmount, :capAmount,
                             :taxRate, :effectiveFrom, :effectiveTo)
                        """)
                .param("engagementId", id)
                .param("feeType", feeType)
                .param("rateAmount", request.rateAmount())
                .param("capAmount", request.capAmount())
                .param("taxRate", request.taxRate())
                .param("effectiveFrom", request.effectiveFrom())
                .param("effectiveTo", request.effectiveTo())
                .update();
        auditService.success(actor, "ENGAGEMENT_CREATE", "ENGAGEMENT", id);
        return engagement(id, actor.organizationId());
    }

    @Transactional
    public EngagementView approveEngagement(UUID id) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "FINANCE_MANAGE");
        EngagementView current = engagement(id, actor.organizationId());
        officeAccessService.resolveManagedOffice(actor, current.officeId());
        int updated = jdbcClient.sql("""
                        UPDATE engagements
                        SET status = 'APPROVED', approved_by = :approvedBy,
                            approved_at = now(), updated_at = now()
                        WHERE id = :id AND organization_id = :organizationId
                          AND status IN ('DRAFT', 'PENDING_APPROVAL')
                          AND NOT EXISTS (
                            SELECT 1 FROM engagements other
                            WHERE other.matter_id = engagements.matter_id
                              AND other.status = 'APPROVED' AND other.id <> engagements.id
                          )
                        """)
                .param("approvedBy", actor.userId())
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "ENGAGEMENT_APPROVAL_INVALID",
                    "委托不可审批，或案件已有生效委托",
                    HttpStatus.CONFLICT
            );
        }
        auditService.success(actor, "ENGAGEMENT_APPROVE", "ENGAGEMENT", id);
        return engagement(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public PageResponse<TimeEntryView> timeEntries(
            UUID matterId, Integer page, Integer size
    ) {
        RequestActor actor = actorProvider.current();
        var spec = PagePolicy.bounded(page, size, "workDateDesc",
                Map.of("workDateDesc", "t.work_date DESC"), "workDateDesc");
        boolean viewAll = authorizationService.hasPermission(actor, "FINANCE_VIEW");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        long total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM time_entries t
                        WHERE t.organization_id = :organizationId
                          AND (:allMatters OR t.matter_id = :matterId)
                          AND (t.professional_user_id = :userId
                               OR (:viewAll AND (:globalAccess OR t.office_id IN (:officeIds))))
                        """)
                .param("organizationId", actor.organizationId())
                .param("allMatters", matterId == null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(Long.class).single();
        List<TimeEntryView> items = jdbcClient.sql("""
                        SELECT t.id, t.office_id, t.matter_id, m.matter_number,
                               t.professional_user_id, u.display_name, t.work_date,
                               t.minutes, t.description, t.billable, t.currency,
                               t.rate_snapshot, t.amount, t.status, t.approved_at, t.updated_at
                        FROM time_entries t
                        JOIN matters m ON m.id = t.matter_id
                        JOIN users u ON u.id = t.professional_user_id
                        WHERE t.organization_id = :organizationId
                          AND (:allMatters OR t.matter_id = :matterId)
                          AND (t.professional_user_id = :userId
                               OR (:viewAll AND (:globalAccess OR t.office_id IN (:officeIds))))
                        ORDER BY t.work_date DESC, t.updated_at DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("organizationId", actor.organizationId())
                .param("allMatters", matterId == null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .param("size", spec.size()).param("offset", spec.offset())
                .query(FinanceService::mapTimeEntry).list();
        return PageResponse.of(items, spec.page(), spec.size(), total);
    }

    @Transactional
    public TimeEntryView createTimeEntry(TimeEntryRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "TIME_ENTRY_CREATE");
        if (!officeAccessService.canAccessBusiness(actor, "MATTER", request.matterId())) {
            throw new BusinessException(
                    "MATTER_ACCESS_DENIED", "无权向该案件登记工时", HttpStatus.FORBIDDEN
            );
        }
        EngagementRate rate = jdbcClient.sql("""
                        SELECT e.id, e.office_id, e.currency, ft.rate_amount
                        FROM engagements e
                        JOIN fee_terms ft ON ft.engagement_id = e.id
                        WHERE e.matter_id = :matterId
                          AND e.organization_id = :organizationId
                          AND e.status = 'APPROVED'
                          AND :workDate >= ft.effective_from
                          AND (ft.effective_to IS NULL OR :workDate <= ft.effective_to)
                        ORDER BY ft.effective_from DESC LIMIT 1
                        """)
                .param("matterId", request.matterId())
                .param("organizationId", actor.organizationId())
                .param("workDate", request.workDate())
                .query((rs, rowNum) -> new EngagementRate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("currency"),
                        rs.getBigDecimal("rate_amount")
                )).optional().orElseThrow(() -> new BusinessException(
                        "ENGAGEMENT_REQUIRED", "案件尚无已审批且在有效期内的收费约定",
                        HttpStatus.CONFLICT
                ));
        BigDecimal hourlyRate = rate.rate() == null ? BigDecimal.ZERO : rate.rate();
        BigDecimal amount = FinancePolicy.timeAmount(
                hourlyRate, request.minutes(), request.billable()
        );
        UUID id = jdbcClient.sql("""
                        INSERT INTO time_entries
                            (organization_id, office_id, matter_id, engagement_id,
                             professional_user_id, work_date, minutes, description,
                             billable, currency, rate_snapshot, amount)
                        VALUES
                            (:organizationId, :officeId, :matterId, :engagementId,
                             :userId, :workDate, :minutes, :description,
                             :billable, :currency, :rate, :amount)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", rate.officeId())
                .param("matterId", request.matterId())
                .param("engagementId", rate.engagementId())
                .param("userId", actor.userId())
                .param("workDate", request.workDate())
                .param("minutes", request.minutes())
                .param("description", request.description().trim())
                .param("billable", request.billable())
                .param("currency", rate.currency())
                .param("rate", hourlyRate)
                .param("amount", amount)
                .query(UUID.class).single();
        auditService.success(actor, "TIME_ENTRY_CREATE", "TIME_ENTRY", id);
        return timeEntry(id, actor.organizationId());
    }

    @Transactional
    public TimeEntryView transitionTimeEntry(UUID id, String target) {
        RequestActor actor = actorProvider.current();
        String source;
        if ("SUBMITTED".equals(target)) {
            source = "DRAFT";
        } else if ("APPROVED".equals(target)) {
            source = "SUBMITTED";
            authorizationService.requirePermission(actor, "TIME_ENTRY_APPROVE");
            TimeEntryApproval approval = jdbcClient.sql("""
                            SELECT office_id, professional_user_id
                            FROM time_entries
                            WHERE id = :id AND organization_id = :organizationId
                            """)
                    .param("id", id)
                    .param("organizationId", actor.organizationId())
                    .query((rs, rowNum) -> new TimeEntryApproval(
                            rs.getObject("office_id", UUID.class),
                            rs.getObject("professional_user_id", UUID.class)
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            "TIME_ENTRY_NOT_FOUND", "工时记录不存在", HttpStatus.NOT_FOUND
                    ));
            officeAccessService.resolveManagedOffice(actor, approval.officeId());
            if (!FinancePolicy.isIndependentApprover(
                    actor.userId(), approval.professionalUserId()
            )) {
                throw new BusinessException(
                        "TIME_ENTRY_SELF_APPROVAL_FORBIDDEN",
                        "工时登记人不能审批自己的工时",
                        HttpStatus.FORBIDDEN
                );
            }
        } else {
            throw invalid("TIME_ENTRY_TRANSITION_INVALID");
        }
        String sql = "APPROVED".equals(target)
                ? """
                  UPDATE time_entries SET status = :target, approved_by = :actorId,
                      approved_at = now(), updated_at = now()
                  WHERE id = :id AND organization_id = :organizationId AND status = :source
                  """
                : """
                  UPDATE time_entries SET status = :target, updated_at = now()
                  WHERE id = :id AND organization_id = :organizationId AND status = :source
                    AND professional_user_id = :actorId
                  """;
        int updated = jdbcClient.sql(sql)
                .param("target", target).param("actorId", actor.userId())
                .param("id", id).param("organizationId", actor.organizationId())
                .param("source", source).update();
        if (updated == 0) {
            throw new BusinessException(
                    "TIME_ENTRY_TRANSITION_INVALID", "工时状态不允许该操作",
                    HttpStatus.CONFLICT
            );
        }
        auditService.success(actor, "TIME_ENTRY_" + target, "TIME_ENTRY", id);
        return timeEntry(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceView> invoices(Integer page, Integer size) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "FINANCE_VIEW");
        var spec = PagePolicy.bounded(page, size, "createdAtDesc",
                Map.of("createdAtDesc", "i.created_at DESC"), "createdAtDesc");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        long total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM invoices
                        WHERE organization_id = :organizationId
                          AND (:globalAccess OR office_id IN (:officeIds))
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(Long.class).single();
        List<InvoiceView> items = jdbcClient.sql("""
                        SELECT i.id FROM invoices i
                        WHERE i.organization_id = :organizationId
                          AND (:globalAccess OR i.office_id IN (:officeIds))
                        ORDER BY i.created_at DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .param("size", spec.size()).param("offset", spec.offset())
                .query(UUID.class).list().stream()
                .map(id -> invoice(id, actor.organizationId())).toList();
        return PageResponse.of(items, spec.page(), spec.size(), total);
    }

    @Transactional
    public InvoiceView draftInvoice(DraftInvoiceRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "INVOICE_MANAGE");
        EngagementInvoiceContext engagement = jdbcClient.sql("""
                        SELECT e.id, e.office_id, e.matter_id, m.matter_number,
                               e.currency, ft.tax_rate, ft.fee_type
                        FROM engagements e
                        JOIN matters m ON m.id = e.matter_id
                        JOIN LATERAL (
                          SELECT * FROM fee_terms x WHERE x.engagement_id = e.id
                          ORDER BY x.effective_from DESC LIMIT 1
                        ) ft ON TRUE
                        WHERE e.id = :id AND e.organization_id = :organizationId
                          AND e.status = 'APPROVED'
                        """)
                .param("id", request.engagementId())
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new EngagementInvoiceContext(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getString("currency"),
                        rs.getBigDecimal("tax_rate"),
                        rs.getString("fee_type")
                )).optional().orElseThrow(() -> new BusinessException(
                        "ENGAGEMENT_NOT_BILLABLE", "委托不存在或尚未审批", HttpStatus.CONFLICT
                ));
        officeAccessService.resolveManagedOffice(actor, engagement.officeId());
        List<BillableTime> time = jdbcClient.sql("""
                        SELECT id, work_date, minutes, description, rate_snapshot,
                               amount, professional_user_id
                        FROM time_entries
                        WHERE engagement_id = :engagementId AND status = 'APPROVED'
                          AND billable = TRUE AND invoice_line_id IS NULL
                        ORDER BY work_date, created_at
                        FOR UPDATE
                        LIMIT 5000
                        """)
                .param("engagementId", engagement.id())
                .query((rs, rowNum) -> new BillableTime(
                        rs.getObject("id", UUID.class),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getInt("minutes"),
                        rs.getString("description"),
                        rs.getBigDecimal("rate_snapshot"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("professional_user_id", UUID.class)
                )).list();
        BigDecimal fixed = request.fixedFeeAmount() == null
                ? BigDecimal.ZERO : request.fixedFeeAmount();
        if (time.isEmpty() && fixed.signum() == 0) {
            throw new BusinessException(
                    "INVOICE_EMPTY", "没有可开票的已审批工时或固定费用",
                    HttpStatus.CONFLICT
            );
        }
        UUID invoiceId = jdbcClient.sql("""
                        INSERT INTO invoices
                            (organization_id, office_id, engagement_id, matter_id,
                             currency, due_date, created_by)
                        VALUES
                            (:organizationId, :officeId, :engagementId, :matterId,
                             :currency, :dueDate, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", engagement.officeId())
                .param("engagementId", engagement.id())
                .param("matterId", engagement.matterId())
                .param("currency", engagement.currency())
                .param("dueDate", request.dueDate())
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        int lineNumber = 1;
        BigDecimal subtotal = BigDecimal.ZERO;
        for (BillableTime row : time) {
            UUID lineId = jdbcClient.sql("""
                            INSERT INTO invoice_lines
                                (invoice_id, line_number, line_type, description,
                                 quantity, unit_price, amount, source_time_entry_id,
                                 professional_user_id, work_date, rate_snapshot)
                            VALUES
                                (:invoiceId, :lineNumber, 'TIME', :description,
                                 :quantity, :unitPrice, :amount, :timeEntryId,
                                 :professionalId, :workDate, :rate)
                            RETURNING id
                            """)
                    .param("invoiceId", invoiceId)
                    .param("lineNumber", lineNumber++)
                    .param("description", row.description())
                    .param("quantity", BigDecimal.valueOf(row.minutes())
                            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP))
                    .param("unitPrice", row.rate())
                    .param("amount", row.amount())
                    .param("timeEntryId", row.id())
                    .param("professionalId", row.professionalId())
                    .param("workDate", row.workDate())
                    .param("rate", row.rate())
                    .query(UUID.class).single();
            jdbcClient.sql("""
                            UPDATE time_entries
                            SET status = 'INVOICED', invoice_line_id = :lineId, updated_at = now()
                            WHERE id = :id
                            """)
                    .param("lineId", lineId).param("id", row.id()).update();
            subtotal = subtotal.add(row.amount());
        }
        if (fixed.signum() > 0) {
            jdbcClient.sql("""
                            INSERT INTO invoice_lines
                                (invoice_id, line_number, line_type, description,
                                 quantity, unit_price, amount)
                            VALUES
                                (:invoiceId, :lineNumber, 'FIXED_FEE', :description,
                                 1, :amount, :amount)
                            """)
                    .param("invoiceId", invoiceId)
                    .param("lineNumber", lineNumber)
                    .param("description", request.fixedFeeDescription() == null
                            ? "Fixed fee" : request.fixedFeeDescription().trim())
                    .param("amount", fixed).update();
            subtotal = subtotal.add(fixed);
        }
        BigDecimal tax = FinancePolicy.tax(subtotal, engagement.taxRate());
        jdbcClient.sql("""
                        UPDATE invoices
                        SET subtotal = :subtotal, tax_amount = :tax,
                            total_amount = :total, updated_at = now()
                        WHERE id = :id
                        """)
                .param("subtotal", subtotal)
                .param("tax", tax)
                .param("total", subtotal.add(tax))
                .param("id", invoiceId).update();
        auditService.record(
                actor, "INVOICE_DRAFT", "INVOICE", invoiceId,
                "SUCCESS", null, Map.of("lineCount", lineNumber - 1)
        );
        return invoice(invoiceId, actor.organizationId());
    }

    @Transactional
    public InvoiceView transitionInvoice(UUID id, String target, String reason) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "INVOICE_MANAGE");
        InvoiceView current = invoice(id, actor.organizationId());
        officeAccessService.resolveManagedOffice(actor, current.officeId());
        String source;
        switch (target) {
            case "UNDER_REVIEW" -> source = "DRAFT";
            case "ISSUED" -> source = "UNDER_REVIEW";
            case "CANCELLED" -> source = "ISSUED";
            default -> throw invalid("INVOICE_TRANSITION_INVALID");
        }
        String number = "ISSUED".equals(target)
                ? "INV-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "-" + shortId()
                : null;
        int updated = jdbcClient.sql("""
                        UPDATE invoices
                        SET status = :target,
                            invoice_number = COALESCE(:number, invoice_number),
                            issued_at = CASE WHEN :target = 'ISSUED' THEN now() ELSE issued_at END,
                            cancelled_at = CASE WHEN :target = 'CANCELLED' THEN now() ELSE cancelled_at END,
                            cancellation_reason = CASE WHEN :target = 'CANCELLED' THEN :reason
                                                       ELSE cancellation_reason END,
                            updated_at = now()
                        WHERE id = :id AND organization_id = :organizationId
                          AND status = :source
                          AND (:target <> 'CANCELLED' OR paid_amount = 0)
                        """)
                .param("target", target)
                .param("number", number)
                .param("reason", reason)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("source", source)
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "INVOICE_TRANSITION_INVALID", "账单状态不允许该操作",
                    HttpStatus.CONFLICT
            );
        }
        if ("ISSUED".equals(target)) {
            jdbcClient.sql("""
                            UPDATE invoice_lines
                            SET immutable_snapshot = jsonb_build_object(
                              'description', description, 'quantity', quantity,
                              'unitPrice', unit_price, 'amount', amount,
                              'professionalUserId', professional_user_id,
                              'workDate', work_date, 'rateSnapshot', rate_snapshot
                            )
                            WHERE invoice_id = :invoiceId
                            """)
                    .param("invoiceId", id).update();
        }
        auditService.record(
                actor, "INVOICE_" + target, "INVOICE", id,
                "SUCCESS", null, reason == null ? Map.of() : Map.of("reason", reason)
        );
        return invoice(id, actor.organizationId());
    }

    @Transactional
    public PaymentView recordPayment(PaymentRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "PAYMENT_MANAGE");
        UUID officeId = officeAccessService.resolveManagedOffice(actor, request.officeId());
        String method = code(request.method());
        if (!PAYMENT_METHODS.contains(method)) {
            throw invalid("PAYMENT_METHOD_INVALID");
        }
        BigDecimal allocated = request.allocations().stream()
                .map(FinanceController.AllocationRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(request.amount()) > 0) {
            throw new BusinessException(
                    "PAYMENT_OVER_ALLOCATED", "分配金额不得超过收款金额",
                    HttpStatus.BAD_REQUEST
            );
        }
        String currency = request.currency().toUpperCase(java.util.Locale.ROOT);
        UUID paymentId = jdbcClient.sql("""
                        INSERT INTO payments
                            (organization_id, office_id, payment_reference, received_on,
                             payer_name, currency, amount, unallocated_amount,
                             method, bank_reference, recorded_by)
                        VALUES
                            (:organizationId, :officeId, :reference, :receivedOn,
                             :payerName, :currency, :amount, :unallocated,
                             :method, :bankReference, :recordedBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("reference", request.paymentReference().trim())
                .param("receivedOn", request.receivedOn())
                .param("payerName", request.payerName().trim())
                .param("currency", currency)
                .param("amount", request.amount())
                .param("unallocated", request.amount().subtract(allocated))
                .param("method", method)
                .param("bankReference", request.bankReference())
                .param("recordedBy", actor.userId())
                .query(UUID.class).single();
        for (var allocation : request.allocations()) {
            InvoiceBalance balance = jdbcClient.sql("""
                            SELECT total_amount, paid_amount, currency, office_id
                            FROM invoices
                            WHERE id = :id AND organization_id = :organizationId
                              AND status IN ('ISSUED', 'PARTIALLY_PAID')
                            FOR UPDATE
                            """)
                    .param("id", allocation.invoiceId())
                    .param("organizationId", actor.organizationId())
                    .query((rs, rowNum) -> new InvoiceBalance(
                            rs.getBigDecimal("total_amount"),
                            rs.getBigDecimal("paid_amount"),
                            rs.getString("currency"),
                            rs.getObject("office_id", UUID.class)
                    )).optional().orElseThrow(() -> new BusinessException(
                            "INVOICE_NOT_PAYABLE", "账单不可收款", HttpStatus.CONFLICT
                    ));
            if (!officeId.equals(balance.officeId()) || !currency.equals(balance.currency())
                    || !FinancePolicy.allocationFits(
                            allocation.amount(),
                            request.amount(),
                            balance.total().subtract(balance.paid())
                    )) {
                throw new BusinessException(
                        "PAYMENT_ALLOCATION_INVALID",
                        "回款办公室、币种或金额与账单不匹配",
                        HttpStatus.BAD_REQUEST
                );
            }
            jdbcClient.sql("""
                            INSERT INTO payment_allocations
                                (payment_id, invoice_id, amount, allocated_by)
                            VALUES (:paymentId, :invoiceId, :amount, :allocatedBy)
                            """)
                    .param("paymentId", paymentId)
                    .param("invoiceId", allocation.invoiceId())
                    .param("amount", allocation.amount())
                    .param("allocatedBy", actor.userId()).update();
            jdbcClient.sql("""
                            UPDATE invoices
                            SET paid_amount = paid_amount + :amount,
                                status = CASE
                                  WHEN paid_amount + :amount >= total_amount THEN 'PAID'
                                  ELSE 'PARTIALLY_PAID'
                                END,
                                updated_at = now()
                            WHERE id = :id
                            """)
                    .param("amount", allocation.amount())
                    .param("id", allocation.invoiceId()).update();
        }
        auditService.record(
                actor, "PAYMENT_RECORD", "PAYMENT", paymentId,
                "SUCCESS", null, Map.of("allocatedAmount", allocated)
        );
        return payment(paymentId, actor.organizationId());
    }

    @Transactional
    public CollectionView recordCollection(UUID invoiceId, CollectionRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "FINANCE_MANAGE");
        String type = code(request.activityType());
        if (!COLLECTION_TYPES.contains(type)) {
            throw invalid("COLLECTION_TYPE_INVALID");
        }
        UUID ownerId = request.ownerUserId() == null ? actor.userId() : request.ownerUserId();
        UUID officeId = jdbcClient.sql("""
                        SELECT i.office_id
                        FROM invoices i
                        JOIN users u ON u.id = :ownerId
                        WHERE i.id = :invoiceId AND i.organization_id = :organizationId
                          AND u.organization_id = i.organization_id
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                        """)
                .param("ownerId", ownerId)
                .param("invoiceId", invoiceId)
                .param("organizationId", actor.organizationId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "COLLECTION_TARGET_INVALID", "账单或跟进负责人无效",
                        HttpStatus.BAD_REQUEST
                ));
        officeAccessService.resolveManagedOffice(actor, officeId);
        UUID id = jdbcClient.sql("""
                        INSERT INTO collection_activities
                            (organization_id, invoice_id, activity_type, notes,
                             next_action_at, owner_user_id, created_by)
                        VALUES
                            (:organizationId, :invoiceId, :type, :notes,
                             :nextActionAt, :ownerId, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("invoiceId", invoiceId)
                .param("type", type)
                .param("notes", request.notes().trim())
                .param("nextActionAt", request.nextActionAt() == null
                        ? null : java.sql.Timestamp.from(request.nextActionAt()))
                .param("ownerId", ownerId)
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        auditService.success(actor, "COLLECTION_ACTIVITY_CREATE", "COLLECTION_ACTIVITY", id);
        return jdbcClient.sql("""
                        SELECT id, invoice_id, activity_type, occurred_at, notes,
                               next_action_at, owner_user_id, created_by
                        FROM collection_activities WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new CollectionView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("invoice_id", UUID.class),
                        rs.getString("activity_type"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("notes"),
                        rs.getTimestamp("next_action_at") == null ? null
                                : rs.getTimestamp("next_action_at").toInstant(),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("created_by", UUID.class)
                )).single();
    }

    @Transactional(readOnly = true)
    public FinanceReport report(UUID requestedOfficeId) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "FINANCE_VIEW");
        UUID officeId = requestedOfficeId == null
                ? officeAccessService.scope(actor).primaryOfficeId()
                : officeAccessService.resolveAccessibleOffice(actor, requestedOfficeId);
        if (officeId == null && !officeAccessService.scope(actor).globalAccess()) {
            throw invalid("REPORT_OFFICE_REQUIRED");
        }
        boolean allOffices = officeId == null;
        ReportTotals totals = jdbcClient.sql("""
                        SELECT
                          COALESCE((SELECT SUM(amount) FROM time_entries
                            WHERE organization_id = :organizationId
                              AND (:allOffices OR office_id = :officeId)
                              AND status = 'APPROVED'), 0) AS approved_wip,
                          COALESCE((SELECT SUM(amount) FROM time_entries
                            WHERE organization_id = :organizationId
                              AND (:allOffices OR office_id = :officeId)
                              AND status = 'APPROVED' AND invoice_line_id IS NULL), 0) AS unbilled,
                          COALESCE((SELECT SUM(total_amount) FROM invoices
                            WHERE organization_id = :organizationId
                              AND (:allOffices OR office_id = :officeId)
                              AND status IN ('ISSUED','PARTIALLY_PAID','PAID')), 0) AS revenue,
                          COALESCE((SELECT SUM(total_amount - paid_amount) FROM invoices
                            WHERE organization_id = :organizationId
                              AND (:allOffices OR office_id = :officeId)
                              AND status IN ('ISSUED','PARTIALLY_PAID')), 0) AS receivables,
                          COALESCE((SELECT SUM(amount - unallocated_amount) FROM payments
                            WHERE organization_id = :organizationId
                              AND (:allOffices OR office_id = :officeId)
                              AND status = 'POSTED'), 0) AS collected,
                          COALESCE((SELECT
                            100.0 * SUM(CASE WHEN billable THEN minutes ELSE 0 END)
                            / NULLIF(SUM(minutes), 0)
                            FROM time_entries
                            WHERE organization_id = :organizationId
                              AND (:allOffices OR office_id = :officeId)
                              AND work_date >= current_date - 30), 0) AS utilization
                        """)
                .param("organizationId", actor.organizationId())
                .param("allOffices", allOffices)
                .param("officeId", officeId == null ? new UUID(0, 0) : officeId)
                .query((rs, rowNum) -> new ReportTotals(
                        rs.getBigDecimal("approved_wip"),
                        rs.getBigDecimal("unbilled"),
                        rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("receivables"),
                        rs.getBigDecimal("collected"),
                        rs.getBigDecimal("utilization")
                )).single();
        List<AgingBucket> aging = jdbcClient.sql("""
                        SELECT bucket, COUNT(*) AS invoice_count,
                               COALESCE(SUM(outstanding), 0) AS outstanding
                        FROM (
                          SELECT CASE
                              WHEN due_date IS NULL OR due_date >= current_date THEN 'CURRENT'
                              WHEN current_date - due_date <= 30 THEN '1_30'
                              WHEN current_date - due_date <= 60 THEN '31_60'
                              WHEN current_date - due_date <= 90 THEN '61_90'
                              ELSE 'OVER_90'
                            END AS bucket,
                            total_amount - paid_amount AS outstanding
                          FROM invoices
                          WHERE organization_id = :organizationId
                            AND (:allOffices OR office_id = :officeId)
                            AND status IN ('ISSUED','PARTIALLY_PAID')
                        ) x
                        GROUP BY bucket
                        ORDER BY bucket
                        """)
                .param("organizationId", actor.organizationId())
                .param("allOffices", allOffices)
                .param("officeId", officeId == null ? new UUID(0, 0) : officeId)
                .query((rs, rowNum) -> new AgingBucket(
                        rs.getString("bucket"),
                        rs.getLong("invoice_count"),
                        rs.getBigDecimal("outstanding")
                )).list();
        return new FinanceReport(
                officeId, totals.approvedWip(), totals.unbilled(), totals.revenue(),
                totals.receivables(), totals.collected(),
                totals.utilization().setScale(2, RoundingMode.HALF_UP),
                aging, Instant.now()
        );
    }

    private EngagementView engagement(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT e.id, e.office_id, e.matter_id, m.matter_number,
                               e.engagement_number, e.title, e.currency, e.status,
                               f.fee_type, f.rate_amount, f.cap_amount, f.tax_rate,
                               e.effective_from, e.effective_to, e.approved_at, e.updated_at
                        FROM engagements e JOIN matters m ON m.id = e.matter_id
                        LEFT JOIN LATERAL (
                          SELECT * FROM fee_terms ft WHERE ft.engagement_id = e.id
                          ORDER BY ft.effective_from DESC LIMIT 1
                        ) f ON TRUE
                        WHERE e.id = :id AND e.organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query(FinanceService::mapEngagement).single();
    }

    private TimeEntryView timeEntry(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT t.id, t.office_id, t.matter_id, m.matter_number,
                               t.professional_user_id, u.display_name, t.work_date,
                               t.minutes, t.description, t.billable, t.currency,
                               t.rate_snapshot, t.amount, t.status, t.approved_at, t.updated_at
                        FROM time_entries t
                        JOIN matters m ON m.id = t.matter_id
                        JOIN users u ON u.id = t.professional_user_id
                        WHERE t.id = :id AND t.organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query(FinanceService::mapTimeEntry).single();
    }

    private InvoiceView invoice(UUID id, UUID organizationId) {
        InvoiceBase base = jdbcClient.sql("""
                        SELECT i.id, i.office_id, i.engagement_id, i.matter_id,
                               m.matter_number, i.invoice_number, i.currency, i.status,
                               i.subtotal, i.tax_amount, i.total_amount, i.paid_amount,
                               i.due_date, i.issued_at, i.cancelled_at,
                               i.cancellation_reason, i.created_at
                        FROM invoices i JOIN matters m ON m.id = i.matter_id
                        WHERE i.id = :id AND i.organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query((rs, rowNum) -> new InvoiceBase(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getObject("engagement_id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getString("invoice_number"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("total_amount"),
                        rs.getBigDecimal("paid_amount"),
                        rs.getObject("due_date", LocalDate.class),
                        ts(rs, "issued_at"), ts(rs, "cancelled_at"),
                        rs.getString("cancellation_reason"),
                        rs.getTimestamp("created_at").toInstant()
                )).optional().orElseThrow(() -> new BusinessException(
                        "INVOICE_NOT_FOUND", "账单不存在", HttpStatus.NOT_FOUND
                ));
        List<InvoiceLineView> lines = jdbcClient.sql("""
                        SELECT id, line_number, line_type, description, quantity,
                               unit_price, amount, source_time_entry_id,
                               professional_user_id, work_date
                        FROM invoice_lines WHERE invoice_id = :invoiceId
                        ORDER BY line_number
                        """)
                .param("invoiceId", id)
                .query((rs, rowNum) -> new InvoiceLineView(
                        rs.getObject("id", UUID.class), rs.getInt("line_number"),
                        rs.getString("line_type"), rs.getString("description"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("source_time_entry_id", UUID.class),
                        rs.getObject("professional_user_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class)
                )).list();
        return new InvoiceView(
                base.id(), base.officeId(), base.engagementId(), base.matterId(),
                base.matterNumber(), base.invoiceNumber(), base.currency(), base.status(),
                base.subtotal(), base.tax(), base.total(), base.paid(),
                base.total().subtract(base.paid()), base.dueDate(), base.issuedAt(),
                base.cancelledAt(), base.cancellationReason(), base.createdAt(), lines
        );
    }

    private PaymentView payment(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT id, payment_reference, received_on, payer_name, currency,
                               amount, unallocated_amount, method, status, created_at
                        FROM payments WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query((rs, rowNum) -> new PaymentView(
                        rs.getObject("id", UUID.class),
                        rs.getString("payment_reference"),
                        rs.getObject("received_on", LocalDate.class),
                        rs.getString("payer_name"), rs.getString("currency"),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("unallocated_amount"),
                        rs.getString("method"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                )).single();
    }

    private static EngagementView mapEngagement(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new EngagementView(
                rs.getObject("id", UUID.class), rs.getObject("office_id", UUID.class),
                rs.getObject("matter_id", UUID.class), rs.getString("matter_number"),
                rs.getString("engagement_number"), rs.getString("title"),
                rs.getString("currency"), rs.getString("status"), rs.getString("fee_type"),
                rs.getBigDecimal("rate_amount"), rs.getBigDecimal("cap_amount"),
                rs.getBigDecimal("tax_rate"), rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), ts(rs, "approved_at"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static TimeEntryView mapTimeEntry(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new TimeEntryView(
                rs.getObject("id", UUID.class), rs.getObject("office_id", UUID.class),
                rs.getObject("matter_id", UUID.class), rs.getString("matter_number"),
                rs.getObject("professional_user_id", UUID.class),
                rs.getString("display_name"), rs.getObject("work_date", LocalDate.class),
                rs.getInt("minutes"), rs.getString("description"), rs.getBoolean("billable"),
                rs.getString("currency"), rs.getBigDecimal("rate_snapshot"),
                rs.getBigDecimal("amount"), rs.getString("status"),
                ts(rs, "approved_at"), rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static Instant ts(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static String code(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static BusinessException invalid(String code) {
        return new BusinessException(code, "请求参数或状态不合法", HttpStatus.BAD_REQUEST);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private record MatterBilling(UUID id, UUID officeId, String currency, String matterNumber) {}
    private record EngagementRate(UUID engagementId, UUID officeId, String currency, BigDecimal rate) {}
    private record EngagementInvoiceContext(
            UUID id, UUID officeId, UUID matterId, String matterNumber,
            String currency, BigDecimal taxRate, String feeType
    ) {}
    private record BillableTime(
            UUID id, LocalDate workDate, int minutes, String description,
            BigDecimal rate, BigDecimal amount, UUID professionalId
    ) {}
    private record InvoiceBalance(
            BigDecimal total, BigDecimal paid, String currency, UUID officeId
    ) {}
    private record TimeEntryApproval(UUID officeId, UUID professionalUserId) {}
    private record ReportTotals(
            BigDecimal approvedWip, BigDecimal unbilled, BigDecimal revenue,
            BigDecimal receivables, BigDecimal collected, BigDecimal utilization
    ) {}
    private record InvoiceBase(
            UUID id, UUID officeId, UUID engagementId, UUID matterId, String matterNumber,
            String invoiceNumber, String currency, String status, BigDecimal subtotal,
            BigDecimal tax, BigDecimal total, BigDecimal paid, LocalDate dueDate,
            Instant issuedAt, Instant cancelledAt, String cancellationReason, Instant createdAt
    ) {}
}
