package com.zoro.legaloa.matter;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.matter.ContractController.ArchiveSignedFileRequest;
import com.zoro.legaloa.matter.ContractController.ContractArchiveLinkView;
import com.zoro.legaloa.matter.ContractController.ContractDetailView;
import com.zoro.legaloa.matter.ContractController.ContractLifecycleComment;
import com.zoro.legaloa.matter.ContractController.ContractLifecycleEventView;
import com.zoro.legaloa.matter.ContractController.ContractVersionView;
import com.zoro.legaloa.matter.ContractController.ContractView;
import com.zoro.legaloa.matter.ContractController.CreateContractVersionRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractLifecycleService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final DocumentAccessService documentAccessService;
    private final AuditService auditService;
    private final ContractService contractService;

    public ContractLifecycleService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            DocumentAccessService documentAccessService,
            AuditService auditService,
            ContractService contractService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.documentAccessService = documentAccessService;
        this.auditService = auditService;
        this.contractService = contractService;
    }

    @Transactional(readOnly = true)
    public ContractDetailView detail(UUID contractId) {
        RequestActor actor = actorProvider.current();
        requireContractAccess(actor, contractId);
        return detailForActor(actor, contractId);
    }

    @Transactional
    public ContractDetailView createVersion(
            UUID contractId,
            CreateContractVersionRequest request
    ) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONTRACT_MANAGE");
        ContractState contract = lockContract(actor, contractId);
        if (!ContractLifecyclePolicy.canCreateVersion(contract.status())) {
            throw new BusinessException(
                    "CONTRACT_VERSION_CREATE_INVALID",
                    "只有草稿或被驳回的合同可以创建送审版本",
                    HttpStatus.CONFLICT
            );
        }
        DocumentVersionRef documentVersion = requireContractDocumentVersion(
                actor, contractId, request.documentVersionId(), null
        );
        int versionNumber = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_number), 0) + 1
                        FROM contract_versions
                        WHERE contract_id = :contractId
                        """)
                .param("contractId", contractId)
                .query(Integer.class)
                .single();
        UUID versionId = jdbcClient.sql("""
                        INSERT INTO contract_versions
                            (contract_id, version_number, status, summary, created_by,
                             primary_document_version_id, signature_status)
                        VALUES
                            (:contractId, :versionNumber, 'DRAFT', :summary, :createdBy,
                             :documentVersionId, 'UNSIGNED')
                        RETURNING id
                        """)
                .param("contractId", contractId)
                .param("versionNumber", versionNumber)
                .param("summary", request.summary().trim())
                .param("createdBy", actor.userId())
                .param("documentVersionId", request.documentVersionId())
                .query(UUID.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO contract_version_documents
                            (contract_version_id, document_version_id)
                        VALUES (:versionId, :documentVersionId)
                        """)
                .param("versionId", versionId)
                .param("documentVersionId", request.documentVersionId())
                .update();
        recordEvent(
                actor, contractId, versionId, "VERSION_CREATED",
                contract.status(), contract.status(), documentVersion.id(),
                request.summary().trim()
        );
        auditService.record(
                actor, "CONTRACT_VERSION_CREATE", "CONTRACT", contractId,
                "SUCCESS", null, Map.of(
                        "contractVersionId", versionId,
                        "documentVersionId", documentVersion.id(),
                        "versionNumber", versionNumber
                )
        );
        return detailForActor(actor, contractId);
    }

    @Transactional
    public ContractDetailView finalizeVersion(
            UUID contractId,
            UUID versionId,
            ContractLifecycleComment request
    ) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONTRACT_FINALIZE");
        ContractState contract = lockContract(actor, contractId);
        ContractVersionState version = lockVersion(contractId, versionId);
        if (!ContractLifecyclePolicy.canFinalize(contract.status(), version.status())) {
            throw new BusinessException(
                    "CONTRACT_FINALIZE_INVALID",
                    "只有已审批合同的草稿或已复核版本可以定稿",
                    HttpStatus.CONFLICT
            );
        }
        requireContractDocumentVersion(
                actor, contractId, version.primaryDocumentVersionId(), null
        );
        jdbcClient.sql("""
                        UPDATE contract_versions
                        SET status = 'SUPERSEDED', updated_at = now()
                        WHERE contract_id = :contractId
                          AND status = 'FINAL' AND id <> :versionId
                        """)
                .param("contractId", contractId)
                .param("versionId", versionId)
                .update();
        jdbcClient.sql("""
                        UPDATE document_versions
                        SET version_status = 'SUPERSEDED'
                        WHERE document_id = (
                            SELECT document_id FROM document_versions
                            WHERE id = :documentVersionId
                        )
                          AND version_status = 'FINAL'
                          AND id <> :documentVersionId
                        """)
                .param("documentVersionId", version.primaryDocumentVersionId())
                .update();
        int updated = jdbcClient.sql("""
                        UPDATE contract_versions
                        SET status = 'FINAL', signature_status = 'PENDING',
                            finalized_by = :actorId, finalized_at = now(), updated_at = now()
                        WHERE id = :versionId AND contract_id = :contractId
                          AND status IN ('DRAFT', 'REVIEWED')
                        """)
                .param("actorId", actor.userId())
                .param("versionId", versionId)
                .param("contractId", contractId)
                .update();
        if (updated != 1) {
            throw stateChanged();
        }
        jdbcClient.sql("""
                        UPDATE document_versions
                        SET version_status = 'FINAL', signature_status = 'PENDING'
                        WHERE id = :documentVersionId AND ingestion_status = 'AVAILABLE'
                        """)
                .param("documentVersionId", version.primaryDocumentVersionId())
                .update();
        recordEvent(
                actor, contractId, versionId, "VERSION_FINALIZED",
                contract.status(), contract.status(), version.primaryDocumentVersionId(),
                trimToNull(request.comment())
        );
        auditService.record(
                actor, "CONTRACT_VERSION_FINALIZE", "CONTRACT", contractId,
                "SUCCESS", null, Map.of(
                        "contractVersionId", versionId,
                        "documentVersionId", version.primaryDocumentVersionId()
                )
        );
        return detailForActor(actor, contractId);
    }

    @Transactional
    public ContractDetailView archiveSignedFile(
            UUID contractId,
            UUID versionId,
            ArchiveSignedFileRequest request
    ) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONTRACT_SIGN_ARCHIVE");
        ContractState contract = lockContract(actor, contractId);
        ContractVersionState version = lockVersion(contractId, versionId);
        if (!ContractLifecyclePolicy.canArchiveSignature(
                contract.status(), version.status(), version.signatureStatus()
        )) {
            throw new BusinessException(
                    "CONTRACT_SIGN_ARCHIVE_INVALID",
                    "只有已审批合同的最终待签版本可以归档签署件",
                    HttpStatus.CONFLICT
            );
        }
        DocumentVersionRef signedDocument = requireContractDocumentVersion(
                actor,
                contractId,
                request.signedDocumentVersionId(),
                "SIGNED_CONTRACT"
        );
        if (signedDocument.id().equals(version.primaryDocumentVersionId())) {
            throw new BusinessException(
                    "CONTRACT_SIGNED_FILE_INVALID",
                    "签署件必须与待签定稿文件分开归档",
                    HttpStatus.BAD_REQUEST
            );
        }
        jdbcClient.sql("""
                        UPDATE document_versions
                        SET version_status = 'SUPERSEDED'
                        WHERE document_id = :documentId
                          AND version_status = 'FINAL'
                          AND id <> :documentVersionId
                        """)
                .param("documentId", signedDocument.documentId())
                .param("documentVersionId", signedDocument.id())
                .update();
        jdbcClient.sql("""
                        UPDATE document_versions
                        SET version_status = 'FINAL', signature_status = 'SIGNED'
                        WHERE id = :documentVersionId AND ingestion_status = 'AVAILABLE'
                        """)
                .param("documentVersionId", signedDocument.id())
                .update();
        int updated = jdbcClient.sql("""
                        UPDATE contract_versions
                        SET signed_document_version_id = :signedDocumentVersionId,
                            signature_status = 'SIGNED', signed_by = :actorId,
                            signed_at = now(), updated_at = now()
                        WHERE id = :versionId AND contract_id = :contractId
                          AND status = 'FINAL'
                          AND signature_status IN ('PENDING', 'UNSIGNED')
                        """)
                .param("signedDocumentVersionId", signedDocument.id())
                .param("actorId", actor.userId())
                .param("versionId", versionId)
                .param("contractId", contractId)
                .update();
        if (updated != 1) {
            throw stateChanged();
        }
        jdbcClient.sql("""
                        INSERT INTO contract_version_documents
                            (contract_version_id, document_version_id)
                        VALUES (:versionId, :documentVersionId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("versionId", versionId)
                .param("documentVersionId", signedDocument.id())
                .update();
        jdbcClient.sql("""
                        UPDATE contracts
                        SET status = 'SIGNED', updated_at = now()
                        WHERE id = :contractId AND organization_id = :organizationId
                          AND status = 'APPROVED'
                        """)
                .param("contractId", contractId)
                .param("organizationId", actor.organizationId())
                .update();
        recordEvent(
                actor, contractId, versionId, "SIGNED_FILE_ARCHIVED",
                contract.status(), "SIGNED", signedDocument.id(),
                trimToNull(request.comment())
        );
        auditService.record(
                actor, "CONTRACT_SIGNED_FILE_ARCHIVE", "CONTRACT", contractId,
                "SUCCESS", null, Map.of(
                        "contractVersionId", versionId,
                        "signedDocumentVersionId", signedDocument.id(),
                        "sha256", signedDocument.sha256()
                )
        );
        return detailForActor(actor, contractId);
    }

    private ContractDetailView detailForActor(RequestActor actor, UUID contractId) {
        ContractView contract = contractService.list().stream()
                .filter(item -> item.id().equals(contractId))
                .findFirst()
                .orElseThrow(ContractLifecycleService::notFound);
        return new ContractDetailView(
                contract,
                versions(contractId),
                lifecycle(contractId),
                archives(contractId)
        );
    }

    private List<ContractVersionView> versions(UUID contractId) {
        return jdbcClient.sql("""
                        SELECT cv.id, cv.version_number, cv.status, cv.summary,
                               primary_dv.document_id AS primary_document_id,
                               cv.primary_document_version_id,
                               primary_dv.original_filename AS primary_filename,
                               primary_dv.sha256 AS primary_sha256,
                               signed_dv.document_id AS signed_document_id,
                               cv.signed_document_version_id,
                               signed_dv.original_filename AS signed_filename,
                               signed_dv.sha256 AS signed_sha256,
                               cv.signature_status, cv.created_by,
                               creator.display_name AS created_by_name, cv.created_at,
                               cv.finalized_by, finalizer.display_name AS finalized_by_name,
                               cv.finalized_at, cv.signed_by,
                               signer.display_name AS signed_by_name, cv.signed_at
                        FROM contract_versions cv
                        JOIN users creator ON creator.id = cv.created_by
                        LEFT JOIN users finalizer ON finalizer.id = cv.finalized_by
                        LEFT JOIN users signer ON signer.id = cv.signed_by
                        LEFT JOIN document_versions primary_dv
                          ON primary_dv.id = cv.primary_document_version_id
                        LEFT JOIN document_versions signed_dv
                          ON signed_dv.id = cv.signed_document_version_id
                        WHERE cv.contract_id = :contractId
                        ORDER BY cv.version_number DESC
                        """)
                .param("contractId", contractId)
                .query((rs, rowNum) -> new ContractVersionView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("version_number"),
                        rs.getString("status"),
                        rs.getString("summary"),
                        rs.getObject("primary_document_id", UUID.class),
                        rs.getObject("primary_document_version_id", UUID.class),
                        rs.getString("primary_filename"),
                        rs.getString("primary_sha256"),
                        rs.getObject("signed_document_id", UUID.class),
                        rs.getObject("signed_document_version_id", UUID.class),
                        rs.getString("signed_filename"),
                        rs.getString("signed_sha256"),
                        rs.getString("signature_status"),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("finalized_by", UUID.class),
                        rs.getString("finalized_by_name"),
                        instant(rs.getTimestamp("finalized_at")),
                        rs.getObject("signed_by", UUID.class),
                        rs.getString("signed_by_name"),
                        instant(rs.getTimestamp("signed_at"))
                ))
                .list();
    }

    private List<ContractLifecycleEventView> lifecycle(UUID contractId) {
        return jdbcClient.sql("""
                        SELECT event.id, event.action, event.actor_user_id,
                               actor.display_name AS actor_name,
                               event.from_contract_status, event.to_contract_status,
                               event.contract_version_id, event.document_version_id,
                               event.comment, event.occurred_at
                        FROM contract_lifecycle_events event
                        JOIN users actor ON actor.id = event.actor_user_id
                        WHERE event.contract_id = :contractId
                        ORDER BY event.occurred_at, event.id
                        """)
                .param("contractId", contractId)
                .query((rs, rowNum) -> new ContractLifecycleEventView(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("from_contract_status"),
                        rs.getString("to_contract_status"),
                        rs.getObject("contract_version_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("comment"),
                        rs.getTimestamp("occurred_at").toInstant()
                ))
                .list();
    }

    private List<ContractArchiveLinkView> archives(UUID contractId) {
        return jdbcClient.sql("""
                        SELECT av.id, av.archive_number, av.title, av.status,
                               av.matter_id, m.matter_number,
                               d.id AS document_id, ai.document_version_id,
                               ai.sequence_number
                        FROM documents d
                        JOIN archive_items ai ON ai.document_id = d.id
                        JOIN archive_volumes av ON av.id = ai.archive_volume_id
                        LEFT JOIN matters m ON m.id = av.matter_id
                        WHERE d.contract_id = :contractId
                          AND d.deleted_at IS NULL
                        ORDER BY av.created_at DESC, ai.sequence_number
                        """)
                .param("contractId", contractId)
                .query((rs, rowNum) -> new ContractArchiveLinkView(
                        rs.getObject("id", UUID.class),
                        rs.getString("archive_number"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getInt("sequence_number")
                ))
                .list();
    }

    private ContractState lockContract(RequestActor actor, UUID contractId) {
        requireContractAccess(actor, contractId);
        return jdbcClient.sql("""
                        SELECT id, status
                        FROM contracts
                        WHERE id = :contractId AND organization_id = :organizationId
                          AND deleted_at IS NULL
                        FOR UPDATE
                        """)
                .param("contractId", contractId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new ContractState(
                        rs.getObject("id", UUID.class),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(ContractLifecycleService::notFound);
    }

    private ContractVersionState lockVersion(UUID contractId, UUID versionId) {
        return jdbcClient.sql("""
                        SELECT id, status, signature_status, primary_document_version_id
                        FROM contract_versions
                        WHERE id = :versionId AND contract_id = :contractId
                        FOR UPDATE
                        """)
                .param("versionId", versionId)
                .param("contractId", contractId)
                .query((rs, rowNum) -> new ContractVersionState(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("signature_status"),
                        rs.getObject("primary_document_version_id", UUID.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "CONTRACT_VERSION_NOT_FOUND",
                        "合同版本不存在或不属于该合同",
                        HttpStatus.NOT_FOUND
                ));
    }

    private DocumentVersionRef requireContractDocumentVersion(
            RequestActor actor,
            UUID contractId,
            UUID documentVersionId,
            String requiredDocumentType
    ) {
        DocumentVersionRef ref = jdbcClient.sql("""
                        SELECT dv.id, dv.document_id, dv.sha256, d.document_type
                        FROM document_versions dv
                        JOIN documents d ON d.id = dv.document_id
                        WHERE dv.id = :documentVersionId
                          AND d.contract_id = :contractId
                          AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                          AND dv.ingestion_status = 'AVAILABLE'
                          AND (:anyType OR d.document_type = :documentType)
                        """)
                .param("documentVersionId", documentVersionId)
                .param("contractId", contractId)
                .param("organizationId", actor.organizationId())
                .param("anyType", requiredDocumentType == null)
                .param("documentType", requiredDocumentType == null ? "" : requiredDocumentType)
                .query((rs, rowNum) -> new DocumentVersionRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("sha256"),
                        rs.getString("document_type")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "CONTRACT_DOCUMENT_VERSION_INVALID",
                        "合同文件版本不存在、未通过安全扫描或文件类型不正确",
                        HttpStatus.BAD_REQUEST
                ));
        documentAccessService.requireDocumentRead(actor, ref.documentId(), false);
        return ref;
    }

    private void requireContractAccess(RequestActor actor, UUID contractId) {
        Boolean allowed = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM contracts c
                            WHERE c.id = :contractId
                              AND c.organization_id = :organizationId
                              AND c.deleted_at IS NULL
                              AND (
                                EXISTS (
                                  SELECT 1 FROM contract_members cm
                                  WHERE cm.contract_id = c.id AND cm.user_id = :userId
                                )
                                OR EXISTS (
                                  SELECT 1 FROM user_roles ur
                                  JOIN role_permissions rp ON rp.role_id = ur.role_id
                                  JOIN permissions permission
                                    ON permission.id = rp.permission_id
                                  WHERE ur.user_id = :userId
                                    AND permission.code = 'CONTRACT_VIEW_ALL'
                                )
                              )
                        )
                        """)
                .param("contractId", contractId)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(allowed)) {
            throw notFound();
        }
    }

    private void recordEvent(
            RequestActor actor,
            UUID contractId,
            UUID versionId,
            String action,
            String fromStatus,
            String toStatus,
            UUID documentVersionId,
            String comment
    ) {
        jdbcClient.sql("""
                        INSERT INTO contract_lifecycle_events
                            (organization_id, contract_id, contract_version_id,
                             action, actor_user_id, from_contract_status,
                             to_contract_status, document_version_id, comment)
                        VALUES
                            (:organizationId, :contractId, :versionId,
                             :action, :actorId, :fromStatus,
                             :toStatus, :documentVersionId, :comment)
                        """)
                .param("organizationId", actor.organizationId())
                .param("contractId", contractId)
                .param("versionId", versionId)
                .param("action", action)
                .param("actorId", actor.userId())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("documentVersionId", documentVersionId)
                .param("comment", comment)
                .update();
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "CONTRACT_CONTEXT_INVALID",
                "合同不存在或当前用户无权访问",
                HttpStatus.NOT_FOUND
        );
    }

    private static BusinessException stateChanged() {
        return new BusinessException(
                "CONTRACT_STATE_CHANGED",
                "合同或版本状态已变化，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ContractState(UUID id, String status) {}

    private record ContractVersionState(
            UUID id,
            String status,
            String signatureStatus,
            UUID primaryDocumentVersionId
    ) {}

    private record DocumentVersionRef(
            UUID id,
            UUID documentId,
            String sha256,
            String documentType
    ) {}
}
