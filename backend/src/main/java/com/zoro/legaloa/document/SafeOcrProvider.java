package com.zoro.legaloa.document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class SafeOcrProvider implements OcrProvider {
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    @Override
    public OcrResult extract(InputStream content, String contentType, String filename) {
        if (!"text/plain".equals(contentType)) {
            return new OcrResult(filename, "und", 1);
        }
        try {
            byte[] bytes = content.readNBytes(MAX_TEXT_BYTES + 1);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Text extraction input exceeds safe limit");
            }
            return new OcrResult(new String(bytes, StandardCharsets.UTF_8), "und", 1);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not extract text", exception);
        }
    }

    @Override
    public String provider() {
        return "SAFE_LOCAL_V1";
    }
}
