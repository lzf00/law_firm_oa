package com.zoro.legaloa.identity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.zoro.legaloa.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class TenantController {
    private final JdbcClient jdbcClient;
    private final String configuredOrganizationId;

    public TenantController(
            JdbcClient jdbcClient,
            @Value("${app.tenant.organization-id:}") String configuredOrganizationId
    ) {
        this.jdbcClient = jdbcClient;
        this.configuredOrganizationId = configuredOrganizationId;
    }

    @GetMapping("/tenant-config")
    TenantConfiguration configuration() {
        UUID organizationId = resolveOrganizationId();
        return configuration(organizationId);
    }

    private UUID resolveOrganizationId() {
        if (configuredOrganizationId != null && !configuredOrganizationId.isBlank()) {
            try {
                return UUID.fromString(configuredOrganizationId);
            } catch (IllegalArgumentException exception) {
                throw tenantNotConfigured();
            }
        }
        List<UUID> organizations = jdbcClient.sql("""
                        SELECT id
                        FROM organizations
                        WHERE status = 'ACTIVE' AND deleted_at IS NULL
                        """)
                .query(UUID.class)
                .list();
        if (organizations.size() != 1) {
            throw tenantNotConfigured();
        }
        return organizations.getFirst();
    }

    private TenantConfiguration configuration(UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT o.id,
                               COALESCE(s.brand_name_zh, o.name) AS brand_name_zh,
                               COALESCE(s.brand_name_en, o.name) AS brand_name_en,
                               COALESCE(s.short_name_zh, o.name) AS short_name_zh,
                               COALESCE(s.short_name_en, o.name) AS short_name_en,
                               COALESCE(s.default_locale, 'zh-CN') AS default_locale,
                               COALESCE(s.supported_locales, ARRAY['zh-CN', 'en-US']) AS supported_locales,
                               COALESCE(s.primary_timezone, 'Asia/Shanghai') AS primary_timezone,
                               COALESCE(s.base_currency, 'CNY') AS base_currency,
                               s.website_url
                        FROM organizations o
                        LEFT JOIN organization_settings s ON s.organization_id = o.id
                        WHERE o.id = :organizationId
                          AND o.status = 'ACTIVE' AND o.deleted_at IS NULL
                        """)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new TenantConfiguration(
                        rs.getObject("id", UUID.class),
                        rs.getString("brand_name_zh"),
                        rs.getString("brand_name_en"),
                        rs.getString("short_name_zh"),
                        rs.getString("short_name_en"),
                        rs.getString("default_locale"),
                        Arrays.asList((String[]) rs.getArray("supported_locales").getArray()),
                        rs.getString("primary_timezone"),
                        rs.getString("base_currency"),
                        rs.getString("website_url")
                ))
                .optional()
                .orElseThrow(TenantController::tenantNotConfigured);
    }

    private static BusinessException tenantNotConfigured() {
        return new BusinessException(
                "TENANT_NOT_CONFIGURED",
                "系统尚未配置有效的所属组织",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    public record TenantConfiguration(
            UUID organizationId,
            String brandNameZh,
            String brandNameEn,
            String shortNameZh,
            String shortNameEn,
            String defaultLocale,
            List<String> supportedLocales,
            String primaryTimezone,
            String baseCurrency,
            String websiteUrl
    ) {}
}
