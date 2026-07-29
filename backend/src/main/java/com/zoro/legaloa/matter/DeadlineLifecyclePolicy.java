package com.zoro.legaloa.matter;

import com.zoro.legaloa.common.BusinessException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class DeadlineLifecyclePolicy {
    private static final Set<String> PRIORITIES =
            Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> TYPES =
            Set.of(
                    "COURT", "COURT_DEADLINE", "ARBITRATION", "FILING",
                    "INTERNAL", "INTERNAL_TASK", "OTHER"
            );
    private static final Set<String> SOURCES =
            Set.of("COURT_ORDER", "STATUTE", "CLIENT", "INTERNAL", "OTHER");

    private DeadlineLifecyclePolicy() {}

    public static boolean canEdit(String status) {
        return "OPEN".equals(status) || "OVERDUE".equals(status);
    }

    public static boolean canComplete(String status) {
        return canEdit(status);
    }

    public static boolean canCancel(String status) {
        return canEdit(status);
    }

    public static boolean canReopen(String status) {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status);
    }

    public static String priority(String value) {
        return normalize(value, "NORMAL", PRIORITIES, "DEADLINE_PRIORITY_INVALID");
    }

    public static String type(String value) {
        return normalize(value, null, TYPES, "DEADLINE_TYPE_INVALID");
    }

    public static String source(String value) {
        return normalize(value, "OTHER", SOURCES, "DEADLINE_SOURCE_INVALID");
    }

    private static String normalize(
            String value,
            String fallback,
            Set<String> accepted,
            String errorCode
    ) {
        String normalized = value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !accepted.contains(normalized)) {
            throw new BusinessException(errorCode, errorCode, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
