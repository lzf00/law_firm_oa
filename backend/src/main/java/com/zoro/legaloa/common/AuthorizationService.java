package com.zoro.legaloa.common;

import com.zoro.legaloa.identity.RequestActor;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private final JdbcClient jdbcClient;

    public AuthorizationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<String> roles(RequestActor actor) {
        return jdbcClient.sql("""
                        SELECT r.code
                        FROM roles r
                        JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                          AND r.organization_id = :organizationId
                        ORDER BY r.code
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .query(String.class)
                .list();
    }

    public List<String> permissions(RequestActor actor) {
        return jdbcClient.sql("""
                        SELECT DISTINCT p.code
                        FROM permissions p
                        JOIN role_permissions rp ON rp.permission_id = p.id
                        JOIN roles r ON r.id = rp.role_id
                        JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                          AND r.organization_id = :organizationId
                        ORDER BY p.code
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .query(String.class)
                .list();
    }

    public boolean hasAnyRole(RequestActor actor, String... roleCodes) {
        if (roleCodes.length == 0) {
            return false;
        }
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM roles r
                        JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                          AND r.organization_id = :organizationId
                          AND r.code IN (:roleCodes)
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .param("roleCodes", Arrays.asList(roleCodes))
                .query(Integer.class)
                .single();
        return count > 0;
    }

    public boolean hasPermission(RequestActor actor, String permissionCode) {
        Boolean granted = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM user_roles ur
                            JOIN roles r ON r.id = ur.role_id
                            JOIN role_permissions rp ON rp.role_id = r.id
                            JOIN permissions p ON p.id = rp.permission_id
                            WHERE ur.user_id = :userId
                              AND r.organization_id = :organizationId
                              AND p.code = :permissionCode
                        )
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .param("permissionCode", permissionCode)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(granted);
    }

    public void requirePermission(RequestActor actor, String permissionCode) {
        if (!hasPermission(actor, permissionCode)) {
            throw new BusinessException(
                    "PERMISSION_DENIED",
                    "当前账号缺少权限：" + permissionCode,
                    HttpStatus.FORBIDDEN
            );
        }
    }
}
