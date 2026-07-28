package com.zoro.legaloa.party;

import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.party.ConflictCheckController.ConflictHit;
import com.zoro.legaloa.party.ConflictCheckController.ConflictPreview;
import com.zoro.legaloa.party.ConflictCheckController.ConflictPreviewRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConflictCheckService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;

    public ConflictCheckService(JdbcClient jdbcClient, RequestActorProvider actorProvider) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
    }

    @Transactional(readOnly = true)
    public ConflictPreview preview(ConflictPreviewRequest request) {
        RequestActor actor = actorProvider.current();
        List<ConflictHit> hits = jdbcClient.sql("""
                        SELECT p.id AS party_id, p.display_name AS party_name,
                               m.id AS matter_id, m.matter_number, m.title AS matter_title,
                               mp.party_role, mp.side, m.status AS matter_status
                        FROM matter_parties mp
                        JOIN parties p ON p.id = mp.party_id
                        JOIN matters m ON m.id = mp.matter_id
                        WHERE m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND mp.party_id IN (:partyIds)
                        ORDER BY
                          CASE WHEN m.status IN ('ACTIVE', 'INTAKE', 'CONFLICT_REVIEW') THEN 0 ELSE 1 END,
                          m.created_at DESC
                        """)
                .param("organizationId", actor.organizationId())
                .param("partyIds", request.partyIds())
                .query((rs, rowNum) -> new ConflictHit(
                        rs.getObject("party_id", UUID.class),
                        rs.getString("party_name"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getString("matter_title"),
                        rs.getString("party_role"),
                        rs.getString("side"),
                        rs.getString("matter_status")
                ))
                .list();

        boolean activeHit = hits.stream()
                .anyMatch(hit -> List.of("ACTIVE", "INTAKE", "CONFLICT_REVIEW").contains(hit.matterStatus()));
        String riskLevel = activeHit ? "HIGH" : hits.isEmpty() ? "CLEAR" : "MEDIUM";
        return new ConflictPreview(riskLevel, hits.size(), hits);
    }
}

