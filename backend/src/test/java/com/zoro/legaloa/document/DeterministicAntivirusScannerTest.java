package com.zoro.legaloa.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeterministicAntivirusScannerTest {
    private final DeterministicAntivirusScanner scanner = new DeterministicAntivirusScanner();

    @Test
    void returnsCleanForOrdinaryContent() throws Exception {
        var result = scanner.scan(
                new ByteArrayInputStream("ordinary legal document".getBytes(StandardCharsets.UTF_8)),
                new AntivirusScanner.ScanMetadata("memo.txt", "text/plain", 23)
        );
        assertThat(result.status()).isEqualTo(AntivirusScanner.Status.CLEAN);
    }

    @Test
    void detectsEicarAcrossStreamContent() throws Exception {
        String eicar = "prefix-X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE";
        var result = scanner.scan(
                new ByteArrayInputStream(eicar.getBytes(StandardCharsets.US_ASCII)),
                new AntivirusScanner.ScanMetadata("eicar.txt", "text/plain", eicar.length())
        );
        assertThat(result.status()).isEqualTo(AntivirusScanner.Status.INFECTED);
        assertThat(result.signature()).isEqualTo("EICAR-Test-Signature");
    }

    @Test
    void exposesDeterministicFailureForFailClosedIntegrationTests() throws Exception {
        String marker = "ZORO_TEST_SCANNER_FAILURE";
        var result = scanner.scan(
                new ByteArrayInputStream(marker.getBytes(StandardCharsets.US_ASCII)),
                new AntivirusScanner.ScanMetadata("failure.txt", "text/plain", marker.length())
        );
        assertThat(result.status()).isEqualTo(AntivirusScanner.Status.FAILED);
    }
}
