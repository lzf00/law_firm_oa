package com.zoro.legaloa.identity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class TenantController {
    private final JdbcClient jdbcClient;

    public TenantController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/tenant-config")
    TenantConfiguration configuration() {
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
                        WHERE o.status = 'ACTIVE' AND o.deleted_at IS NULL
                        ORDER BY o.created_at
                        LIMIT 1
                        """)
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
                .single();
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
