package com.zoro.legaloa.party;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.party.ClientController.ClientView;
import com.zoro.legaloa.party.ClientController.CreateClientRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;
    private final AuthorizationService authorizationService;

    public ClientService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService,
            AuthorizationService authorizationService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
        this.authorizationService = authorizationService;
    }

    @Transactional(readOnly = true)
    public List<ClientView> list() {
        RequestActor actor = actorProvider.current();
        return jdbcClient.sql("""
                        SELECT c.id, c.party_id, c.client_number, p.display_name, p.party_type,
                               c.owner_user_id, u.display_name AS owner_name, c.status, c.source
                        FROM clients c
                        JOIN parties p ON p.id = c.party_id
                        LEFT JOIN users u ON u.id = c.owner_user_id
                        WHERE p.organization_id = :organizationId
                          AND p.deleted_at IS NULL AND c.deleted_at IS NULL
                        ORDER BY c.updated_at DESC
                        """)
                .param("organizationId", actor.organizationId())
                .query(ClientService::map)
                .list();
    }

    @Transactional
    public ClientView create(CreateClientRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CLIENT_CREATE");
        UUID id = jdbcClient.sql("""
                        INSERT INTO clients (party_id, client_number, owner_user_id, source)
                        SELECT p.id, :clientNumber, :ownerUserId, :source
                        FROM parties p
                        WHERE p.id = :partyId AND p.organization_id = :organizationId
                          AND p.deleted_at IS NULL
                          AND (:noOwner OR EXISTS (
                              SELECT 1 FROM users u
                              WHERE u.id = :ownerUserId
                                AND u.organization_id = :organizationId
                                AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          ))
                        RETURNING id
                        """)
                .param("partyId", request.partyId())
                .param("clientNumber", request.clientNumber().trim())
                .param("ownerUserId", request.ownerUserId())
                .param("noOwner", request.ownerUserId() == null)
                .param("source", request.source())
                .param("organizationId", actor.organizationId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "CLIENT_CONTEXT_INVALID", "主体或客户负责人无效", HttpStatus.BAD_REQUEST
                ));
        auditService.success(actor, "CLIENT_CREATE", "CLIENT", id);
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public ClientView update(UUID id, CreateClientRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CLIENT_MANAGE");
        int updated = jdbcClient.sql("""
                        UPDATE clients c
                        SET party_id = :partyId,
                            client_number = :clientNumber,
                            owner_user_id = :ownerUserId,
                            source = :source,
                            updated_at = now()
                        WHERE c.id = :id AND c.deleted_at IS NULL
                          AND EXISTS (
                              SELECT 1 FROM parties current_party
                              WHERE current_party.id = c.party_id
                                AND current_party.organization_id = :organizationId
                                AND current_party.deleted_at IS NULL
                          )
                          AND EXISTS (
                              SELECT 1 FROM parties target_party
                              WHERE target_party.id = :partyId
                                AND target_party.organization_id = :organizationId
                                AND target_party.deleted_at IS NULL
                          )
                          AND (:noOwner OR EXISTS (
                              SELECT 1 FROM users u
                              WHERE u.id = :ownerUserId
                                AND u.organization_id = :organizationId
                                AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          ))
                        """)
                .param("id", id)
                .param("partyId", request.partyId())
                .param("clientNumber", request.clientNumber().trim())
                .param("ownerUserId", request.ownerUserId())
                .param("noOwner", request.ownerUserId() == null)
                .param("source", request.source())
                .param("organizationId", actor.organizationId())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "CLIENT_CONTEXT_INVALID", "客户、主体或负责人无效", HttpStatus.BAD_REQUEST
            );
        }
        auditService.success(actor, "CLIENT_UPDATE", "CLIENT", id);
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static ClientView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ClientView(
                rs.getObject("id", UUID.class),
                rs.getObject("party_id", UUID.class),
                rs.getString("client_number"),
                rs.getString("display_name"),
                rs.getString("party_type"),
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("owner_name"),
                rs.getString("status"),
                rs.getString("source")
        );
    }
}
