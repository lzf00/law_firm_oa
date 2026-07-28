package com.zoro.legaloa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionConfigurationValidatorTest {
    @Test
    void acceptsCompleteProductionConfiguration() {
        List<String> errors = ProductionConfigurationValidator.validate(validSettings());

        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsDevModePlaceholdersAndLocalServices() {
        ProductionConfigurationValidator.Settings settings =
                new ProductionConfigurationValidator.Settings(
                        "dev",
                        "jdbc:postgresql://localhost:5432/law_oa",
                        "law_oa",
                        "replace_with_secret_manager_reference",
                        "localhost",
                        "http://localhost:9000",
                        "http://localhost:9000",
                        "replace_with_key",
                        "changeme",
                        "replace_with_dingtalk_app_key",
                        "change_me",
                        "http://localhost/auth/dingtalk/callback"
                );

        List<String> errors = ProductionConfigurationValidator.validate(settings);

        assertThat(errors)
                .hasSizeGreaterThanOrEqualTo(9)
                .anyMatch(message -> message.contains("app.auth.mode"))
                .anyMatch(message -> message.contains("database password"))
                .anyMatch(message -> message.contains("Redis host"))
                .anyMatch(message -> message.contains("storage public endpoint"))
                .anyMatch(message -> message.contains("DingTalk redirect URI"));
    }

    @Test
    void requiresHttpsForBrowserFacingEndpoints() {
        ProductionConfigurationValidator.Settings base = validSettings();
        ProductionConfigurationValidator.Settings settings =
                new ProductionConfigurationValidator.Settings(
                        base.authMode(),
                        base.databaseUrl(),
                        base.databaseUsername(),
                        base.databasePassword(),
                        base.redisHost(),
                        base.storageEndpoint(),
                        "http://files.launch-check.test",
                        base.storageAccessKey(),
                        base.storageSecretKey(),
                        base.dingTalkClientId(),
                        base.dingTalkClientSecret(),
                        "http://oa.launch-check.test/auth/dingtalk/callback"
                );

        assertThat(ProductionConfigurationValidator.validate(settings))
                .containsExactlyInAnyOrder(
                        "storage public endpoint must be a non-local HTTPS URL",
                        "DingTalk redirect URI must be a non-local HTTPS URL"
                );
    }

    private ProductionConfigurationValidator.Settings validSettings() {
        return new ProductionConfigurationValidator.Settings(
                "dingtalk",
                "jdbc:postgresql://postgres.launch-check.internal:5432/law_oa",
                "law_oa_app",
                "database-secret",
                "redis.launch-check.internal",
                "http://minio.launch-check.internal:9000",
                "https://files.launch-check.test",
                "storage-access-key",
                "storage-secret-key",
                "ding-client-id",
                "ding-client-secret",
                "https://oa.launch-check.test/auth/dingtalk/callback"
        );
    }
}
