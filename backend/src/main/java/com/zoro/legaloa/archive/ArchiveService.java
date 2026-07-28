package com.zoro.legaloa.archive;

import com.zoro.legaloa.archive.ArchiveController.AddArchiveItemRequest;
import com.zoro.legaloa.archive.ArchiveController.ArchiveVolumeView;
import com.zoro.legaloa.archive.ArchiveController.ArchiveItemView;
import com.zoro.legaloa.archive.ArchiveController.CreateArchiveVolumeRequest;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArchiveService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final DocumentAccessService accessService;
    private final AuditService auditService;

    public ArchiveService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            DocumentAccessService accessService,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.accessService = accessService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ArchiveVolumeView> list(UUID matterId) {
        RequestActor actor = actorProvider.current();
        List<ArchiveVolumeView> volumes = jdbcClient.sql("""
                        SELECT av.id, av.archive_number, av.title, av.matter_id,
                               av.retention_policy_code, av.status, av.archived_at,
                               u.display_name AS created_by_name, av.created_at,
                               COUNT(ai.document_id) AS item_count
                        FROM archive_volumes av
                        JOIN users u ON u.id = av.created_by
                        LEFT JOIN archive_items ai ON ai.archive_volume_id = av.id
                        WHERE av.organization_id = :organizationId
                          AND (:allMatters OR av.matter_id = :matterId)
                          AND (
                            av.matter_id IS NULL
                            OR EXISTS (
                              SELECT 1 FROM matter_members mm
                              WHERE mm.matter_id = av.matter_id
                                AND mm.user_id = :userId AND mm.left_at IS NULL
                            )
                            OR EXISTS (
                              SELECT 1 FROM user_roles ur
                              JOIN roles r ON r.id = ur.role_id
                              WHERE ur.user_id = :userId
                                AND r.code IN ('ADMIN', 'MANAGING_PARTNER')
                            )
                          )
                        GROUP BY av.id, u.display_name
                        ORDER BY av.created_at DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("allMatters", matterId == null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .query((rs, rowNum) -> new ArchiveVolumeView(
                        rs.getObject("id", UUID.class),
                        rs.getString("archive_number"),
                        rs.getString("title"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("retention_policy_code"),
                        rs.getString("status"),
                        rs.getTimestamp("archived_at") == null
                                ? null : rs.getTimestamp("archived_at").toInstant(),
                        rs.getString("created_by_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("item_count"),
                        List.of()
                ))
                .list();
        return volumes.stream().map(volume -> new ArchiveVolumeView(
                volume.id(), volume.archiveNumber(), volume.title(), volume.matterId(),
                volume.retentionPolicyCode(), volume.status(), volume.archivedAt(),
                volume.createdByName(), volume.createdAt(), volume.itemCount(),
                items(volume.id())
        )).toList();
    }

    @Transactional
    public ArchiveVolumeView create(CreateArchiveVolumeRequest request) {
        RequestActor actor = actorProvider.current();
        if (request.matterId() != null) {
            accessService.requireContextWrite(actor, request.matterId(), null);
        }
        UUID archiveId = jdbcClient.sql("""
                        INSERT INTO archive_volumes
                            (organization_id, archive_number, title, matter_id,
                             retention_policy_code, created_by)
                        VALUES
                            (:organizationId, :archiveNumber, :title, :matterId,
                             :retentionPolicyCode, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("archiveNumber", request.archiveNumber().trim())
                .param("title", request.title().trim())
                .param("matterId", request.matterId())
                .param("retentionPolicyCode", request.retentionPolicyCode().trim())
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .single();
        auditService.success(actor, "ARCHIVE_CREATE", "ARCHIVE_VOLUME", archiveId);
        return requireView(archiveId);
    }

    @Transactional
    public ArchiveVolumeView addItem(UUID archiveId, AddArchiveItemRequest request) {
        RequestActor actor = actorProvider.current();
        accessService.requireDocumentRead(actor, request.documentId(), false);
        Integer added = jdbcClient.sql("""
                        INSERT INTO archive_items
                            (archive_volume_id, document_id, sequence_number)
                        SELECT av.id, d.id,
                               COALESCE((SELECT MAX(ai.sequence_number) + 1
                                         FROM archive_items ai
                                         WHERE ai.archive_volume_id = av.id), 1)
                        FROM archive_volumes av
                        JOIN documents d ON d.id = :documentId
                        WHERE av.id = :archiveId
                          AND av.organization_id = :organizationId
                          AND av.status = 'OPEN'
                          AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                          AND (av.matter_id IS NULL OR d.matter_id = av.matter_id)
                        ON CONFLICT DO NOTHING
                        """)
                .param("archiveId", archiveId)
                .param("documentId", request.documentId())
                .param("organizationId", actor.organizationId())
                .update();
        if (added == 0) {
            throw new BusinessException(
                    "ARCHIVE_ITEM_INVALID", "卷宗不可编辑、文档不存在或已归入该卷", HttpStatus.CONFLICT
            );
        }
        auditService.success(actor, "ARCHIVE_ITEM_ADD", "ARCHIVE_VOLUME", archiveId);
        return requireView(archiveId);
    }

    private ArchiveVolumeView requireView(UUID archiveId) {
        return list(null).stream()
                .filter(item -> item.id().equals(archiveId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "ARCHIVE_NOT_FOUND", "卷宗不存在", HttpStatus.NOT_FOUND
                ));
    }

    private List<ArchiveItemView> items(UUID archiveId) {
        return jdbcClient.sql("""
                        SELECT d.id, d.logical_name, d.document_type, ai.sequence_number
                        FROM archive_items ai
                        JOIN documents d ON d.id = ai.document_id
                        WHERE ai.archive_volume_id = :archiveId AND d.deleted_at IS NULL
                        ORDER BY ai.sequence_number
                        """)
                .param("archiveId", archiveId)
                .query((rs, rowNum) -> new ArchiveItemView(
                        rs.getObject("id", UUID.class),
                        rs.getString("logical_name"),
                        rs.getString("document_type"),
                        rs.getInt("sequence_number")
                ))
                .list();
    }
}
