package com.zoro.legaloa.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentSecurityPolicyTest {
    @Test
    void allowsNormalDownloadVolume() {
        assertThat(DocumentSecurityPolicy.evaluate(0))
                .isEqualTo(DocumentSecurityPolicy.Decision.ALLOW);
    }

    @Test
    void warnsExactlyAtBurstThreshold() {
        assertThat(DocumentSecurityPolicy.evaluate(9))
                .isEqualTo(DocumentSecurityPolicy.Decision.ALLOW_AND_WARN);
    }

    @Test
    void blocksAtHardThreshold() {
        assertThat(DocumentSecurityPolicy.evaluate(30))
                .isEqualTo(DocumentSecurityPolicy.Decision.BLOCK);
    }
}
