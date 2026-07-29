package com.zoro.legaloa.archive;

import com.zoro.legaloa.archive.ArchiveController.AddArchiveItemRequest;
import com.zoro.legaloa.archive.ArchiveController.ArchiveCandidateView;
import com.zoro.legaloa.archive.ArchiveController.ArchiveItemView;
import com.zoro.legaloa.archive.ArchiveController.ArchiveLifecycleEventView;
import com.zoro.legaloa.archive.ArchiveController.ArchiveVolumeView;
import com.zoro.legaloa.archive.ArchiveController.CloseArchiveRequest;
import com.zoro.legaloa.archive.ArchiveController.CreateArchiveVolumeRequest;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
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
public class ArchiveService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final DocumentAccessService accessService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public ArchiveService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            DocumentAccessService accessService,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.accessService = accessService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ArchiveVolumeView> list(UUID matterId) {
        RequestActor actor = actorProvider.current();
        boolean viewAll = authorizationService.hasPermission(actor, "ARCHIVE_VIEW_ALL");
        List<ArchiveVolumeView> volumes = jdbcClient.sql("""
                        SELECT av.id, av.archive_number, av.title, av.matter_id,
                               m.matter_number, m.title AS matter_title,
                               av.retention_policy_code, av.status, av.archived_at,
                               av.created_by, creator.display_name AS created_by_name,
                               av.created_at, archiver.display_name AS archived_by_name,
                               COUNT(ai.document_id) AS item_count
                        FROM archive_volumes av
                        JOIN users creator ON creator.id = av.created_by
                        LEFT JOIN users archiver ON archiver.id = av.archived_by
                        LEFT JOIN matters m ON m.id = av.matter_id
                        LEFT JOIN archive_items ai ON ai.archive_volume_id = av.id
                        WHERE av.organization_id = :organizationId
                          AND (:allMatters OR av.matter_id = :matterId)
                          AND (
                            :viewAll
                            OR EXISTS (
                              SELECT 1 FROM matter_members mm
                              WHERE mm.matter_id = av.matter_id
                                AND mm.user_id = :userId AND mm.left_at IS NULL
                            )
                          )
                        GROUP BY av.id, m.matter_number, m.title,
                                 creator.display_name, archiver.display_name
                        ORDER BY
                          CASE av.status WHEN 'OPEN' THEN 0 ELSE 1 END,
                          av.created_at DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .param("allMatters", matterId == null)
                .param("matterId", matterId == null ? new UUID(0, 0) : matterId)
                .query((rs, rowNum) -> new ArchiveVolumeView(
                        rs.getObject("id", UUID.class),
                        rs.getString("archive_number"),
                        rs.getString("title"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getString("matter_title"),
                        rs.getString("retention_policy_code"),
                        rs.getString("status"),
                        instant(rs.getTimestamp("archived_at")),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("archived_by_name"),
                        rs.getInt("item_count"),
                        List.of(),
                        List.of()
                ))
                .list();
        return volumes.stream().map(volume -> new ArchiveVolumeView(
                volume.id(), volume.archiveNumber(), volume.title(), volume.matterId(),
                volume.matterNumber(), volume.matterTitle(), volume.retentionPolicyCode(),
                volume.status(), volume.archivedAt(), volume.createdBy(),
                volume.createdByName(), volume.createdAt(), volume.archivedByName(),
                volume.itemCount(), items(volume.id()), lifecycle(volume.id())
        )).toList();
    }

    @Transactional
    public ArchiveVolumeView create(CreateArchiveVolumeRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ARCHIVE_CREATE");
        accessService.requireContextWrite(actor, request.matterId(), null);
        UUID archiveId = jdbcClient.sql("""
                        INSERT INTO archive_volumes
                            (organization_id, archive_number, title, matter_id,
                             retention_policy_code, created_by)
                        SELECT :organizationId, :archiveNumber, :title, m.id,
                               :retentionPolicyCode, :createdBy
                        FROM matters m
                        WHERE m.id = :matterId AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("archiveNumber", request.archiveNumber().trim())
                .param("title", request.title().trim())
                .param("matterId", request.matterId())
                .param("retentionPolicyCode", request.retentionPolicyCode().trim())
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "ARCHIVE_CONTEXT_INVALID",
                        "案件不存在或当前用户无权建卷",
                        HttpStatus.BAD_REQUEST
                ));
        recordEvent(actor, archiveId, "CREATED", null, null, null);
        auditService.success(actor, "ARCHIVE_CREATE", "ARCHIVE_VOLUME", archiveId);
        return requireView(actor, archiveId);
    }

    @Transactional
    public ArchiveVolumeView addItem(UUID archiveId, AddArchiveItemRequest request) {
        RequestActor actor = actorProvider.current();
        ArchiveState archive = lockArchive(actor, archiveId);
        requireArchiveWrite(actor, archive);
        if (!ArchiveLifecyclePolicy.canEdit(archive.status())) {
            throw new BusinessException(
                    "ARCHIVE_ITEM_INVALID",
                    "卷宗已封卷，不能继续编目",
                    HttpStatus.CONFLICT
            );
        }
        accessService.requireDocumentRead(actor, request.documentId(), false);
        AddedItem added = jdbcClient.sql("""
                        INSERT INTO archive_items
                            (archive_volume_id, document_id, document_version_id, sequence_number)
                        SELECT av.id, d.id, dv.id,
                               COALESCE((SELECT MAX(ai.sequence_number) + 1
                                         FROM archive_items ai
                                         WHERE ai.archive_volume_id = av.id), 1)
                        FROM archive_volumes av
                        JOIN documents d ON d.id = :documentId
                        JOIN document_versions dv ON dv.id = d.current_version_id
                        WHERE av.id = :archiveId
                          AND av.organization_id = :organizationId
                          AND av.status = 'OPEN'
                          AND d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                          AND (
                            d.matter_id = av.matter_id
                            OR EXISTS (
                              SELECT 1 FROM contract_matters cm
                              WHERE cm.contract_id = d.contract_id
                                AND cm.matter_id = av.matter_id
                            )
                          )
                          AND dv.ingestion_status = 'AVAILABLE'
                        ON CONFLICT DO NOTHING
                        RETURNING document_id, document_version_id
                        """)
                .param("archiveId", archiveId)
                .param("documentId", request.documentId())
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new AddedItem(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "ARCHIVE_ITEM_INVALID",
                        "卷宗不可编辑，或文档未通过扫描、归属不同案件、已归入该卷",
                        HttpStatus.CONFLICT
                ));
        recordEvent(
                actor, archiveId, "ITEM_ADDED",
                added.documentId(), added.documentVersionId(), null
        );
        String sha256 = jdbcClient.sql("""
                        SELECT sha256 FROM document_versions WHERE id = :versionId
                        """)
                .param("versionId", added.documentVersionId())
                .query(String.class)
                .single();
        auditService.record(
                actor, "ARCHIVE_ITEM_ADD", "ARCHIVE_VOLUME", archiveId,
                "SUCCESS", null, Map.of(
                        "documentId", added.documentId(),
                        "documentVersionId", added.documentVersionId(),
                        "sha256", sha256
                )
        );
        return requireView(actor, archiveId);
    }

    @Transactional(readOnly = true)
    public List<ArchiveCandidateView> candidates(UUID archiveId) {
        RequestActor actor = actorProvider.current();
        ArchiveState archive = findArchive(actor, archiveId);
        requireArchiveWrite(actor, archive);
        if (!ArchiveLifecyclePolicy.canEdit(archive.status())) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT d.id AS document_id, dv.id AS document_version_id,
                               d.logical_name, d.document_type, dv.version_number,
                               dv.original_filename, dv.signature_status,
                               d.contract_id, c.contract_number
                        FROM documents d
                        JOIN document_versions dv ON dv.id = d.current_version_id
                        LEFT JOIN contracts c ON c.id = d.contract_id
                        WHERE d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                          AND dv.ingestion_status = 'AVAILABLE'
                          AND (
                            d.matter_id = :matterId
                            OR EXISTS (
                              SELECT 1 FROM contract_matters cm
                              WHERE cm.contract_id = d.contract_id
                                AND cm.matter_id = :matterId
                            )
                          )
                          AND NOT EXISTS (
                            SELECT 1 FROM archive_items ai
                            WHERE ai.archive_volume_id = :archiveId
                              AND ai.document_id = d.id
                          )
                        ORDER BY
                          CASE WHEN d.contract_id IS NOT NULL THEN 0 ELSE 1 END,
                          d.updated_at DESC
                        """)
                .param("organizationId", actor.organizationId())
                .param("matterId", archive.matterId())
                .param("archiveId", archiveId)
                .query((rs, rowNum) -> new ArchiveCandidateView(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("logical_name"),
                        rs.getString("document_type"),
                        rs.getInt("version_number"),
                        rs.getString("original_filename"),
                        rs.getString("signature_status"),
                        rs.getObject("contract_id", UUID.class),
                        rs.getString("contract_number")
                ))
                .list();
    }

    @Transactional
    public ArchiveVolumeView close(UUID archiveId, CloseArchiveRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ARCHIVE_CLOSE");
        ArchiveState archive = lockArchive(actor, archiveId);
        int itemCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM archive_items WHERE archive_volume_id = :archiveId
                        """)
                .param("archiveId", archiveId)
                .query(Integer.class)
                .single();
        int unavailable = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM archive_items ai
                        LEFT JOIN document_versions dv ON dv.id = ai.document_version_id
                        WHERE ai.archive_volume_id = :archiveId
                          AND (dv.id IS NULL OR dv.ingestion_status <> 'AVAILABLE')
                        """)
                .param("archiveId", archiveId)
                .query(Integer.class)
                .single();
        if (!ArchiveLifecyclePolicy.canClose(archive.status(), itemCount, unavailable)) {
            throw new BusinessException(
                    "ARCHIVE_CLOSE_INVALID",
                    "只有包含已通过安全扫描文件版本的开放卷宗可以封卷",
                    HttpStatus.CONFLICT
            );
        }
        int updated = jdbcClient.sql("""
                        UPDATE archive_volumes
                        SET status = 'ARCHIVED', archived_by = :actorId, archived_at = now()
                        WHERE id = :archiveId AND organization_id = :organizationId
                          AND status = 'OPEN'
                        """)
                .param("actorId", actor.userId())
                .param("archiveId", archiveId)
                .param("organizationId", actor.organizationId())
                .update();
        if (updated != 1) {
            throw new BusinessException(
                    "ARCHIVE_STATE_CHANGED",
                    "卷宗状态已变化，请刷新后重试",
                    HttpStatus.CONFLICT
            );
        }
        recordEvent(actor, archiveId, "CLOSED", null, null, trimToNull(request.comment()));
        auditService.record(
                actor, "ARCHIVE_CLOSE", "ARCHIVE_VOLUME", archiveId,
                "SUCCESS", null, Map.of("itemCount", itemCount)
        );
        return requireView(actor, archiveId);
    }

    private ArchiveVolumeView requireView(RequestActor actor, UUID archiveId) {
        return list(null).stream()
                .filter(item -> item.id().equals(archiveId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "ARCHIVE_NOT_FOUND",
                        "卷宗不存在或当前用户无权访问",
                        HttpStatus.NOT_FOUND
                ));
    }

    private ArchiveState lockArchive(RequestActor actor, UUID archiveId) {
        return jdbcClient.sql("""
                        SELECT id, matter_id, status, created_by
                        FROM archive_volumes
                        WHERE id = :archiveId AND organization_id = :organizationId
                        FOR UPDATE
                        """)
                .param("archiveId", archiveId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new ArchiveState(
                        rs.getObject("id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("created_by", UUID.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "ARCHIVE_NOT_FOUND",
                        "卷宗不存在或当前用户无权访问",
                        HttpStatus.NOT_FOUND
                ));
    }

    private ArchiveState findArchive(RequestActor actor, UUID archiveId) {
        return jdbcClient.sql("""
                        SELECT id, matter_id, status, created_by
                        FROM archive_volumes
                        WHERE id = :archiveId AND organization_id = :organizationId
                        """)
                .param("archiveId", archiveId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new ArchiveState(
                        rs.getObject("id", UUID.class),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("created_by", UUID.class)
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "ARCHIVE_NOT_FOUND",
                        "卷宗不存在或当前用户无权访问",
                        HttpStatus.NOT_FOUND
                ));
    }

    private void requireArchiveWrite(RequestActor actor, ArchiveState archive) {
        if (authorizationService.hasPermission(actor, "ARCHIVE_MANAGE")) {
            return;
        }
        if (archive.matterId() == null) {
            throw new BusinessException(
                    "PERMISSION_DENIED",
                    "当前账号无权管理全所卷宗",
                    HttpStatus.FORBIDDEN
            );
        }
        accessService.requireContextWrite(actor, archive.matterId(), null);
    }

    private List<ArchiveItemView> items(UUID archiveId) {
        return jdbcClient.sql("""
                        SELECT d.id, ai.document_version_id, d.logical_name, d.document_type,
                               ai.sequence_number, dv.version_number,
                               dv.original_filename, dv.sha256, dv.version_status,
                               dv.signature_status, d.contract_id, c.contract_number
                        FROM archive_items ai
                        JOIN documents d ON d.id = ai.document_id
                        LEFT JOIN document_versions dv ON dv.id = ai.document_version_id
                        LEFT JOIN contracts c ON c.id = d.contract_id
                        WHERE ai.archive_volume_id = :archiveId AND d.deleted_at IS NULL
                        ORDER BY ai.sequence_number
                        """)
                .param("archiveId", archiveId)
                .query((rs, rowNum) -> new ArchiveItemView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("logical_name"),
                        rs.getString("document_type"),
                        rs.getInt("sequence_number"),
                        rs.getInt("version_number"),
                        rs.getString("original_filename"),
                        rs.getString("sha256"),
                        rs.getString("version_status"),
                        rs.getString("signature_status"),
                        rs.getObject("contract_id", UUID.class),
                        rs.getString("contract_number")
                ))
                .list();
    }

    private List<ArchiveLifecycleEventView> lifecycle(UUID archiveId) {
        return jdbcClient.sql("""
                        SELECT event.id, event.action, event.actor_user_id,
                               actor.display_name AS actor_name,
                               event.document_id, event.document_version_id,
                               event.comment, event.occurred_at
                        FROM archive_lifecycle_events event
                        JOIN users actor ON actor.id = event.actor_user_id
                        WHERE event.archive_volume_id = :archiveId
                        ORDER BY event.occurred_at, event.id
                        """)
                .param("archiveId", archiveId)
                .query((rs, rowNum) -> new ArchiveLifecycleEventView(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("comment"),
                        rs.getTimestamp("occurred_at").toInstant()
                ))
                .list();
    }

    private void recordEvent(
            RequestActor actor,
            UUID archiveId,
            String action,
            UUID documentId,
            UUID documentVersionId,
            String comment
    ) {
        jdbcClient.sql("""
                        INSERT INTO archive_lifecycle_events
                            (organization_id, archive_volume_id, action, actor_user_id,
                             document_id, document_version_id, comment)
                        VALUES
                            (:organizationId, :archiveId, :action, :actorId,
                             :documentId, :documentVersionId, :comment)
                        """)
                .param("organizationId", actor.organizationId())
                .param("archiveId", archiveId)
                .param("action", action)
                .param("actorId", actor.userId())
                .param("documentId", documentId)
                .param("documentVersionId", documentVersionId)
                .param("comment", comment)
                .update();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ArchiveState(UUID id, UUID matterId, String status, UUID createdBy) {}

    private record AddedItem(UUID documentId, UUID documentVersionId) {}
}
