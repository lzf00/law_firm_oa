package com.zoro.legaloa.document;

import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DocumentAccessService {
    private final JdbcClient jdbcClient;

    public DocumentAccessService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void requireContextWrite(RequestActor actor, UUID matterId, UUID contractId) {
        Boolean allowed;
        if (matterId != null) {
            allowed = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM matters m
                                JOIN matter_members mm ON mm.matter_id = m.id
                                WHERE m.id = :matterId
                                  AND m.organization_id = :organizationId
                                  AND m.deleted_at IS NULL
                                  AND mm.user_id = :userId AND mm.left_at IS NULL
                                  AND mm.member_role IN ('RESPONSIBLE', 'LEAD', 'COUNSEL')
                            )
                            """)
                    .param("matterId", matterId)
                    .param("organizationId", actor.organizationId())
                    .param("userId", actor.userId())
                    .query(Boolean.class)
                    .single();
        } else {
            allowed = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM contracts c
                                WHERE c.id = :contractId
                                  AND c.organization_id = :organizationId
                                  AND c.deleted_at IS NULL
                                  AND (
                                    EXISTS (
                                      SELECT 1 FROM contract_members cm
                                      WHERE cm.contract_id = c.id
                                        AND cm.user_id = :userId
                                        AND cm.member_role IN ('RESPONSIBLE', 'LEAD', 'COUNSEL')
                                    )
                                    OR EXISTS (
                                      SELECT 1 FROM user_roles ur
                                      JOIN role_permissions rp ON rp.role_id = ur.role_id
                                      JOIN permissions p ON p.id = rp.permission_id
                                      WHERE ur.user_id = :userId
                                        AND p.code = 'CONTRACT_SIGN_ARCHIVE'
                                    )
                                  )
                            )
                            """)
                    .param("contractId", contractId)
                    .param("organizationId", actor.organizationId())
                    .param("userId", actor.userId())
                    .query(Boolean.class)
                    .single();
        }
        if (!Boolean.TRUE.equals(allowed)) {
            throw denied();
        }
    }

    public void requireContextRead(RequestActor actor, UUID matterId, UUID contractId) {
        Boolean allowed;
        if (matterId != null) {
            allowed = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM matters m
                                JOIN matter_members mm ON mm.matter_id = m.id
                                WHERE m.id = :matterId
                                  AND m.organization_id = :organizationId
                                  AND m.deleted_at IS NULL
                                  AND mm.user_id = :userId AND mm.left_at IS NULL
                            )
                            """)
                    .param("matterId", matterId)
                    .param("organizationId", actor.organizationId())
                    .param("userId", actor.userId())
                    .query(Boolean.class)
                    .single();
        } else {
            allowed = jdbcClient.sql("""
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
                                      JOIN permissions p ON p.id = rp.permission_id
                                      WHERE ur.user_id = :userId
                                        AND p.code IN (
                                          'CONTRACT_FINALIZE', 'CONTRACT_SIGN_ARCHIVE'
                                        )
                                    )
                                  )
                            )
                            """)
                    .param("contractId", contractId)
                    .param("organizationId", actor.organizationId())
                    .param("userId", actor.userId())
                    .query(Boolean.class)
                    .single();
        }
        if (!Boolean.TRUE.equals(allowed)) {
            throw denied();
        }
    }

    public void requireDocumentRead(RequestActor actor, UUID documentId, boolean download) {
        Boolean allowed = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM documents d
                            LEFT JOIN matter_members mm
                              ON mm.matter_id = d.matter_id
                             AND mm.user_id = :userId AND mm.left_at IS NULL
                            LEFT JOIN contract_members cm
                              ON cm.contract_id = d.contract_id
                             AND cm.user_id = :userId
                            LEFT JOIN document_grants dg
                              ON dg.document_id = d.id AND dg.user_id = :userId
                             AND (dg.expires_at IS NULL OR dg.expires_at > now())
                            WHERE d.id = :documentId
                              AND d.organization_id = :organizationId
                              AND d.deleted_at IS NULL
                              AND (
                                  (:download = FALSE AND (
                                      mm.user_id IS NOT NULL OR cm.user_id IS NOT NULL
                                      OR dg.permission IN ('PREVIEW', 'DOWNLOAD', 'EDIT', 'SHARE')
                                      OR (
                                        d.contract_id IS NOT NULL
                                        AND EXISTS (
                                          SELECT 1 FROM user_roles ur
                                          JOIN role_permissions rp ON rp.role_id = ur.role_id
                                          JOIN permissions p ON p.id = rp.permission_id
                                          WHERE ur.user_id = :userId
                                            AND p.code IN (
                                              'CONTRACT_FINALIZE', 'CONTRACT_SIGN_ARCHIVE'
                                            )
                                        )
                                      )
                                  ))
                                  OR
                                  (:download = TRUE AND (
                                      mm.can_download = TRUE OR cm.can_download = TRUE
                                      OR dg.permission IN ('DOWNLOAD', 'EDIT', 'SHARE')
                                      OR (
                                        d.contract_id IS NOT NULL
                                        AND EXISTS (
                                          SELECT 1 FROM user_roles ur
                                          JOIN role_permissions rp ON rp.role_id = ur.role_id
                                          JOIN permissions p ON p.id = rp.permission_id
                                          WHERE ur.user_id = :userId
                                            AND p.code IN (
                                              'CONTRACT_FINALIZE', 'CONTRACT_SIGN_ARCHIVE'
                                            )
                                        )
                                      )
                                  ))
                              )
                        )
                        """)
                .param("userId", actor.userId())
                .param("documentId", documentId)
                .param("organizationId", actor.organizationId())
                .param("download", download)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(allowed)) {
            throw denied();
        }
    }

    public void requireDocumentShare(RequestActor actor, UUID documentId) {
        Boolean allowed = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM documents d
                            LEFT JOIN matter_members mm
                              ON mm.matter_id = d.matter_id
                             AND mm.user_id = :userId AND mm.left_at IS NULL
                            LEFT JOIN contract_members cm
                              ON cm.contract_id = d.contract_id
                             AND cm.user_id = :userId
                            LEFT JOIN document_grants dg
                              ON dg.document_id = d.id AND dg.user_id = :userId
                             AND (dg.expires_at IS NULL OR dg.expires_at > now())
                            WHERE d.id = :documentId
                              AND d.organization_id = :organizationId
                              AND d.deleted_at IS NULL
                              AND (
                                mm.member_role IN ('RESPONSIBLE', 'LEAD')
                                OR cm.member_role IN ('RESPONSIBLE', 'LEAD')
                                OR dg.permission = 'SHARE'
                              )
                        )
                        """)
                .param("userId", actor.userId())
                .param("documentId", documentId)
                .param("organizationId", actor.organizationId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(allowed)) {
            throw denied();
        }
    }

    public void requireDocumentEdit(
            RequestActor actor,
            UUID documentId,
            UUID matterId,
            UUID contractId
    ) {
        Boolean allowed = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM documents d
                            LEFT JOIN matter_members mm
                              ON mm.matter_id = d.matter_id
                             AND mm.user_id = :userId AND mm.left_at IS NULL
                            LEFT JOIN contract_members cm
                              ON cm.contract_id = d.contract_id
                             AND cm.user_id = :userId
                            LEFT JOIN document_grants dg
                              ON dg.document_id = d.id AND dg.user_id = :userId
                             AND (dg.expires_at IS NULL OR dg.expires_at > now())
                            WHERE d.id = :documentId
                              AND d.organization_id = :organizationId
                              AND d.deleted_at IS NULL
                              AND (
                                (:byMatter AND d.matter_id = :matterId)
                                OR (:byContract AND d.contract_id = :contractId)
                              )
                              AND (
                                mm.member_role IN ('RESPONSIBLE', 'LEAD', 'COUNSEL')
                                OR cm.member_role IN ('RESPONSIBLE', 'LEAD', 'COUNSEL')
                                OR dg.permission IN ('EDIT', 'SHARE')
                              )
                        )
                        """)
                .param("userId", actor.userId())
                .param("documentId", documentId)
                .param("organizationId", actor.organizationId())
                .param("byMatter", matterId != null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .param("byContract", contractId != null)
                .param("contractId", contractId == null ? new UUID(0, 0) : contractId)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(allowed)) {
            throw denied();
        }
    }

    private static BusinessException denied() {
        return new BusinessException(
                "DOCUMENT_ACCESS_DENIED",
                "你没有访问该文件的权限",
                HttpStatus.FORBIDDEN
        );
    }
}
