package com.zoro.legaloa.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DocumentGrantPolicyTest {
    @Test
    void normalizesSupportedPermission() {
        assertEquals("DOWNLOAD", DocumentGrantPolicy.normalizePermission(" download "));
    }

    @Test
    void rejectsUnsupportedPermission() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentGrantPolicy.normalizePermission("OWNER")
        );
    }

    @Test
    void rejectsExpiredGrant() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentGrantPolicy.requireFutureExpiry(now, now)
        );
    }

    @Test
    void acceptsNoExpiryOrFutureExpiry() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        DocumentGrantPolicy.requireFutureExpiry(null, now);
        DocumentGrantPolicy.requireFutureExpiry(now.plusSeconds(1), now);
    }
}
