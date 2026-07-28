package com.zoro.legaloa.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.common.PagePolicy;
import com.zoro.legaloa.common.PageResponse;
import com.zoro.legaloa.document.DocumentGovernanceController.ClauseRequest;
import com.zoro.legaloa.document.DocumentGovernanceController.ClauseView;
import com.zoro.legaloa.document.DocumentGovernanceController.DeletionView;
import com.zoro.legaloa.document.DocumentGovernanceController.ExportJobView;
import com.zoro.legaloa.document.DocumentGovernanceController.ExportRequest;
import com.zoro.legaloa.document.DocumentGovernanceController.HoldResourceRequest;
import com.zoro.legaloa.document.DocumentGovernanceController.IndexView;
import com.zoro.legaloa.document.DocumentGovernanceController.LegalHoldRequest;
import com.zoro.legaloa.document.DocumentGovernanceController.LegalHoldView;
import com.zoro.legaloa.document.DocumentGovernanceController.RetentionRuleRequest;
import com.zoro.legaloa.document.DocumentGovernanceController.RetentionRuleView;
import com.zoro.legaloa.document.DocumentGovernanceController.SearchHit;
import com.zoro.legaloa.document.DocumentGovernanceController.TemplateRequest;
import com.zoro.legaloa.document.DocumentGovernanceController.TemplateView;
import com.zoro.legaloa.document.DocumentGovernanceController.VersionView;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentGovernanceService {
    private static final Set<String> EXPORT_TYPES = Set.of("DOCUMENT");
    private static final Set<String> HOLD_TYPES = Set.of("DOCUMENT", "MATTER", "CONTRACT");
    private static final Set<String> DISPOSITION_ACTIONS = Set.of("REVIEW", "DELETE", "ARCHIVE");
    private static final Set<String> RISK_LEVELS = Set.of("STANDARD", "REVIEW_REQUIRED", "RESTRICTED");

    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;
    private final DocumentAccessService documentAccessService;
    private final ObjectStorageService storageService;
    private final OcrProvider ocrProvider;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public DocumentGovernanceService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            OfficeAccessService officeAccessService,
            DocumentAccessService documentAccessService,
            ObjectStorageService storageService,
            OcrProvider ocrProvider,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.officeAccessService = officeAccessService;
        this.documentAccessService = documentAccessService;
        this.storageService = storageService;
        this.ocrProvider = ocrProvider;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(UUID documentId) {
        RequestActor actor = actorProvider.current();
        requireDocumentRead(actor, documentId);
        return jdbcClient.sql("""
                        SELECT dv.id, dv.version_number, dv.original_filename, dv.content_type,
                               dv.size_bytes, dv.sha256, dv.version_status, dv.ingestion_status,
                               dv.created_by, dv.created_at
                        FROM document_versions dv
                        JOIN documents d ON d.id = dv.document_id
                        WHERE d.id = :documentId AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                        ORDER BY dv.version_number DESC
                        """)
                .param("documentId", documentId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new VersionView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version_number"),
                        rs.getString("original_filename"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getString("version_status"),
                        rs.getString("ingestion_status"),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()
                )).list();
    }

    @Transactional
    public IndexView index(UUID documentId, UUID versionId) {
        RequestActor actor = actorProvider.current();
        requireDocumentRead(actor, documentId);
        VersionSource source = jdbcClient.sql("""
                        SELECT dv.object_key, dv.content_type, dv.original_filename
                        FROM document_versions dv
                        JOIN documents d ON d.id = dv.document_id
                        WHERE d.id = :documentId AND dv.id = :versionId
                          AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL AND dv.ingestion_status = 'AVAILABLE'
                        """)
                .param("documentId", documentId)
                .param("versionId", versionId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new VersionSource(
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("original_filename")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_VERSION_UNAVAILABLE",
                        "仅可索引安全扫描通过的文档版本",
                        HttpStatus.CONFLICT
                ));
        jdbcClient.sql("""
                        INSERT INTO document_index_entries
                            (document_version_id, organization_id, provider, status)
                        VALUES (:versionId, :organizationId, :provider, 'PROCESSING')
                        ON CONFLICT (document_version_id) DO UPDATE
                        SET provider = EXCLUDED.provider, status = 'PROCESSING',
                            failure_reason = NULL, updated_at = now()
                        """)
                .param("versionId", versionId)
                .param("organizationId", actor.organizationId())
                .param("provider", ocrProvider.provider())
                .update();
        try (var content = storageService.open(source.objectKey())) {
            OcrProvider.OcrResult result = ocrProvider.extract(
                    content, source.contentType(), source.filename()
            );
            jdbcClient.sql("""
                            UPDATE document_index_entries
                            SET status = 'INDEXED', extracted_text = :text, language = :language,
                                page_count = :pageCount, indexed_at = now(), updated_at = now()
                            WHERE document_version_id = :versionId
                            """)
                    .param("text", result.text())
                    .param("language", result.language())
                    .param("pageCount", result.pageCount())
                    .param("versionId", versionId)
                    .update();
        } catch (Exception exception) {
            jdbcClient.sql("""
                            UPDATE document_index_entries
                            SET status = 'FAILED', failure_reason = :reason, updated_at = now()
                            WHERE document_version_id = :versionId
                            """)
                    .param("reason", safeMessage(exception))
                    .param("versionId", versionId)
                    .update();
            auditService.failure(
                    actor, "DOCUMENT_INDEX", "DOCUMENT_VERSION", versionId, "OCR_FAILED"
            );
            throw new BusinessException(
                    "DOCUMENT_INDEX_FAILED", "文档索引失败，原始文件未受影响",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        auditService.record(
                actor, "DOCUMENT_INDEX", "DOCUMENT_VERSION", versionId,
                "SUCCESS", null, Map.of("provider", ocrProvider.provider())
        );
        return indexView(versionId, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public PageResponse<SearchHit> search(String query, Integer page, Integer size) {
        RequestActor actor = actorProvider.current();
        var spec = PagePolicy.bounded(page, size, "updatedAtDesc",
                Map.of("updatedAtDesc", "d.updated_at DESC"), "updatedAtDesc");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        boolean governance = authorizationService.hasPermission(actor, "DOCUMENT_GOVERNANCE_VIEW");
        String pattern = "%" + escapeLike(query.trim()) + "%";
        long total = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM documents d
                        JOIN document_versions dv ON dv.id = d.current_version_id
                        JOIN document_index_entries die ON die.document_version_id = dv.id
                        LEFT JOIN matter_members mm ON mm.matter_id = d.matter_id
                          AND mm.user_id = :userId AND mm.left_at IS NULL
                        LEFT JOIN contract_members cm ON cm.contract_id = d.contract_id
                          AND cm.user_id = :userId
                        LEFT JOIN document_grants dg ON dg.document_id = d.id
                          AND dg.user_id = :userId
                          AND (dg.expires_at IS NULL OR dg.expires_at > now())
                        WHERE d.organization_id = :organizationId AND d.deleted_at IS NULL
                          AND die.status = 'INDEXED'
                          AND (die.extracted_text ILIKE :pattern ESCAPE '\\'
                               OR d.logical_name ILIKE :pattern ESCAPE '\\')
                          AND (:governance OR mm.user_id IS NOT NULL
                               OR cm.user_id IS NOT NULL OR dg.user_id IS NOT NULL)
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .param("pattern", pattern)
                .param("governance", governance && scope.globalAccess())
                .query(Long.class).single();
        List<SearchHit> items = jdbcClient.sql("""
                        SELECT DISTINCT d.id, dv.id AS version_id, d.logical_name,
                               dv.original_filename, d.document_type,
                               LEFT(die.extracted_text, 500) AS excerpt, d.updated_at
                        FROM documents d
                        JOIN document_versions dv ON dv.id = d.current_version_id
                        JOIN document_index_entries die ON die.document_version_id = dv.id
                        LEFT JOIN matter_members mm ON mm.matter_id = d.matter_id
                          AND mm.user_id = :userId AND mm.left_at IS NULL
                        LEFT JOIN contract_members cm ON cm.contract_id = d.contract_id
                          AND cm.user_id = :userId
                        LEFT JOIN document_grants dg ON dg.document_id = d.id
                          AND dg.user_id = :userId
                          AND (dg.expires_at IS NULL OR dg.expires_at > now())
                        WHERE d.organization_id = :organizationId AND d.deleted_at IS NULL
                          AND die.status = 'INDEXED'
                          AND (die.extracted_text ILIKE :pattern ESCAPE '\\'
                               OR d.logical_name ILIKE :pattern ESCAPE '\\')
                          AND (:governance OR mm.user_id IS NOT NULL
                               OR cm.user_id IS NOT NULL OR dg.user_id IS NOT NULL)
                        ORDER BY d.updated_at DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .param("pattern", pattern)
                .param("governance", governance && scope.globalAccess())
                .param("size", spec.size())
                .param("offset", spec.offset())
                .query((rs, rowNum) -> new SearchHit(
                        rs.getObject("id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getString("logical_name"),
                        rs.getString("original_filename"),
                        rs.getString("document_type"),
                        rs.getString("excerpt"),
                        1.0,
                        rs.getTimestamp("updated_at").toInstant()
                )).list();
        return PageResponse.of(items, spec.page(), spec.size(), total);
    }

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        RequestActor actor = actorProvider.current();
        requireView(actor);
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT t.id, t.office_id, t.code, t.name_zh, t.name_en, t.category,
                               t.status, t.current_version_id, v.version_number, v.title,
                               v.body_markdown, t.updated_at
                        FROM document_templates t
                        JOIN document_template_versions v ON v.id = t.current_version_id
                        WHERE t.organization_id = :organizationId AND t.deleted_at IS NULL
                          AND (:globalAccess OR t.office_id IS NULL OR t.office_id IN (:officeIds))
                        ORDER BY t.category, t.code
                        LIMIT 500
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new TemplateView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name_zh"),
                        rs.getString("name_en"),
                        rs.getString("category"),
                        rs.getString("status"),
                        rs.getObject("current_version_id", UUID.class),
                        rs.getInt("version_number"),
                        rs.getString("title"),
                        rs.getString("body_markdown"),
                        rs.getTimestamp("updated_at").toInstant()
                )).list();
    }

    @Transactional
    public TemplateView createTemplate(TemplateRequest request) {
        RequestActor actor = actorProvider.current();
        requireManage(actor);
        UUID officeId = request.officeId() == null ? null
                : officeAccessService.resolveManagedOffice(actor, request.officeId());
        UUID id = jdbcClient.sql("""
                        INSERT INTO document_templates
                            (organization_id, office_id, code, name_zh, name_en,
                             category, created_by)
                        VALUES
                            (:organizationId, :officeId, :code, :nameZh, :nameEn,
                             :category, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("code", normalizedCode(request.code()))
                .param("nameZh", request.nameZh().trim())
                .param("nameEn", request.nameEn().trim())
                .param("category", normalizedCode(request.category()))
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        UUID versionId = jdbcClient.sql("""
                        INSERT INTO document_template_versions
                            (template_id, version_number, title, body_markdown,
                             change_note, created_by)
                        VALUES (:templateId, 1, :title, :body, :note, :createdBy)
                        RETURNING id
                        """)
                .param("templateId", id)
                .param("title", request.title().trim())
                .param("body", request.bodyMarkdown())
                .param("note", request.changeNote())
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        jdbcClient.sql("""
                        UPDATE document_templates SET current_version_id = :versionId
                        WHERE id = :id
                        """)
                .param("versionId", versionId)
                .param("id", id).update();
        auditService.success(actor, "DOCUMENT_TEMPLATE_CREATE", "DOCUMENT_TEMPLATE", id);
        return template(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<ClauseView> clauses() {
        RequestActor actor = actorProvider.current();
        requireView(actor);
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT c.id, c.office_id, c.code, c.title_zh, c.title_en,
                               c.category, c.risk_level, c.status, v.version_number,
                               v.body_zh, v.body_en, v.guidance_zh, v.guidance_en,
                               c.updated_at
                        FROM clause_library c
                        JOIN clause_versions v ON v.id = c.current_version_id
                        WHERE c.organization_id = :organizationId AND c.deleted_at IS NULL
                          AND (:globalAccess OR c.office_id IS NULL OR c.office_id IN (:officeIds))
                        ORDER BY c.category, c.code
                        LIMIT 1000
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(DocumentGovernanceService::mapClause)
                .list();
    }

    @Transactional
    public ClauseView createClause(ClauseRequest request) {
        RequestActor actor = actorProvider.current();
        requireManage(actor);
        String riskLevel = normalizedCode(request.riskLevel());
        if (!RISK_LEVELS.contains(riskLevel)) {
            throw invalidEnum("CLAUSE_RISK_INVALID");
        }
        UUID officeId = request.officeId() == null ? null
                : officeAccessService.resolveManagedOffice(actor, request.officeId());
        UUID id = jdbcClient.sql("""
                        INSERT INTO clause_library
                            (organization_id, office_id, code, title_zh, title_en,
                             category, risk_level, created_by)
                        VALUES
                            (:organizationId, :officeId, :code, :titleZh, :titleEn,
                             :category, :riskLevel, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("code", normalizedCode(request.code()))
                .param("titleZh", request.titleZh().trim())
                .param("titleEn", request.titleEn().trim())
                .param("category", normalizedCode(request.category()))
                .param("riskLevel", riskLevel)
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        UUID versionId = jdbcClient.sql("""
                        INSERT INTO clause_versions
                            (clause_id, version_number, body_zh, body_en,
                             guidance_zh, guidance_en, change_note, created_by)
                        VALUES
                            (:clauseId, 1, :bodyZh, :bodyEn,
                             :guidanceZh, :guidanceEn, :note, :createdBy)
                        RETURNING id
                        """)
                .param("clauseId", id)
                .param("bodyZh", request.bodyZh())
                .param("bodyEn", request.bodyEn())
                .param("guidanceZh", request.guidanceZh())
                .param("guidanceEn", request.guidanceEn())
                .param("note", request.changeNote())
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        jdbcClient.sql("UPDATE clause_library SET current_version_id = :versionId WHERE id = :id")
                .param("versionId", versionId).param("id", id).update();
        auditService.success(actor, "CLAUSE_CREATE", "CLAUSE", id);
        return clause(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<RetentionRuleView> retentionRules() {
        RequestActor actor = actorProvider.current();
        requireView(actor);
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT id, office_id, resource_type, document_type, retention_years,
                               disposition_action, enabled, updated_at
                        FROM retention_rules
                        WHERE organization_id = :organizationId
                          AND (:globalAccess OR office_id IS NULL OR office_id IN (:officeIds))
                        ORDER BY resource_type, document_type NULLS FIRST
                        LIMIT 500
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new RetentionRuleView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("resource_type"),
                        rs.getString("document_type"),
                        rs.getInt("retention_years"),
                        rs.getString("disposition_action"),
                        rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toInstant()
                )).list();
    }

    @Transactional
    public RetentionRuleView createRetentionRule(RetentionRuleRequest request) {
        RequestActor actor = actorProvider.current();
        requireManage(actor);
        String action = normalizedCode(request.dispositionAction());
        if (!DISPOSITION_ACTIONS.contains(action)) {
            throw invalidEnum("RETENTION_ACTION_INVALID");
        }
        UUID officeId = request.officeId() == null ? null
                : officeAccessService.resolveManagedOffice(actor, request.officeId());
        UUID id = jdbcClient.sql("""
                        INSERT INTO retention_rules
                            (organization_id, office_id, resource_type, document_type,
                             retention_years, disposition_action, created_by)
                        VALUES
                            (:organizationId, :officeId, :resourceType, :documentType,
                             :years, :action, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("resourceType", normalizedCode(request.resourceType()))
                .param("documentType", blankToNull(request.documentType()))
                .param("years", request.retentionYears())
                .param("action", action)
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        auditService.success(actor, "RETENTION_RULE_CREATE", "RETENTION_RULE", id);
        return retentionRule(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<LegalHoldView> legalHolds() {
        RequestActor actor = actorProvider.current();
        requireView(actor);
        return jdbcClient.sql("""
                        SELECT h.id, h.name, h.reason, h.status, h.created_by, h.created_at,
                               h.released_by, h.released_at, COUNT(r.resource_id) AS resource_count
                        FROM legal_holds h
                        LEFT JOIN legal_hold_resources r ON r.legal_hold_id = h.id
                        WHERE h.organization_id = :organizationId
                        GROUP BY h.id
                        ORDER BY h.created_at DESC
                        LIMIT 500
                        """)
                .param("organizationId", actor.organizationId())
                .query(DocumentGovernanceService::mapHold)
                .list();
    }

    @Transactional
    public LegalHoldView createLegalHold(LegalHoldRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "LEGAL_HOLD_MANAGE");
        UUID id = jdbcClient.sql("""
                        INSERT INTO legal_holds
                            (organization_id, name, reason, created_by)
                        VALUES (:organizationId, :name, :reason, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("name", request.name().trim())
                .param("reason", request.reason().trim())
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        for (HoldResourceRequest resource : request.resources()) {
            String type = normalizedCode(resource.resourceType());
            if (!HOLD_TYPES.contains(type) || resource.resourceId() == null
                    || !resourceExists(actor.organizationId(), type, resource.resourceId())) {
                throw new BusinessException(
                        "LEGAL_HOLD_RESOURCE_INVALID", "保全对象不存在或类型不受支持",
                        HttpStatus.BAD_REQUEST
                );
            }
            jdbcClient.sql("""
                            INSERT INTO legal_hold_resources
                                (legal_hold_id, resource_type, resource_id, added_by)
                            VALUES (:holdId, :type, :resourceId, :addedBy)
                            """)
                    .param("holdId", id)
                    .param("type", type)
                    .param("resourceId", resource.resourceId())
                    .param("addedBy", actor.userId())
                    .update();
        }
        auditService.record(
                actor, "LEGAL_HOLD_CREATE", "LEGAL_HOLD", id,
                "SUCCESS", null, Map.of("resourceCount", request.resources().size())
        );
        return hold(id, actor.organizationId());
    }

    @Transactional
    public LegalHoldView releaseLegalHold(UUID id, String reason) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "LEGAL_HOLD_MANAGE");
        int updated = jdbcClient.sql("""
                        UPDATE legal_holds
                        SET status = 'RELEASED', released_by = :releasedBy,
                            released_at = now(), release_reason = :reason
                        WHERE id = :id AND organization_id = :organizationId
                          AND status = 'ACTIVE'
                        """)
                .param("releasedBy", actor.userId())
                .param("reason", reason.trim())
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "LEGAL_HOLD_RELEASE_INVALID", "保全不存在或已经解除",
                    HttpStatus.CONFLICT
            );
        }
        auditService.success(actor, "LEGAL_HOLD_RELEASE", "LEGAL_HOLD", id);
        return hold(id, actor.organizationId());
    }

    @Transactional
    public DeletionView deleteDocument(UUID documentId, String reason) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "DOCUMENT_DELETE");
        DocumentContext context = jdbcClient.sql("""
                        SELECT id, matter_id, contract_id
                        FROM documents
                        WHERE id = :id AND organization_id = :organizationId
                          AND deleted_at IS NULL
                        FOR UPDATE
                        """)
                .param("id", documentId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new DocumentContext(
                        rs.getObject("id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getObject("contract_id", UUID.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_NOT_FOUND", "文档不存在或已删除", HttpStatus.NOT_FOUND
                ));
        boolean held = Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1
                          FROM legal_holds h
                          JOIN legal_hold_resources r ON r.legal_hold_id = h.id
                          WHERE h.organization_id = :organizationId AND h.status = 'ACTIVE'
                            AND (
                              (r.resource_type = 'DOCUMENT' AND r.resource_id = :documentId)
                              OR (r.resource_type = 'MATTER' AND r.resource_id = :matterId)
                              OR (r.resource_type = 'CONTRACT' AND r.resource_id = :contractId)
                            )
                        )
                        """)
                .param("organizationId", actor.organizationId())
                .param("documentId", documentId)
                .param("matterId", context.matterId() == null ? new UUID(0, 0) : context.matterId())
                .param("contractId", context.contractId() == null ? new UUID(0, 0) : context.contractId())
                .query(Boolean.class).single());
        UUID requestId = jdbcClient.sql("""
                        INSERT INTO document_deletion_requests
                            (organization_id, document_id, requested_by, reason, status,
                             decided_by, decision_reason, decided_at)
                        VALUES
                            (:organizationId, :documentId, :requestedBy, :reason, :status,
                             :decidedBy, :decisionReason, now())
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("documentId", documentId)
                .param("requestedBy", actor.userId())
                .param("reason", reason.trim())
                .param("status", held ? "BLOCKED_BY_HOLD" : "COMPLETED")
                .param("decidedBy", actor.userId())
                .param("decisionReason", held ? "ACTIVE_LEGAL_HOLD" : "APPROVED_BY_POLICY")
                .query(UUID.class).single();
        if (held) {
            auditService.failure(
                    actor, "DOCUMENT_DELETE", "DOCUMENT", documentId, "ACTIVE_LEGAL_HOLD"
            );
            return new DeletionView(
                    requestId, documentId, "BLOCKED_BY_HOLD", "ACTIVE_LEGAL_HOLD", Instant.now()
            );
        }
        jdbcClient.sql("""
                        UPDATE documents SET deleted_at = now(), updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", documentId).update();
        auditService.record(
                actor, "DOCUMENT_DELETE", "DOCUMENT", documentId,
                "SUCCESS", null, Map.of("reason", reason.trim(), "requestId", requestId)
        );
        return new DeletionView(requestId, documentId, "COMPLETED", reason.trim(), Instant.now());
    }

    @Transactional
    public ExportJobView createExport(ExportRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "DOCUMENT_EXPORT");
        String type = normalizedCode(request.resourceType());
        if (!EXPORT_TYPES.contains(type)) {
            throw new BusinessException(
                    "EXPORT_TYPE_INVALID", "暂不支持该资源类型导出", HttpStatus.BAD_REQUEST
            );
        }
        String filters;
        try {
            filters = objectMapper.writeValueAsString(Map.of(
                    "query", request.query() == null ? "" : request.query().trim()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize export filters", exception);
        }
        UUID id = jdbcClient.sql("""
                        INSERT INTO export_jobs
                            (organization_id, requested_by, resource_type, filters)
                        VALUES (:organizationId, :requestedBy, :type, CAST(:filters AS jsonb))
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("requestedBy", actor.userId())
                .param("type", type)
                .param("filters", filters)
                .query(UUID.class).single();
        auditService.success(actor, "DOCUMENT_EXPORT_REQUEST", "EXPORT_JOB", id);
        return export(id);
    }

    @Transactional
    public ExportJobView export(UUID id) {
        RequestActor actor = actorProvider.current();
        ExportRow row = exportRow(id, actor.organizationId());
        if (!row.requestedBy().equals(actor.userId())
                && !authorizationService.hasPermission(actor, "DOCUMENT_GOVERNANCE_VIEW")) {
            throw new BusinessException(
                    "EXPORT_ACCESS_DENIED", "无权查看该导出任务", HttpStatus.FORBIDDEN
            );
        }
        String url = "COMPLETED".equals(row.status()) && row.objectKey() != null
                ? storageService.presignedDownload(row.objectKey()) : null;
        if (url != null) {
            auditService.success(actor, "DOCUMENT_EXPORT_DOWNLOAD", "EXPORT_JOB", id);
        }
        return toExportView(row, url);
    }

    @Scheduled(fixedDelayString = "${app.exports.worker-delay-ms:5000}")
    public void processExports() {
        List<ExportClaim> claims = jdbcClient.sql("""
                        UPDATE export_jobs
                        SET status = 'RUNNING', started_at = now()
                        WHERE id IN (
                          SELECT id FROM export_jobs
                          WHERE status = 'PENDING'
                          ORDER BY created_at
                          FOR UPDATE SKIP LOCKED
                          LIMIT 3
                        )
                        RETURNING id, organization_id, requested_by, resource_type
                        """)
                .query((rs, rowNum) -> new ExportClaim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("requested_by", UUID.class),
                        rs.getString("resource_type")
                )).list();
        for (ExportClaim claim : claims) {
            try {
                completeDocumentExport(claim);
            } catch (Exception exception) {
                jdbcClient.sql("""
                                UPDATE export_jobs SET status = 'FAILED',
                                    error_message = :message, completed_at = now()
                                WHERE id = :id
                                """)
                        .param("message", safeMessage(exception))
                        .param("id", claim.id())
                        .update();
            }
        }
    }

    private void completeDocumentExport(ExportClaim claim) throws Exception {
        List<ExportDocument> rows = jdbcClient.sql("""
                        SELECT d.id, d.logical_name, d.document_type, d.confidentiality_level,
                               dv.version_number, dv.original_filename, dv.size_bytes,
                               dv.sha256, dv.ingestion_status, d.created_at, d.updated_at
                        FROM documents d
                        LEFT JOIN document_versions dv ON dv.id = d.current_version_id
                        WHERE d.organization_id = :organizationId AND d.deleted_at IS NULL
                        ORDER BY d.updated_at DESC
                        LIMIT 10000
                        """)
                .param("organizationId", claim.organizationId())
                .query((rs, rowNum) -> new ExportDocument(
                        rs.getObject("id", UUID.class),
                        rs.getString("logical_name"),
                        rs.getString("document_type"),
                        rs.getString("confidentiality_level"),
                        rs.getObject("version_number", Integer.class),
                        rs.getString("original_filename"),
                        rs.getObject("size_bytes", Long.class),
                        rs.getString("sha256"),
                        rs.getString("ingestion_status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                )).list();
        StringBuilder csv = new StringBuilder(
                "document_id,logical_name,document_type,confidentiality,version,filename,"
                        + "size_bytes,sha256,ingestion_status,created_at,updated_at\n"
        );
        for (ExportDocument row : rows) {
            csv.append(csv(row.id())).append(',')
                    .append(csv(row.logicalName())).append(',')
                    .append(csv(row.documentType())).append(',')
                    .append(csv(row.confidentiality())).append(',')
                    .append(csv(row.versionNumber())).append(',')
                    .append(csv(row.filename())).append(',')
                    .append(csv(row.sizeBytes())).append(',')
                    .append(csv(row.sha256())).append(',')
                    .append(csv(row.ingestionStatus())).append(',')
                    .append(csv(row.createdAt())).append(',')
                    .append(csv(row.updatedAt())).append('\n');
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
        );
        String key = claim.organizationId() + "/exports/" + claim.id() + ".csv";
        storageService.put(key, bytes, "text/csv; charset=utf-8");
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        jdbcClient.sql("""
                        UPDATE export_jobs
                        SET status = 'COMPLETED', row_count = :rowCount,
                            object_key = :objectKey, sha256 = :sha256,
                            completed_at = now(), expires_at = :expiresAt
                        WHERE id = :id
                        """)
                .param("rowCount", rows.size())
                .param("objectKey", key)
                .param("sha256", sha256)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("id", claim.id())
                .update();
        RequestActor actor = jdbcClient.sql("""
                        SELECT username, display_name FROM users
                        WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("id", claim.requestedBy())
                .param("organizationId", claim.organizationId())
                .query((rs, rowNum) -> new RequestActor(
                        claim.requestedBy(), claim.organizationId(),
                        rs.getString("username"), rs.getString("display_name")
                )).single();
        auditService.record(
                actor, "DOCUMENT_EXPORT_COMPLETE", "EXPORT_JOB", claim.id(),
                "SUCCESS", null, Map.of("rowCount", rows.size(), "sha256", sha256)
        );
    }

    private void requireDocumentRead(RequestActor actor, UUID documentId) {
        if (authorizationService.hasPermission(actor, "DOCUMENT_GOVERNANCE_VIEW")
                && officeAccessService.scope(actor).globalAccess()) {
            Boolean exists = jdbcClient.sql("""
                            SELECT EXISTS (
                              SELECT 1 FROM documents
                              WHERE id = :id AND organization_id = :organizationId
                                AND deleted_at IS NULL
                            )
                            """)
                    .param("id", documentId)
                    .param("organizationId", actor.organizationId())
                    .query(Boolean.class).single();
            if (Boolean.TRUE.equals(exists)) {
                return;
            }
        }
        documentAccessService.requireDocumentRead(actor, documentId, false);
    }

    private void requireView(RequestActor actor) {
        authorizationService.requirePermission(actor, "DOCUMENT_GOVERNANCE_VIEW");
    }

    private void requireManage(RequestActor actor) {
        authorizationService.requirePermission(actor, "DOCUMENT_GOVERNANCE_MANAGE");
    }

    private IndexView indexView(UUID versionId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT document_version_id, provider, status, language, page_count,
                               indexed_at, failure_reason
                        FROM document_index_entries
                        WHERE document_version_id = :versionId
                          AND organization_id = :organizationId
                        """)
                .param("versionId", versionId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new IndexView(
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("status"),
                        rs.getString("language"),
                        rs.getObject("page_count", Integer.class),
                        rs.getTimestamp("indexed_at") == null
                                ? null : rs.getTimestamp("indexed_at").toInstant(),
                        rs.getString("failure_reason")
                )).single();
    }

    private TemplateView template(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT t.id, t.office_id, t.code, t.name_zh, t.name_en, t.category,
                               t.status, t.current_version_id, v.version_number, v.title,
                               v.body_markdown, t.updated_at
                        FROM document_templates t
                        JOIN document_template_versions v ON v.id = t.current_version_id
                        WHERE t.id = :id AND t.organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query((rs, rowNum) -> new TemplateView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name_zh"),
                        rs.getString("name_en"),
                        rs.getString("category"),
                        rs.getString("status"),
                        rs.getObject("current_version_id", UUID.class),
                        rs.getInt("version_number"),
                        rs.getString("title"),
                        rs.getString("body_markdown"),
                        rs.getTimestamp("updated_at").toInstant()
                )).single();
    }

    private ClauseView clause(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT c.id, c.office_id, c.code, c.title_zh, c.title_en,
                               c.category, c.risk_level, c.status, v.version_number,
                               v.body_zh, v.body_en, v.guidance_zh, v.guidance_en,
                               c.updated_at
                        FROM clause_library c
                        JOIN clause_versions v ON v.id = c.current_version_id
                        WHERE c.id = :id AND c.organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query(DocumentGovernanceService::mapClause).single();
    }

    private RetentionRuleView retentionRule(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT id, office_id, resource_type, document_type, retention_years,
                               disposition_action, enabled, updated_at
                        FROM retention_rules
                        WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query((rs, rowNum) -> new RetentionRuleView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("resource_type"),
                        rs.getString("document_type"),
                        rs.getInt("retention_years"),
                        rs.getString("disposition_action"),
                        rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toInstant()
                )).single();
    }

    private LegalHoldView hold(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT h.id, h.name, h.reason, h.status, h.created_by, h.created_at,
                               h.released_by, h.released_at, COUNT(r.resource_id) AS resource_count
                        FROM legal_holds h
                        LEFT JOIN legal_hold_resources r ON r.legal_hold_id = h.id
                        WHERE h.id = :id AND h.organization_id = :organizationId
                        GROUP BY h.id
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query(DocumentGovernanceService::mapHold).single();
    }

    private boolean resourceExists(UUID organizationId, String type, UUID id) {
        String table = switch (type) {
            case "DOCUMENT" -> "documents";
            case "MATTER" -> "matters";
            case "CONTRACT" -> "contracts";
            default -> throw new IllegalArgumentException("Unsupported resource type");
        };
        return Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM %s
                          WHERE id = :id AND organization_id = :organizationId
                            AND deleted_at IS NULL
                        )
                        """.formatted(table))
                .param("id", id).param("organizationId", organizationId)
                .query(Boolean.class).single());
    }

    private ExportRow exportRow(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT id, requested_by, resource_type, status, row_count,
                               object_key, sha256, error_message, created_at,
                               completed_at, expires_at
                        FROM export_jobs
                        WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ExportRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("requested_by", UUID.class),
                        rs.getString("resource_type"),
                        rs.getString("status"),
                        rs.getObject("row_count", Integer.class),
                        rs.getString("object_key"),
                        rs.getString("sha256"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getTimestamp("expires_at") == null
                                ? null : rs.getTimestamp("expires_at").toInstant()
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "EXPORT_NOT_FOUND", "导出任务不存在", HttpStatus.NOT_FOUND
                ));
    }

    private static ClauseView mapClause(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ClauseView(
                rs.getObject("id", UUID.class),
                rs.getObject("office_id", UUID.class),
                rs.getString("code"),
                rs.getString("title_zh"),
                rs.getString("title_en"),
                rs.getString("category"),
                rs.getString("risk_level"),
                rs.getString("status"),
                rs.getInt("version_number"),
                rs.getString("body_zh"),
                rs.getString("body_en"),
                rs.getString("guidance_zh"),
                rs.getString("guidance_en"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static LegalHoldView mapHold(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new LegalHoldView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getInt("resource_count"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("released_by", UUID.class),
                rs.getTimestamp("released_at") == null
                        ? null : rs.getTimestamp("released_at").toInstant()
        );
    }

    private static ExportJobView toExportView(ExportRow row, String url) {
        return new ExportJobView(
                row.id(), row.resourceType(), row.status(), row.rowCount(), url,
                row.sha256(), row.errorMessage(), row.createdAt(), row.completedAt(), row.expiresAt()
        );
    }

    private static String normalizedCode(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : normalizedCode(value);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BusinessException invalidEnum(String code) {
        return new BusinessException(code, "枚举值不受支持", HttpStatus.BAD_REQUEST);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(message.length(), 500));
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.toString().replace("\"", "\"\"")
                .replace("\r", " ").replace("\n", " ") + "\"";
    }

    private record VersionSource(String objectKey, String contentType, String filename) {}
    private record DocumentContext(UUID id, UUID matterId, UUID contractId) {}
    private record ExportClaim(UUID id, UUID organizationId, UUID requestedBy, String resourceType) {}
    private record ExportDocument(
            UUID id, String logicalName, String documentType, String confidentiality,
            Integer versionNumber, String filename, Long sizeBytes, String sha256,
            String ingestionStatus, Instant createdAt, Instant updatedAt
    ) {}
    private record ExportRow(
            UUID id, UUID requestedBy, String resourceType, String status, Integer rowCount,
            String objectKey, String sha256, String errorMessage, Instant createdAt,
            Instant completedAt, Instant expiresAt
    ) {}
}
