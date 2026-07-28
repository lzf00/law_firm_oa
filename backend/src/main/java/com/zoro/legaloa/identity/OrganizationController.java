package com.zoro.legaloa.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final OfficeAccessService officeAccessService;

    public OrganizationController(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            OfficeAccessService officeAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.officeAccessService = officeAccessService;
    }

    @GetMapping("/users")
    List<OrganizationUser> users() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT id, username, display_name, status
                        FROM users u
                        WHERE u.organization_id = :organizationId
                          AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
                          AND (
                            :globalAccess
                            OR u.primary_office_id IN (:officeIds)
                            OR EXISTS (
                              SELECT 1 FROM user_offices uo
                              WHERE uo.user_id = u.id
                                AND uo.office_id IN (:officeIds)
                                AND (uo.valid_until IS NULL OR uo.valid_until > now())
                            )
                          )
                        ORDER BY display_name
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new OrganizationUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("status")
                ))
                .list();
    }

    @GetMapping("/departments")
    List<OrganizationDepartment> departments() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT d.id, d.parent_id, d.name, d.sort_order,
                               d.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en,
                               COUNT(dm.user_id) AS member_count
                        FROM departments d
                        LEFT JOIN department_members dm ON dm.department_id = d.id
                        LEFT JOIN offices o ON o.id = d.office_id
                        WHERE d.organization_id = :organizationId
                          AND d.deleted_at IS NULL
                          AND (:globalAccess OR d.office_id IN (:officeIds))
                        GROUP BY d.id, d.parent_id, d.name, d.sort_order, o.id
                        ORDER BY d.sort_order, d.name
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new OrganizationDepartment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("parent_id", UUID.class),
                        rs.getString("name"),
                        rs.getInt("sort_order"),
                        rs.getLong("member_count"),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en")
                ))
                .list();
    }

    @GetMapping("/directory")
    List<DirectoryUser> directory() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT u.id, u.username, u.display_name, u.mobile_masked, u.email,
                               u.preferred_locale, u.primary_office_id,
                               o.name_zh AS office_name_zh, o.name_en AS office_name_en,
                               o.timezone, o.default_currency,
                               COALESCE(string_agg(DISTINCT d.name, '、'), '') AS departments,
                               COALESCE(string_agg(DISTINCT r.name, '、'), '') AS roles
                        FROM users u
                        LEFT JOIN offices o ON o.id = u.primary_office_id
                        LEFT JOIN department_members dm ON dm.user_id = u.id
                        LEFT JOIN departments d ON d.id = dm.department_id AND d.deleted_at IS NULL
                        LEFT JOIN user_roles ur ON ur.user_id = u.id
                        LEFT JOIN roles r ON r.id = ur.role_id
                        WHERE u.organization_id = :organizationId
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          AND (
                            :globalAccess
                            OR u.primary_office_id IN (:officeIds)
                            OR EXISTS (
                              SELECT 1 FROM user_offices visible_uo
                              WHERE visible_uo.user_id = u.id
                                AND visible_uo.office_id IN (:officeIds)
                                AND (visible_uo.valid_until IS NULL
                                     OR visible_uo.valid_until > now())
                            )
                          )
                        GROUP BY u.id, u.username, u.display_name, u.mobile_masked, u.email, o.id
                        ORDER BY u.display_name
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new DirectoryUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("mobile_masked"),
                        rs.getString("email"),
                        rs.getString("departments"),
                        rs.getString("roles"),
                        rs.getString("preferred_locale"),
                        rs.getObject("primary_office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en"),
                        rs.getString("timezone"),
                        rs.getString("default_currency")
                ))
                .list();
    }

    public record OrganizationUser(UUID id, String username, String displayName, String status) {}

    public record OrganizationDepartment(
            UUID id,
            UUID parentId,
            String name,
            int sortOrder,
            long memberCount,
            UUID officeId,
            String officeNameZh,
            String officeNameEn
    ) {}

    public record DirectoryUser(
            UUID id,
            String username,
            String displayName,
            String mobileMasked,
            String email,
            String departments,
            String roles,
            String preferredLocale,
            UUID primaryOfficeId,
            String officeNameZh,
            String officeNameEn,
            String timezone,
            String defaultCurrency
    ) {}
}
