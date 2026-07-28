package com.zoro.legaloa.seal;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.seal.SealController.CreateSealRequest;
import com.zoro.legaloa.seal.SealController.SealRequestView;
import com.zoro.legaloa.seal.SealController.SealView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SealService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;

    public SealService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SealView> listSeals() {
        RequestActor actor = actorProvider.current();
        return jdbcClient.sql("""
                        SELECT s.id, s.name, s.seal_type, u.display_name, s.status
                        FROM seals s
                        JOIN users u ON u.id = s.custodian_user_id
                        WHERE s.organization_id = :organizationId
                        ORDER BY s.name
                        """)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new SealView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("seal_type"),
                        rs.getString("display_name"),
                        rs.getString("status")
                ))
                .list();
    }

    @Transactional(readOnly = true)
    public List<SealRequestView> listRequests() {
        RequestActor actor = actorProvider.current();
        return jdbcClient.sql("""
                        SELECT sr.id, sr.seal_id, s.name AS seal_name,
                               sr.matter_id, sr.contract_id, sr.purpose, sr.copies,
                               sr.status, u.display_name AS requested_by_name, sr.created_at
                        FROM seal_requests sr
                        JOIN seals s ON s.id = sr.seal_id
                        JOIN users u ON u.id = sr.requested_by
                        WHERE sr.organization_id = :organizationId
                        ORDER BY sr.created_at DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new SealRequestView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("seal_id", UUID.class),
                        rs.getString("seal_name"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getObject("contract_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getInt("copies"),
                        rs.getString("status"),
                        rs.getString("requested_by_name"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    @Transactional
    public SealRequestView createRequest(CreateSealRequest request) {
        RequestActor actor = actorProvider.current();
        if ((request.matterId() == null) == (request.contractId() == null)) {
            throw new BusinessException(
                    "SEAL_CONTEXT_INVALID", "用印申请必须且只能关联一个案件或合同", HttpStatus.BAD_REQUEST
            );
        }
        UUID requestId = jdbcClient.sql("""
                        INSERT INTO seal_requests
                            (organization_id, seal_id, matter_id, contract_id,
                             purpose, copies, requested_by)
                        SELECT :organizationId, s.id, CAST(:matterId AS uuid), CAST(:contractId AS uuid),
                               :purpose, :copies, :requestedBy
                        FROM seals s
                        WHERE s.id = :sealId
                          AND s.organization_id = :organizationId
                          AND s.status = 'ACTIVE'
                          AND (
                            (:byMatter AND EXISTS (
                                SELECT 1 FROM matter_members mm
                                JOIN matters m ON m.id = mm.matter_id
                                WHERE m.id = CAST(:matterId AS uuid)
                                  AND m.organization_id = :organizationId
                                  AND mm.user_id = :requestedBy AND mm.left_at IS NULL
                            ))
                            OR
                            (:byContract AND EXISTS (
                                SELECT 1 FROM contract_members cm
                                JOIN contracts c ON c.id = cm.contract_id
                                WHERE c.id = CAST(:contractId AS uuid)
                                  AND c.organization_id = :organizationId
                                  AND cm.user_id = :requestedBy
                            ))
                          )
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("sealId", request.sealId())
                .param("byMatter", request.matterId() != null)
                .param("matterId", request.matterId())
                .param("byContract", request.contractId() != null)
                .param("contractId", request.contractId())
                .param("purpose", request.purpose().trim())
                .param("copies", request.copies())
                .param("requestedBy", actor.userId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "SEAL_REQUEST_DENIED", "印章无效或你不是关联业务成员", HttpStatus.FORBIDDEN
                ));
        auditService.success(actor, "SEAL_REQUEST_CREATE", "SEAL_REQUEST", requestId);
        return listRequests().stream()
                .filter(item -> item.id().equals(requestId))
                .findFirst()
                .orElseThrow();
    }
}
