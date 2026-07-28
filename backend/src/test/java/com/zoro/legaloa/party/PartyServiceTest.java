package com.zoro.legaloa.party;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartyServiceTest {
    @Test
    void normalizesChineseOrganizationNamesForConflictSearch() {
        assertThat(PartyService.normalize(" 华辰科技（上海）有限公司 "))
                .isEqualTo("华辰科技上海有限公司");
    }

    @Test
    void normalizesUnicodeAndSeparatorsConsistently() {
        assertThat(PartyService.normalize("ＡＢＣ-贸易·集团"))
                .isEqualTo("abc贸易集团");
    }
}

