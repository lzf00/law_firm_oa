package com.zoro.legaloa.identity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class IdentityDirectory {
    private final JdbcClient jdbcClient;

    public IdentityDirectory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<RequestActor> findActiveById(UUID userId, UUID organizationId) {
        return findOne("""
                SELECT id, organization_id, username, display_name
                FROM users
                WHERE id = :identifier
                  AND organization_id = :organizationId
                  AND status = 'ACTIVE' AND deleted_at IS NULL
                """, userId, organizationId);
    }

    public Optional<RequestActor> findActiveByDingTalkId(String externalId, UUID organizationId) {
        return findOne("""
                SELECT id, organization_id, username, display_name
                FROM users
                WHERE dingtalk_user_id = :identifier
                  AND organization_id = :organizationId
                  AND status = 'ACTIVE' AND deleted_at IS NULL
                """, externalId, organizationId);
    }

    private Optional<RequestActor> findOne(String sql, Object identifier, UUID organizationId) {
        return jdbcClient.sql(sql)
                .param("identifier", identifier)
                .param("organizationId", organizationId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .map(IdentityDirectory::toActor);
    }

    private static RequestActor toActor(Map<String, Object> row) {
        return new RequestActor(
                (UUID) row.get("id"),
                (UUID) row.get("organization_id"),
                (String) row.get("username"),
                (String) row.get("display_name")
        );
    }
}
