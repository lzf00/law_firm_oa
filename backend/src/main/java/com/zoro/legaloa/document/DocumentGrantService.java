package com.zoro.legaloa.document;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentController.DocumentGrantRequest;
import com.zoro.legaloa.document.DocumentController.DocumentGrantView;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentGrantService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;
    private final DocumentAccessService accessService;
    private final AuditService auditService;

    public DocumentGrantService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            OfficeAccessService officeAccessService,
            DocumentAccessService accessService,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.officeAccessService = officeAccessService;
        this.accessService = accessService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DocumentGrantView> list(UUID documentId) {
        RequestActor actor = actorProvider.current();
        requireManage(actor, documentId);
        return jdbcClient.sql("""
                        SELECT dg.id, dg.user_id, u.username, u.display_name,
                               dg.permission, dg.granted_by, grantor.display_name AS granted_by_name,
                               dg.expires_at, dg.created_at,
                               (dg.expires_at IS NULL OR dg.expires_at > now()) AS active
                        FROM document_grants dg
                        JOIN documents d ON d.id = dg.document_id
                        JOIN users u ON u.id = dg.user_id
                        JOIN users grantor ON grantor.id = dg.granted_by
                        WHERE dg.document_id = :documentId
                          AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                        ORDER BY active DESC, u.display_name, dg.permission
                        """)
                .param("documentId", documentId)
                .param("organizationId", actor.organizationId())
                .query(DocumentGrantService::mapGrant)
                .list();
    }

    @Transactional
    public DocumentGrantView grant(UUID documentId, DocumentGrantRequest request) {
        RequestActor actor = actorProvider.current();
        requireManage(actor, documentId);
        if (request.userId() == null) {
            throw invalid("DOCUMENT_GRANT_USER_REQUIRED", "请选择授权人员");
        }
        String permission;
        try {
            permission = DocumentGrantPolicy.normalizePermission(request.permission());
            DocumentGrantPolicy.requireFutureExpiry(request.expiresAt(), Instant.now());
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage(), "授权类型或有效期不正确");
        }
        boolean userExists = Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM users
                          WHERE id = :userId AND organization_id = :organizationId
                            AND status = 'ACTIVE' AND deleted_at IS NULL
                        )
                        """)
                .param("userId", request.userId())
                .param("organizationId", actor.organizationId())
                .query(Boolean.class).single());
        if (!userExists) {
            throw invalid("DOCUMENT_GRANT_USER_INVALID", "授权人员不存在或已停用");
        }
        UUID id = jdbcClient.sql("""
                        INSERT INTO document_grants
                            (document_id, user_id, permission, granted_by, expires_at)
                        VALUES (:documentId, :userId, :permission, :grantedBy, :expiresAt)
                        ON CONFLICT (document_id, user_id, permission) DO UPDATE
                        SET granted_by = EXCLUDED.granted_by,
                            expires_at = EXCLUDED.expires_at,
                            created_at = now()
                        RETURNING id
                        """)
                .param("documentId", documentId)
                .param("userId", request.userId())
                .param("permission", permission)
                .param("grantedBy", actor.userId())
                .param("expiresAt", request.expiresAt() == null
                        ? null : java.sql.Timestamp.from(request.expiresAt()))
                .query(UUID.class).single();
        auditService.record(
                actor, "DOCUMENT_GRANT_UPSERT", "DOCUMENT", documentId,
                "SUCCESS", null, Map.of(
                        "grantId", id,
                        "granteeUserId", request.userId(),
                        "permission", permission
                )
        );
        return find(id, documentId, actor.organizationId());
    }

    @Transactional
    public void revoke(UUID documentId, UUID grantId) {
        RequestActor actor = actorProvider.current();
        requireManage(actor, documentId);
        DocumentGrantView existing = find(grantId, documentId, actor.organizationId());
        int deleted = jdbcClient.sql("""
                        DELETE FROM document_grants
                        WHERE id = :grantId AND document_id = :documentId
                        """)
                .param("grantId", grantId)
                .param("documentId", documentId)
                .update();
        if (deleted != 1) {
            throw new BusinessException(
                    "DOCUMENT_GRANT_NOT_FOUND", "授权记录不存在", HttpStatus.NOT_FOUND
            );
        }
        auditService.record(
                actor, "DOCUMENT_GRANT_REVOKE", "DOCUMENT", documentId,
                "SUCCESS", null, Map.of(
                        "grantId", grantId,
                        "granteeUserId", existing.userId(),
                        "permission", existing.permission()
                )
        );
    }

    private void requireManage(RequestActor actor, UUID documentId) {
        if (authorizationService.hasPermission(actor, "DOCUMENT_GOVERNANCE_MANAGE")) {
            DocumentScope scope = jdbcClient.sql("""
                            SELECT matter_id, contract_id
                            FROM documents
                            WHERE id = :documentId AND organization_id = :organizationId
                              AND deleted_at IS NULL
                            """)
                    .param("documentId", documentId)
                    .param("organizationId", actor.organizationId())
                    .query((rs, rowNum) -> new DocumentScope(
                            rs.getObject("matter_id", UUID.class),
                            rs.getObject("contract_id", UUID.class)
                    ))
                    .optional()
                    .orElse(null);
            if (scope != null && (
                    officeAccessService.scope(actor).globalAccess()
                    || scope.matterId() != null && officeAccessService.canAccessBusiness(
                            actor, "MATTER", scope.matterId()
                    )
                    || scope.contractId() != null && officeAccessService.canAccessBusiness(
                            actor, "CONTRACT", scope.contractId()
                    )
            )) {
                return;
            }
        }
        accessService.requireDocumentShare(actor, documentId);
    }

    private DocumentGrantView find(UUID id, UUID documentId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT dg.id, dg.user_id, u.username, u.display_name,
                               dg.permission, dg.granted_by, grantor.display_name AS granted_by_name,
                               dg.expires_at, dg.created_at,
                               (dg.expires_at IS NULL OR dg.expires_at > now()) AS active
                        FROM document_grants dg
                        JOIN documents d ON d.id = dg.document_id
                        JOIN users u ON u.id = dg.user_id
                        JOIN users grantor ON grantor.id = dg.granted_by
                        WHERE dg.id = :id AND dg.document_id = :documentId
                          AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                        """)
                .param("id", id)
                .param("documentId", documentId)
                .param("organizationId", organizationId)
                .query(DocumentGrantService::mapGrant)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "DOCUMENT_GRANT_NOT_FOUND", "授权记录不存在", HttpStatus.NOT_FOUND
                ));
    }

    private static DocumentGrantView mapGrant(
            java.sql.ResultSet rs,
            int rowNumber
    ) throws java.sql.SQLException {
        var expiresAt = rs.getTimestamp("expires_at");
        return new DocumentGrantView(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("permission"),
                rs.getObject("granted_by", UUID.class),
                rs.getString("granted_by_name"),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("active")
        );
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }

    private record DocumentScope(UUID matterId, UUID contractId) {}
}
