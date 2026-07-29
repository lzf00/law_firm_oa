package com.zoro.legaloa.identity;

import java.util.List;
import java.util.UUID;
import com.zoro.legaloa.common.AuthorizationService;
import org.springframework.jdbc.core.simple.JdbcClient;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class CurrentUserController {
    private final RequestActorProvider actorProvider;
    private final JdbcClient jdbcClient;
    private final OfficeAccessService officeAccessService;
    private final AuthorizationService authorizationService;

    public CurrentUserController(
            RequestActorProvider actorProvider,
            JdbcClient jdbcClient,
            OfficeAccessService officeAccessService,
            AuthorizationService authorizationService
    ) {
        this.actorProvider = actorProvider;
        this.jdbcClient = jdbcClient;
        this.officeAccessService = officeAccessService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    CurrentUserResponse me(Authentication authentication) {
        RequestActor actor = actorProvider.current();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(Object::toString)
                .toList();
        UserPreference preference = jdbcClient.sql("""
                        SELECT u.preferred_locale, u.primary_office_id,
                               o.name_zh AS office_name_zh, o.name_en AS office_name_en,
                               o.timezone, o.default_currency
                        FROM users u
                        LEFT JOIN offices o ON o.id = u.primary_office_id
                        WHERE u.id = :userId
                        """)
                .param("userId", actor.userId())
                .query((rs, rowNum) -> new UserPreference(
                        rs.getString("preferred_locale"),
                        rs.getObject("primary_office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en"),
                        rs.getString("timezone"),
                        rs.getString("default_currency")
                ))
                .single();
        OfficeAccessScope officeScope = officeAccessService.scope(actor);
        List<String> roles = authorizationService.roles(actor);
        List<String> permissions = authorizationService.permissions(actor);
        List<AccessibleOffice> accessibleOffices = jdbcClient.sql("""
                        SELECT o.id, o.code, o.name_zh, o.name_en, o.timezone,
                               o.default_currency,
                               CASE WHEN :globalAccess THEN TRUE
                                    ELSE COALESCE(uo.access_level = 'MANAGER', FALSE)
                               END AS manageable
                        FROM offices o
                        LEFT JOIN user_offices uo
                          ON uo.office_id = o.id AND uo.user_id = :userId
                         AND (uo.valid_until IS NULL OR uo.valid_until > now())
                        WHERE o.organization_id = :organizationId
                          AND o.status = 'ACTIVE'
                          AND (:globalAccess OR o.id IN (:officeIds))
                        ORDER BY CASE WHEN o.id = :primaryOfficeId THEN 0 ELSE 1 END, o.code
                        """)
                .param("globalAccess", officeScope.globalAccess())
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .param("officeIds", officeScope.sqlOfficeIds())
                .param("primaryOfficeId", officeScope.primaryOfficeId())
                .query((rs, rowNum) -> new AccessibleOffice(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name_zh"),
                        rs.getString("name_en"),
                        rs.getString("timezone"),
                        rs.getString("default_currency"),
                        rs.getBoolean("manageable")
                ))
                .list();
        return new CurrentUserResponse(
                actor.userId(),
                actor.organizationId(),
                actor.username(),
                actor.displayName(),
                authorities,
                roles,
                permissions,
                preference.preferredLocale(),
                preference.primaryOfficeId(),
                preference.officeNameZh(),
                preference.officeNameEn(),
                preference.timezone(),
                preference.defaultCurrency(),
                officeScope.globalAccess(),
                accessibleOffices
        );
    }

    record CurrentUserResponse(
            java.util.UUID userId,
            java.util.UUID organizationId,
            String username,
            String displayName,
            List<String> authorities,
            List<String> roles,
            List<String> permissions,
            String preferredLocale,
            UUID primaryOfficeId,
            String officeNameZh,
            String officeNameEn,
            String timezone,
            String defaultCurrency,
            boolean globalOfficeAccess,
            List<AccessibleOffice> accessibleOffices
    ) {}

    record AccessibleOffice(
            UUID id,
            String code,
            String nameZh,
            String nameEn,
            String timezone,
            String defaultCurrency,
            boolean manageable
    ) {}

    private record UserPreference(
            String preferredLocale,
            UUID primaryOfficeId,
            String officeNameZh,
            String officeNameEn,
            String timezone,
            String defaultCurrency
    ) {}
}
