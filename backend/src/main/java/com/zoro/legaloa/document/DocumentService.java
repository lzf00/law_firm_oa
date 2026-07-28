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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public DocumentService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            DocumentAccessService accessService,
            ObjectStorageService storageService,
            AuditService auditService,
            DocumentSecurityService securityService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.accessService = accessService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.securityService = securityService;
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
                               dv.original_filename, dv.size_bytes, dv.created_at
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

        if (request.documentId() != null) {
            accessService.requireDocumentRead(actor, request.documentId(), true);
        }
        UUID uploadId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        String extension = safeExtension(request.originalFilename());
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
                .param("filename", request.originalFilename().trim())
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

    @Transactional(noRollbackFor = BusinessException.class)
    public DocumentView complete(UUID uploadId) {
        RequestActor actor = actorProvider.current();
        Map<String, Object> upload = jdbcClient.sql("""
                        SELECT *
                        FROM document_upload_sessions
                        WHERE id = :id AND organization_id = :organizationId
                          AND created_by = :createdBy AND status = 'PENDING'
                          AND expires_at > now()
                        FOR UPDATE
                        """)
                .param("id", uploadId)
                .param("organizationId", actor.organizationId())
                .param("createdBy", actor.userId())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "UPLOAD_SESSION_INVALID", "上传会话不存在、已完成或已过期", HttpStatus.CONFLICT
                ));

        UUID matterId = (UUID) upload.get("matter_id");
        UUID contractId = (UUID) upload.get("contract_id");
        accessService.requireContextWrite(actor, matterId, contractId);
        ObjectStorageService.StoredObject stored = storageService.stat((String) upload.get("object_key"));
        long expectedSize = ((Number) upload.get("expected_size")).longValue();
        if (stored.size() != expectedSize) {
            jdbcClient.sql("UPDATE document_upload_sessions SET status = 'FAILED' WHERE id = :id")
                    .param("id", uploadId)
                    .update();
            throw new BusinessException(
                    "UPLOAD_SIZE_MISMATCH", "上传文件大小与登记信息不一致", HttpStatus.CONFLICT
            );
        }
        String actualSha256 = storageService.sha256((String) upload.get("object_key"));
        String expectedSha256 = ((String) upload.get("expected_sha256")).trim();
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            jdbcClient.sql("UPDATE document_upload_sessions SET status = 'FAILED' WHERE id = :id")
                    .param("id", uploadId)
                    .update();
            throw new BusinessException(
                    "UPLOAD_HASH_MISMATCH",
                    "上传文件摘要校验失败，文件可能不完整或已被替换",
                    HttpStatus.CONFLICT
            );
        }

        UUID documentId = (UUID) upload.get("document_id");
        if (documentId == null) {
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
                             content_type, size_bytes, sha256, created_by)
                        VALUES
                            (:documentId, :versionNumber, :objectKey, :filename,
                             :contentType, :size, :sha256, :createdBy)
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
        jdbcClient.sql("""
                        UPDATE documents SET current_version_id = :versionId, updated_at = now()
                        WHERE id = :documentId
                        """)
                .param("versionId", versionId)
                .param("documentId", documentId)
                .update();
        jdbcClient.sql("""
                        UPDATE document_upload_sessions
                        SET status = 'COMPLETED', completed_at = now(), document_id = :documentId
                        WHERE id = :id
                        """)
                .param("documentId", documentId)
                .param("id", uploadId)
                .update();
        auditService.success(actor, "DOCUMENT_UPLOAD", "DOCUMENT", documentId);
        UUID finalDocumentId = documentId;
        return findView(finalDocumentId);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public DownloadTicket download(UUID documentId, UUID versionId) {
        RequestActor actor = actorProvider.current();
        accessService.requireDocumentRead(actor, documentId, true);
        String objectKey = jdbcClient.sql("""
                        SELECT object_key FROM document_versions
                        WHERE id = :versionId AND document_id = :documentId
                        """)
                .param("versionId", versionId)
                .param("documentId", documentId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_VERSION_NOT_FOUND", "文件版本不存在", HttpStatus.NOT_FOUND
                ));
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

    private DocumentView findView(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT d.id, d.matter_id, d.contract_id, d.logical_name, d.document_type,
                               d.confidentiality_level, d.current_version_id,
                               dv.version_number, dv.version_status, dv.signature_status,
                               dv.original_filename, dv.size_bytes, dv.created_at
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
                createdAt == null ? null : createdAt.toInstant()
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
}
