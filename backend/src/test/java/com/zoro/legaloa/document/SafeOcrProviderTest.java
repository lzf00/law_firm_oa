package com.zoro.legaloa.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SafeOcrProviderTest {
    private final SafeOcrProvider provider = new SafeOcrProvider();

    @Test
    void extractsBoundedPlainText() {
        var result = provider.extract(
                new ByteArrayInputStream("Privileged legal note".getBytes(StandardCharsets.UTF_8)),
                "text/plain",
                "note.txt"
        );
        assertThat(result.text()).isEqualTo("Privileged legal note");
        assertThat(result.pageCount()).isEqualTo(1);
    }

    @Test
    void doesNotAttemptUnsafeBinaryParsing() {
        var result = provider.extract(
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "application/pdf",
                "evidence.pdf"
        );
        assertThat(result.text()).isEqualTo("evidence.pdf");
    }

    @Test
    void rejectsOversizedText() {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> provider.extract(
                new ByteArrayInputStream(oversized), "text/plain", "large.txt"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
