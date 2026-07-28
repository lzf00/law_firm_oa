package com.zoro.legaloa.matter;

import com.zoro.legaloa.archive.ArchiveService;
import com.zoro.legaloa.document.DocumentController.DocumentView;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.matter.DeadlineController.DeadlineView;
import com.zoro.legaloa.matter.MatterController.ApprovalReference;
import com.zoro.legaloa.matter.MatterController.ConflictReference;
import com.zoro.legaloa.matter.MatterController.ContractReference;
import com.zoro.legaloa.matter.MatterController.MatterEventView;
import com.zoro.legaloa.matter.MatterController.MatterMemberView;
import com.zoro.legaloa.matter.MatterController.MatterWorkspace;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatterWorkspaceService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final MatterService matterService;
    private final DeadlineService deadlineService;
    private final ArchiveService archiveService;

    public MatterWorkspaceService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            MatterService matterService,
            DeadlineService deadlineService,
            ArchiveService archiveService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.matterService = matterService;
        this.deadlineService = deadlineService;
        this.archiveService = archiveService;
    }

    @Transactional(readOnly = true)
    public MatterWorkspace get(UUID matterId) {
        var detail = matterService.get(matterId);
        RequestActor actor = actorProvider.current();
        return new MatterWorkspace(
                detail,
                team(matterId),
                conflicts(matterId, actor.organizationId()),
                contracts(matterId, actor.organizationId()),
                approvals(matterId, actor.organizationId()),
                archiveService.list(matterId),
                deadlineService.list(matterId),
                documents(matterId, actor.organizationId()),
                activity(matterId)
        );
    }

    private List<MatterMemberView> team(UUID matterId) {
        return jdbcClient.sql("""
                        SELECT u.id, u.display_name, mm.member_role, mm.can_download, mm.joined_at
                        FROM matter_members mm
                        JOIN users u ON u.id = mm.user_id
                        WHERE mm.matter_id = :matterId AND mm.left_at IS NULL
                        ORDER BY
                          CASE mm.member_role
                            WHEN 'RESPONSIBLE' THEN 0 WHEN 'LEAD' THEN 1
                            WHEN 'COUNSEL' THEN 2 WHEN 'ASSISTANT' THEN 3 ELSE 4
                          END,
                          u.display_name
                        """)
                .param("matterId", matterId)
                .query((rs, rowNum) -> new MatterMemberView(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("member_role"),
                        rs.getBoolean("can_download"),
                        rs.getTimestamp("joined_at").toInstant()
                ))
                .list();
    }

    private List<ConflictReference> conflicts(UUID matterId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT DISTINCT cc.id, cc.request_number, cc.proposed_matter_title,
                               cc.status, cc.risk_level, cc.decision, cc.created_at
                        FROM conflict_checks cc
                        JOIN conflict_check_parties ccp ON ccp.conflict_check_id = cc.id
                        JOIN matter_parties mp ON mp.party_id = ccp.party_id
                        WHERE mp.matter_id = :matterId
                          AND cc.organization_id = :organizationId
                        ORDER BY cc.created_at DESC
                        LIMIT 50
                        """)
                .param("matterId", matterId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ConflictReference(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_number"),
                        rs.getString("proposed_matter_title"),
                        rs.getString("status"),
                        rs.getString("risk_level"),
                        rs.getString("decision"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    private List<ContractReference> contracts(UUID matterId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT c.id, c.contract_number, c.title, c.status, c.amount, c.currency
                        FROM contract_matters cm
                        JOIN contracts c ON c.id = cm.contract_id
                        WHERE cm.matter_id = :matterId
                          AND c.organization_id = :organizationId
                          AND c.deleted_at IS NULL
                        ORDER BY c.updated_at DESC
                        """)
                .param("matterId", matterId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ContractReference(
                        rs.getObject("id", UUID.class),
                        rs.getString("contract_number"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency")
                ))
                .list();
    }

    private List<ApprovalReference> approvals(UUID matterId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT wl.id, wl.business_type, wl.business_id,
                               wl.process_definition_key, wl.status, wl.decision,
                               wl.started_at, wl.completed_at
                        FROM workflow_links wl
                        WHERE wl.organization_id = :organizationId
                          AND (
                            (wl.business_type = 'MATTER' AND wl.business_id = :matterId)
                            OR (
                              wl.business_type = 'CONTRACT'
                              AND EXISTS (
                                SELECT 1 FROM contract_matters cm
                                WHERE cm.contract_id = wl.business_id
                                  AND cm.matter_id = :matterId
                              )
                            )
                          )
                        ORDER BY wl.started_at DESC
                        LIMIT 100
                        """)
                .param("matterId", matterId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new ApprovalReference(
                        rs.getObject("id", UUID.class),
                        rs.getString("business_type"),
                        rs.getObject("business_id", UUID.class),
                        rs.getString("process_definition_key"),
                        rs.getString("status"),
                        rs.getString("decision"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant()
                ))
                .list();
    }

    private List<DocumentView> documents(UUID matterId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT d.id, d.matter_id, d.contract_id, d.logical_name, d.document_type,
                               d.confidentiality_level, d.current_version_id,
                               dv.version_number, dv.version_status, dv.signature_status,
                               dv.original_filename, dv.size_bytes, dv.created_at
                        FROM documents d
                        LEFT JOIN document_versions dv ON dv.id = d.current_version_id
                        WHERE d.organization_id = :organizationId
                          AND d.matter_id = :matterId AND d.deleted_at IS NULL
                        ORDER BY d.updated_at DESC
                        LIMIT 200
                        """)
                .param("matterId", matterId)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new DocumentView(
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
                        rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    private List<MatterEventView> activity(UUID matterId) {
        return jdbcClient.sql("""
                        SELECT me.id, me.event_type, me.title, me.description,
                               me.event_at, u.display_name AS created_by_name
                        FROM matter_events me
                        JOIN users u ON u.id = me.created_by
                        WHERE me.matter_id = :matterId
                        ORDER BY me.event_at DESC
                        LIMIT 200
                        """)
                .param("matterId", matterId)
                .query((rs, rowNum) -> new MatterEventView(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getTimestamp("event_at").toInstant(),
                        rs.getString("created_by_name")
                ))
                .list();
    }
}
