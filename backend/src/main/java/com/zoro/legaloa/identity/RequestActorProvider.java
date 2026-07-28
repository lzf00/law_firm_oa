package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.BusinessException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class RequestActorProvider {
    private final JdbcClient jdbcClient;
    private final IdentityDirectory identityDirectory;
    private final String authMode;

    public RequestActorProvider(
            JdbcClient jdbcClient,
            IdentityDirectory identityDirectory,
            @Value("${app.auth.mode}") String authMode
    ) {
        this.jdbcClient = jdbcClient;
        this.identityDirectory = identityDirectory;
        this.authMode = authMode;
    }

    public RequestActor current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw userNotFound();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof SessionSubject subject) {
            return identityDirectory.findActiveById(subject.userId(), subject.organizationId())
                    .orElseThrow(RequestActorProvider::userNotFound);
        }
        if ("dev".equals(authMode)) {
            String username = authentication.getName();
            Map<String, Object> row = jdbcClient.sql("""
                        SELECT id, organization_id, username, display_name
                        FROM users
                        WHERE username = :username AND status = 'ACTIVE' AND deleted_at IS NULL
                        """)
                    .param("username", username)
                    .query()
                    .listOfRows()
                    .stream()
                    .findFirst()
                    .orElse(Map.of());
            if (!row.isEmpty()) {
                return new RequestActor(
                        (UUID) row.get("id"),
                        (UUID) row.get("organization_id"),
                        (String) row.get("username"),
                        (String) row.get("display_name")
                );
            }
        }
        throw userNotFound();
    }

    private static BusinessException userNotFound() {
        return new BusinessException(
                "USER_NOT_FOUND",
                "当前登录用户未同步到组织通讯录",
                HttpStatus.UNAUTHORIZED
        );
    }
}
