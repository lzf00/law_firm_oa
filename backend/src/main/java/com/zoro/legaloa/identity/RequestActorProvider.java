package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.BusinessException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class RequestActorProvider {
    private final JdbcClient jdbcClient;

    public RequestActorProvider(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public RequestActor current() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, Object> row = jdbcClient.sql("""
                        SELECT id, organization_id, username, display_name
                        FROM users
                        WHERE username = :username AND status = 'ACTIVE' AND deleted_at IS NULL
                        """)
                .param("username", username)
                .query()
                .singleRow();
        if (row.isEmpty()) {
            throw new BusinessException("USER_NOT_FOUND", "当前登录用户未同步到组织通讯录", HttpStatus.UNAUTHORIZED);
        }
        return new RequestActor(
                (UUID) row.get("id"),
                (UUID) row.get("organization_id"),
                (String) row.get("username"),
                (String) row.get("display_name")
        );
    }
}

