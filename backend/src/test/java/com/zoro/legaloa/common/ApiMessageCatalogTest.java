package com.zoro.legaloa.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class ApiMessageCatalogTest {
    @Test
    void translatesKnownCodeToEnglish() {
        assertThat(ApiMessageCatalog.message(
                "OFFICE_INVALID", "承办办公室不存在或已停用", Locale.ENGLISH
        )).isEqualTo("The responsible office does not exist or is inactive");
    }

    @Test
    void preservesChineseFallbackForChineseLocale() {
        assertThat(ApiMessageCatalog.message(
                "OFFICE_INVALID", "承办办公室不存在或已停用", Locale.SIMPLIFIED_CHINESE
        )).isEqualTo("承办办公室不存在或已停用");
    }

    @Test
    void preservesFallbackForUnknownEnglishCode() {
        assertThat(ApiMessageCatalog.message(
                "FUTURE_CODE", "Safe fallback", Locale.ENGLISH
        )).isEqualTo("Safe fallback");
    }

    @Test
    void treatsNullLocaleAsDefaultChineseResponse() {
        assertThat(ApiMessageCatalog.message(
                "INTERNAL_ERROR", "系统处理失败，请联系管理员", null
        )).isEqualTo("系统处理失败，请联系管理员");
    }

    @Test
    void translatesOfficeScopeDenialsWithoutChangingStableCode() {
        assertThat(ApiMessageCatalog.message(
                "OFFICE_ACCESS_DENIED", "无权访问所选办公室", Locale.ENGLISH
        )).isEqualTo("You do not have access to the selected office");
        assertThat(ApiMessageCatalog.message(
                "USER_OFFICE_MISMATCH", "所选用户不属于该办公室", Locale.ENGLISH
        )).isEqualTo("The selected user is not assigned to this office");
    }
}
