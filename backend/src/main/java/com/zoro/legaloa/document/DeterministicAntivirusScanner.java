package com.zoro.legaloa.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.document.scanner.mode",
        havingValue = "deterministic",
        matchIfMissing = true
)
public class DeterministicAntivirusScanner implements AntivirusScanner {
    private static final String EICAR_MARKER = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR";
    private static final String FAILURE_MARKER = "ZORO_TEST_SCANNER_FAILURE";

    @Override
    public ScanResult scan(InputStream content, ScanMetadata metadata) throws IOException {
        byte[] buffer = new byte[8192];
        String carry = "";
        int read;
        while ((read = content.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            String chunk = carry + new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
            if (chunk.contains(EICAR_MARKER)) {
                return ScanResult.infected("EICAR-Test-Signature");
            }
            if (chunk.contains(FAILURE_MARKER)) {
                return ScanResult.failed("Deterministic scanner failure");
            }
            int carryLength = Math.max(EICAR_MARKER.length(), FAILURE_MARKER.length());
            carry = chunk.substring(Math.max(0, chunk.length() - carryLength));
        }
        return ScanResult.clean();
    }

    @Override
    public String provider() {
        return "deterministic-test-scanner";
    }
}
