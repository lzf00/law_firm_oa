package com.zoro.legaloa.document;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

final class DocumentGrantPolicy {
    private static final Set<String> PERMISSIONS =
            Set.of("PREVIEW", "DOWNLOAD", "EDIT", "SHARE");

    private DocumentGrantPolicy() {}

    static String normalizePermission(String permission) {
        String normalized = permission == null
                ? ""
                : permission.trim().toUpperCase(Locale.ROOT);
        if (!PERMISSIONS.contains(normalized)) {
            throw new IllegalArgumentException("DOCUMENT_GRANT_PERMISSION_INVALID");
        }
        return normalized;
    }

    static void requireFutureExpiry(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("DOCUMENT_GRANT_EXPIRY_INVALID");
        }
    }
}
