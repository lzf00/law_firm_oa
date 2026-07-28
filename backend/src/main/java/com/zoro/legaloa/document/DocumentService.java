package com.zoro.legaloa.document;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentController.DocumentView;
import com.zoro.legaloa.document.DocumentController.DownloadTicket;
import com.zoro.legaloa.document.DocumentController.InitiateUploadRequest;
import com.zoro.legaloa.document.DocumentController.UploadTicket;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentService {
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "image/jpeg",
            "image/png",
            "text/plain",
            "application/zip"
    );
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final DocumentAccessService accessService;
    private final ObjectStorageService storageService;
    private final AuditService auditService;
    private final DocumentSecurityService securityService;
    private final AntivirusScanner antivirusScanner;
    private final TransactionTemplate transactionTemplate;

    public DocumentService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            DocumentAccessService accessService,
            ObjectStorageService storageService,
            AuditService auditService,
            DocumentSecurityService securityService,
            AntivirusScanner antivirusScanner,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.accessService = accessService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.securityService = securityService;
        this.antivirusScanner = antivirusScanner;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> list(UUID matterId, UUID contractId) {
        requireExactlyOneContext(matterId, contractId);
        RequestActor actor = actorProvider.current();
        accessService.requireContextRead(actor, matterId, contractId);
        return jdbcClient.sql("""
                        SELECT d.id, d.matter_id, d.contract_id, d.logical_name, d.document_type,
                               d.confidentiality_level, d.current_version_id,
                               dv.version_number, dv.version_status, dv.signature_status,
                               dv.original_filename, dv.size_bytes, dv.created_at,
                               dv.ingestion_status, dv.scan_failure_reason, dv.scan_completed_at
                        FROM documents d
                        LEFT JOIN document_versions dv ON dv.id = d.current_version_id
                        WHERE d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                          AND (:byMatter AND d.matter_id = :matterId
                               OR :byContract AND d.contract_id = :contractId)
                        ORDER BY d.updated_at DESC
                        """)
                .param("organizationId", actor.organizationId())
                .param("byMatter", matterId != null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .param("byContract", contractId != null)
                .param("contractId", contractId == null ? new UUID(0, 0) : contractId)
                .query(DocumentService::mapView)
                .list();
    }

    @Transactional
    public UploadTicket initiate(InitiateUploadRequest request) {
        requireExactlyOneContext(request.matterId(), request.contractId());
        if (!ALLOWED_CONTENT_TYPES.contains(request.contentType())) {
            throw new BusinessException(
                    "FILE_TYPE_NOT_ALLOWED", "该文件类型不允许上传", HttpStatus.BAD_REQUEST
            );
        }
        RequestActor actor = actorProvider.current();
        accessService.requireContextWrite(actor, request.matterId(), request.contractId());
        String normalizedFilename;
        try {
            normalizedFilename = FileInspectionPolicy.normalizeFilename(request.originalFilename());
        } catch (FileInspectionPolicy.InspectionException exception) {
            throw new BusinessException(
                    exception.code(), "文件名不安全或不符合长度限制", HttpStatus.BAD_REQUEST
            );
        }

        if (request.documentId() != null) {
            accessService.requireDocumentRead(actor, request.documentId(), true);
        }
        UUID uploadId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        String extension = safeExtension(normalizedFilename);
        String context = request.matterId() != null ? "matters/" + request.matterId()
                : "contracts/" + request.contractId();
        String objectKey = actor.organizationId() + "/" + context + "/uploads/" + uploadId + extension;

        jdbcClient.sql("""
                        INSERT INTO document_upload_sessions
                            (id, organization_id, document_id, matter_id, contract_id,
                             logical_name, document_type, original_filename, content_type,
                             expected_size, expected_sha256, object_key, created_by, expires_at)
                        VALUES
                            (:id, :organizationId, :documentId, :matterId, :contractId,
                             :logicalName, :documentType, :filename, :contentType,
                             :size, :sha256, :objectKey, :createdBy, :expiresAt)
                        """)
                .param("id", uploadId)
                .param("organizationId", actor.organizationId())
                .param("documentId", request.documentId())
                .param("matterId", request.matterId())
                .param("contractId", request.contractId())
                .param("logicalName", request.logicalName().trim())
                .param("documentType", request.documentType().trim())
                .param("filename", normalizedFilename)
                .param("contentType", request.contentType())
                .param("size", request.sizeBytes())
                .param("sha256", request.sha256().toLowerCase())
                .param("objectKey", objectKey)
                .param("createdBy", actor.userId())
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();

        String uploadUrl = storageService.presignedUpload(objectKey, request.contentType());
        return new UploadTicket(uploadId, uploadUrl, "PUT", request.contentType(), expiresAt);
    }

    public DocumentView complete(UUID uploadId) {
        RequestActor actor = actorProvider.current();
        Map<String, Object> upload = claimUpload(actor, uploadId);

        UUID matterId = (UUID) upload.get("matter_id");
        UUID contractId = (UUID) upload.get("contract_id");
        accessService.requireContextWrite(actor, matterId, contractId);
        String objectKey = (String) upload.get("object_key");
        ObjectStorageService.StoredObject stored = storageService.stat(objectKey);
        long expectedSize = ((Number) upload.get("expected_size")).longValue();
        if (stored.size() != expectedSize) {
            finishUploadFailure(uploadId, "FAILED");
            throw new BusinessException(
                    "UPLOAD_SIZE_MISMATCH", "上传文件大小与登记信息不一致", HttpStatus.CONFLICT
            );
        }
        String actualSha256 = storageService.sha256(objectKey);
        String expectedSha256 = ((String) upload.get("expected_sha256")).trim();
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            finishUploadFailure(uploadId, "FAILED");
            throw new BusinessException(
                    "UPLOAD_HASH_MISMATCH",
                    "上传文件摘要校验失败，文件可能不完整或已被替换",
                    HttpStatus.CONFLICT
            );
        }

        PersistedVersion persisted = transactionTemplate.execute(status -> persistQuarantinedVersion(
                actor, uploadId, upload
        ));
        if (persisted == null) {
            throw new IllegalStateException("Could not persist quarantined document version");
        }
        updateIngestionStatus(persisted.versionId(), "SCANNING", null, null, null, false);

        FileInspectionPolicy.InspectionResult inspection;
        try (var content = storageService.open(objectKey)) {
            inspection = FileInspectionPolicy.inspect(
                    (String) upload.get("original_filename"),
                    (String) upload.get("content_type"),
                    expectedSize,
                    content
            );
        } catch (FileInspectionPolicy.InspectionException exception) {
            rejectIngestion(
                    actor, uploadId, persisted, "REJECTED", exception.code(), exception.getMessage()
            );
            throw new BusinessException(
                    exception.code(), "文件内容未通过安全策略检查", HttpStatus.UNPROCESSABLE_ENTITY
            );
        } catch (IOException exception) {
            rejectIngestion(
                    actor, uploadId, persisted, "FAILED", "FILE_INSPECTION_FAILED",
                    "Could not inspect uploaded content"
            );
            throw new BusinessException(
                    "FILE_INSPECTION_FAILED", "文件安全检查失败", HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        AntivirusScanner.ScanResult scanResult;
        try (var content = storageService.open(objectKey)) {
            scanResult = antivirusScanner.scan(
                    content,
                    new AntivirusScanner.ScanMetadata(
                            inspection.normalizedFilename(),
                            inspection.detectedContentType(),
                            expectedSize
                    )
            );
        } catch (IOException | RuntimeException exception) {
            scanResult = AntivirusScanner.ScanResult.failed("Scanner communication failed");
        }
        if (scanResult.status() == AntivirusScanner.Status.INFECTED) {
            rejectIngestion(
                    actor, uploadId, persisted, "REJECTED", scanResult.signature(),
                    "Malware signature detected"
            );
            throw new BusinessException(
                    "MALWARE_DETECTED", "文件未通过病毒扫描", HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
        if (scanResult.status() != AntivirusScanner.Status.CLEAN) {
            rejectIngestion(
                    actor, uploadId, persisted, "FAILED", "SCANNER_FAILED", scanResult.detail()
            );
            throw new BusinessException(
                    "SCANNER_FAILED", "病毒扫描服务不可用，文件保持隔离", HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        transactionTemplate.executeWithoutResult(status -> {
            updateIngestionStatus(
                    persisted.versionId(), "AVAILABLE", inspection.detectedContentType(),
                    antivirusScanner.provider(), "CLEAN", true
            );
            jdbcClient.sql("""
                            UPDATE documents SET current_version_id = :versionId, updated_at = now()
                            WHERE id = :documentId
                            """)
                    .param("versionId", persisted.versionId())
                    .param("documentId", persisted.documentId())
                    .update();
            jdbcClient.sql("""
                            UPDATE document_upload_sessions
                            SET status = 'COMPLETED', completed_at = now()
                            WHERE id = :id
                            """)
                    .param("id", uploadId)
                    .update();
        });
        auditService.record(
                actor, "DOCUMENT_SCAN", "DOCUMENT", persisted.documentId(),
                "SUCCESS", null, Map.of(
                        "documentVersionId", persisted.versionId(),
                        "provider", antivirusScanner.provider(),
                        "result", "CLEAN"
                )
        );
        auditService.success(actor, "DOCUMENT_UPLOAD", "DOCUMENT", persisted.documentId());
        return findView(persisted.documentId());
    }

    private Map<String, Object> claimUpload(RequestActor actor, UUID uploadId) {
        Map<String, Object> upload = transactionTemplate.execute(status -> jdbcClient.sql("""
                        UPDATE document_upload_sessions
                        SET status = 'SCANNING'
                        WHERE id = :id AND organization_id = :organizationId
                          AND created_by = :createdBy AND status = 'PENDING'
                          AND expires_at > now()
                        RETURNING *
                        """)
                .param("id", uploadId)
                .param("organizationId", actor.organizationId())
                .param("createdBy", actor.userId())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null));
        if (upload == null) {
            throw new BusinessException(
                    "UPLOAD_SESSION_INVALID", "上传会话不存在、已完成或已过期", HttpStatus.CONFLICT
            );
        }
        return upload;
    }

    private PersistedVersion persistQuarantinedVersion(
            RequestActor actor,
            UUID uploadId,
            Map<String, Object> upload
    ) {
        UUID documentId = (UUID) upload.get("document_id");
        UUID matterId = (UUID) upload.get("matter_id");
        UUID contractId = (UUID) upload.get("contract_id");
        long expectedSize = ((Number) upload.get("expected_size")).longValue();
        boolean newDocument = documentId == null;
        if (newDocument) {
            documentId = jdbcClient.sql("""
                            INSERT INTO documents
                                (organization_id, matter_id, contract_id, logical_name,
                                 document_type, created_by)
                            VALUES
                                (:organizationId, :matterId, :contractId, :logicalName,
                                 :documentType, :createdBy)
                            RETURNING id
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("matterId", matterId)
                    .param("contractId", contractId)
                    .param("logicalName", upload.get("logical_name"))
                    .param("documentType", upload.get("document_type"))
                    .param("createdBy", actor.userId())
                    .query(UUID.class)
                    .single();
        } else {
            accessService.requireDocumentRead(actor, documentId, true);
            jdbcClient.sql("SELECT id FROM documents WHERE id = :id FOR UPDATE")
                    .param("id", documentId)
                    .query(UUID.class)
                    .single();
        }

        Integer nextVersion = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_number), 0) + 1
                        FROM document_versions WHERE document_id = :documentId
                        """)
                .param("documentId", documentId)
                .query(Integer.class)
                .single();
        UUID versionId = jdbcClient.sql("""
                        INSERT INTO document_versions
                            (document_id, version_number, object_key, original_filename,
                             content_type, size_bytes, sha256, created_by,
                             ingestion_status, scan_started_at)
                        VALUES
                            (:documentId, :versionNumber, :objectKey, :filename,
                             :contentType, :size, :sha256, :createdBy,
                             'QUARANTINED', now())
                        RETURNING id
                        """)
                .param("documentId", documentId)
                .param("versionNumber", nextVersion)
                .param("objectKey", upload.get("object_key"))
                .param("filename", upload.get("original_filename"))
                .param("contentType", upload.get("content_type"))
                .param("size", expectedSize)
                .param("sha256", upload.get("expected_sha256"))
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .single();
        if (newDocument) {
            jdbcClient.sql("""
                            UPDATE documents SET current_version_id = :versionId, updated_at = now()
                            WHERE id = :documentId
                            """)
                    .param("versionId", versionId)
                    .param("documentId", documentId)
                    .update();
        }
        jdbcClient.sql("""
                        UPDATE document_upload_sessions
                        SET document_id = :documentId
                        WHERE id = :id
                        """)
                .param("documentId", documentId)
                .param("id", uploadId)
                .update();
        return new PersistedVersion(documentId, versionId);
    }

    private void rejectIngestion(
            RequestActor actor,
            UUID uploadId,
            PersistedVersion persisted,
            String status,
            String reason,
            String detail
    ) {
        transactionTemplate.executeWithoutResult(transaction -> {
            jdbcClient.sql("""
                            UPDATE document_versions
                            SET ingestion_status = :status,
                                scan_provider = :provider,
                                scan_result = :reason,
                                scan_failure_reason = :detail,
                                scan_completed_at = now()
                            WHERE id = :id
                            """)
                    .param("status", status)
                    .param("provider", antivirusScanner.provider())
                    .param("reason", reason)
                    .param("detail", detail)
                    .param("id", persisted.versionId())
                    .update();
            jdbcClient.sql("""
                            UPDATE document_upload_sessions
                            SET status = :status, completed_at = now()
                            WHERE id = :id
                            """)
                    .param("status", "REJECTED".equals(status) ? "REJECTED" : "FAILED")
                    .param("id", uploadId)
                    .update();
        });
        auditService.record(
                actor, "DOCUMENT_SCAN", "DOCUMENT", persisted.documentId(),
                "REJECTED".equals(status) ? "DENIED" : "FAILED",
                reason, Map.of(
                        "documentVersionId", persisted.versionId(),
                        "provider", antivirusScanner.provider(),
                        "detail", detail == null ? "" : detail
                )
        );
    }

    private void updateIngestionStatus(
            UUID versionId,
            String status,
            String detectedContentType,
            String provider,
            String result,
            boolean completed
    ) {
        jdbcClient.sql("""
                        UPDATE document_versions
                        SET ingestion_status = :status,
                            detected_content_type = COALESCE(:detectedContentType, detected_content_type),
                            scan_provider = COALESCE(:provider, scan_provider),
                            scan_result = COALESCE(:result, scan_result),
                            scan_completed_at = CASE WHEN :completed THEN now() ELSE scan_completed_at END
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("detectedContentType", detectedContentType)
                .param("provider", provider)
                .param("result", result)
                .param("completed", completed)
                .param("id", versionId)
                .update();
    }

    private void finishUploadFailure(UUID uploadId, String status) {
        jdbcClient.sql("""
                        UPDATE document_upload_sessions
                        SET status = :status, completed_at = now()
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("id", uploadId)
                .update();
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public DownloadTicket download(UUID documentId, UUID versionId) {
        RequestActor actor = actorProvider.current();
        try {
            accessService.requireDocumentRead(actor, documentId, true);
        } catch (BusinessException exception) {
            auditService.record(
                    actor, "DOCUMENT_DOWNLOAD_URL_CREATE", "DOCUMENT", documentId,
                    "DENIED", exception.code(), Map.of("documentVersionId", versionId)
            );
            throw exception;
        }
        String objectKey = availableObjectKey(documentId, versionId, actor, "DOCUMENT_DOWNLOAD_URL_CREATE");
        DocumentSecurityPolicy.Decision securityDecision =
                securityService.beforeDownload(actor, documentId, versionId);
        securityService.recordAllowed(actor, documentId, versionId, securityDecision);
        auditService.record(
                actor, "DOCUMENT_DOWNLOAD_URL_CREATE", "DOCUMENT", documentId,
                "SUCCESS", null, Map.of("documentVersionId", versionId)
        );
        Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        return new DownloadTicket(storageService.presignedDownload(objectKey), expiresAt);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public DownloadTicket preview(UUID documentId, UUID versionId) {
        RequestActor actor = actorProvider.current();
        try {
            accessService.requireDocumentRead(actor, documentId, false);
        } catch (BusinessException exception) {
            auditService.record(
                    actor, "DOCUMENT_PREVIEW_URL_CREATE", "DOCUMENT", documentId,
                    "DENIED", exception.code(), Map.of("documentVersionId", versionId)
            );
            throw exception;
        }
        String objectKey = availableObjectKey(documentId, versionId, actor, "DOCUMENT_PREVIEW_URL_CREATE");
        auditService.record(
                actor, "DOCUMENT_PREVIEW_URL_CREATE", "DOCUMENT", documentId,
                "SUCCESS", null, Map.of("documentVersionId", versionId)
        );
        Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        return new DownloadTicket(storageService.presignedDownload(objectKey), expiresAt);
    }

    private String availableObjectKey(
            UUID documentId,
            UUID versionId,
            RequestActor actor,
            String auditAction
    ) {
        var objectKey = jdbcClient.sql("""
                        SELECT object_key FROM document_versions
                        WHERE id = :versionId AND document_id = :documentId
                          AND ingestion_status = 'AVAILABLE'
                        """)
                .param("versionId", versionId)
                .param("documentId", documentId)
                .query(String.class)
                .optional();
        if (objectKey.isEmpty()) {
            auditService.record(
                    actor, auditAction, "DOCUMENT", documentId,
                    "DENIED", "DOCUMENT_NOT_AVAILABLE", Map.of("documentVersionId", versionId)
            );
            throw new BusinessException(
                    "DOCUMENT_NOT_AVAILABLE", "文件仍在隔离扫描或已被拒绝", HttpStatus.CONFLICT
            );
        }
        return objectKey.get();
    }

    private DocumentView findView(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT d.id, d.matter_id, d.contract_id, d.logical_name, d.document_type,
                               d.confidentiality_level, d.current_version_id,
                               dv.version_number, dv.version_status, dv.signature_status,
                               dv.original_filename, dv.size_bytes, dv.created_at,
                               dv.ingestion_status, dv.scan_failure_reason, dv.scan_completed_at
                        FROM documents d
                        JOIN document_versions dv ON dv.id = d.current_version_id
                        WHERE d.id = :id
                        """)
                .param("id", documentId)
                .query(DocumentService::mapView)
                .single();
    }

    private static DocumentView mapView(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        var createdAt = rs.getTimestamp("created_at");
        return new DocumentView(
                rs.getObject("id", UUID.class),
                rs.getObject("matter_id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                rs.getString("logical_name"),
                rs.getString("document_type"),
                rs.getString("confidentiality_level"),
                rs.getObject("current_version_id", UUID.class),
                rs.getObject("version_number", Integer.class),
                rs.getString("version_status"),
                rs.getString("signature_status"),
                rs.getString("original_filename"),
                rs.getLong("size_bytes"),
                createdAt == null ? null : createdAt.toInstant(),
                rs.getString("ingestion_status"),
                rs.getString("scan_failure_reason"),
                rs.getTimestamp("scan_completed_at") == null
                        ? null
                        : rs.getTimestamp("scan_completed_at").toInstant()
        );
    }

    private static void requireExactlyOneContext(UUID matterId, UUID contractId) {
        if ((matterId == null) == (contractId == null)) {
            throw new BusinessException(
                    "DOCUMENT_CONTEXT_INVALID",
                    "文件必须且只能归属一个案件或合同",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private static String safeExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(index).toLowerCase();
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    private record PersistedVersion(UUID documentId, UUID versionId) {}
}
