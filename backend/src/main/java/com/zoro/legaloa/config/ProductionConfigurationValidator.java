package com.zoro.legaloa.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionConfigurationValidator {
    private final Settings settings;

    public ProductionConfigurationValidator(
            @Value("${app.auth.mode}") String authMode,
            @Value("${app.tenant.organization-id}") String organizationId,
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${spring.datasource.username}") String databaseUsername,
            @Value("${spring.datasource.password}") String databasePassword,
            @Value("${spring.data.redis.host}") String redisHost,
            @Value("${app.storage.endpoint}") String storageEndpoint,
            @Value("${app.storage.public-endpoint}") String storagePublicEndpoint,
            @Value("${app.storage.access-key}") String storageAccessKey,
            @Value("${app.storage.secret-key}") String storageSecretKey,
            @Value("${app.dingtalk.client-id}") String dingTalkClientId,
            @Value("${app.dingtalk.client-secret}") String dingTalkClientSecret,
            @Value("${app.dingtalk.redirect-uri}") String dingTalkRedirectUri,
            @Value("${app.document.scanner.mode}") String scannerMode,
            @Value("${app.document.scanner.host}") String scannerHost
    ) {
        this.settings = new Settings(
                authMode,
                organizationId,
                databaseUrl,
                databaseUsername,
                databasePassword,
                redisHost,
                storageEndpoint,
                storagePublicEndpoint,
                storageAccessKey,
                storageSecretKey,
                dingTalkClientId,
                dingTalkClientSecret,
                dingTalkRedirectUri,
                scannerMode,
                scannerHost
        );
    }

    @PostConstruct
    void validateAtStartup() {
        List<String> errors = validate(settings);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration invalid: " + String.join("; ", errors)
            );
        }
    }

    static List<String> validate(Settings settings) {
        List<String> errors = new ArrayList<>();
        if (!"dingtalk".equals(settings.authMode())) {
            errors.add("app.auth.mode must be dingtalk");
        }
        requireOrganizationId(settings.organizationId(), errors);
        requireSecret("database username", settings.databaseUsername(), errors);
        requireSecret("database password", settings.databasePassword(), errors);
        requireHost("Redis host", settings.redisHost(), errors);
        requireSecret("storage access key", settings.storageAccessKey(), errors);
        requireSecret("storage secret key", settings.storageSecretKey(), errors);
        requireSecret("DingTalk client ID", settings.dingTalkClientId(), errors);
        requireSecret("DingTalk client secret", settings.dingTalkClientSecret(), errors);
        if (!"clamav".equalsIgnoreCase(settings.scannerMode())) {
            errors.add("app.document.scanner.mode must be clamav");
        }
        requireHost("ClamAV host", settings.scannerHost(), errors);

        if (isPlaceholder(settings.databaseUrl())
                || !settings.databaseUrl().startsWith("jdbc:postgresql://")) {
            errors.add("database URL must be a non-placeholder PostgreSQL JDBC URL");
        }
        requireHttpUri("storage endpoint", settings.storageEndpoint(), false, errors);
        requireHttpUri("storage public endpoint", settings.storagePublicEndpoint(), true, errors);
        requireHttpUri("DingTalk redirect URI", settings.dingTalkRedirectUri(), true, errors);
        return List.copyOf(errors);
    }

    private static void requireSecret(String name, String value, List<String> errors) {
        if (isPlaceholder(value)) {
            errors.add(name + " must be configured with a non-placeholder value");
        }
    }

    private static void requireOrganizationId(String value, List<String> errors) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            errors.add("app.tenant.organization-id must be a valid UUID");
        }
    }

    private static void requireHost(String name, String value, List<String> errors) {
        if (isPlaceholder(value) || isLocalHost(value)) {
            errors.add(name + " must be configured with a non-local, non-placeholder value");
        }
    }

    private static void requireHttpUri(
            String name,
            String value,
            boolean httpsRequired,
            List<String> errors
    ) {
        if (isPlaceholder(value)) {
            errors.add(name + " must be configured with a non-placeholder value");
            return;
        }
        try {
            URI uri = new URI(value);
            boolean schemeAllowed = httpsRequired
                    ? "https".equalsIgnoreCase(uri.getScheme())
                    : "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            if (!schemeAllowed || uri.getHost() == null || isLocalHost(uri.getHost())) {
                errors.add(name + (httpsRequired
                        ? " must be a non-local HTTPS URL"
                        : " must be a non-local HTTP(S) URL"));
            }
        } catch (URISyntaxException exception) {
            errors.add(name + " must be a valid URL");
        }
    }

    private static boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("replace_with")
                || normalized.contains("change_me")
                || normalized.contains("changeme")
                || normalized.contains("example.com")
                || normalized.endsWith(".invalid");
    }

    private static boolean isLocalHost(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized);
    }

    record Settings(
            String authMode,
            String organizationId,
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String redisHost,
            String storageEndpoint,
            String storagePublicEndpoint,
            String storageAccessKey,
            String storageSecretKey,
            String dingTalkClientId,
            String dingTalkClientSecret,
            String dingTalkRedirectUri,
            String scannerMode,
            String scannerHost
    ) {}
}
