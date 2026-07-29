package com.zoro.legaloa.office;

import com.zoro.legaloa.common.BusinessException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class WorkTaskLifecyclePolicy {
    private static final Set<String> PRIORITIES =
            Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STATUSES =
            Set.of("TODO", "IN_PROGRESS", "DONE", "CANCELLED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "TODO", Set.of("IN_PROGRESS", "DONE", "CANCELLED"),
            "IN_PROGRESS", Set.of("TODO", "DONE", "CANCELLED"),
            "DONE", Set.of("IN_PROGRESS"),
            "CANCELLED", Set.of("TODO")
    );

    private WorkTaskLifecyclePolicy() {}

    public static String priority(String value) {
        return normalize(value, "NORMAL", PRIORITIES, "WORK_TASK_PRIORITY_INVALID");
    }

    public static String status(String value) {
        return normalize(value, null, STATUSES, "WORK_TASK_STATUS_INVALID");
    }

    public static boolean canTransition(String from, String to) {
        return from != null && to != null
                && (from.equals(to) || TRANSITIONS.getOrDefault(from, Set.of()).contains(to));
    }

    public static boolean requiresNote(String from, String to) {
        return "CANCELLED".equals(to)
                || ("DONE".equals(from) && "IN_PROGRESS".equals(to))
                || ("CANCELLED".equals(from) && "TODO".equals(to));
    }

    public static String action(String from, String to) {
        if (from.equals(to)) {
            return "STATUS_CONFIRMED";
        }
        if ("DONE".equals(to)) {
            return "COMPLETED";
        }
        if ("CANCELLED".equals(to)) {
            return "CANCELLED";
        }
        if ("DONE".equals(from) || "CANCELLED".equals(from)) {
            return "REOPENED";
        }
        return "STATUS_CHANGED";
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
