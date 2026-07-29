package com.zoro.legaloa.party;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

public final class ConflictCheckPolicy {
    private static final Set<String> ACTIVE_MATTER_STATES = Set.of(
            "ACTIVE", "INTAKE", "CONFLICT_REVIEW"
    );
    private static final Set<String> DECISIONS = Set.of(
            "CLEAR", "WAIVER_REQUIRED", "REJECT"
    );
    private static final Set<String> RISK_LEVELS = Set.of(
            "CLEAR", "MEDIUM", "HIGH"
    );

    private ConflictCheckPolicy() {}

    public static String riskLevel(Collection<String> matterStatuses) {
        if (matterStatuses.stream().anyMatch(ACTIVE_MATTER_STATES::contains)) {
            return "HIGH";
        }
        return matterStatuses.isEmpty() ? "CLEAR" : "MEDIUM";
    }

    public static String normalizeDecision(String decision) {
        String normalized = decision.toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported conflict decision");
        }
        return normalized;
    }

    public static String normalizeRiskLevel(String riskLevel) {
        String normalized = riskLevel.toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported conflict risk level");
        }
        return normalized;
    }

    public static String terminalStatus(String decision) {
        return "REJECT".equals(normalizeDecision(decision)) ? "REJECTED" : "APPROVED";
    }

    public static boolean canSubmit(String status) {
        return "DRAFT".equals(status);
    }

    public static boolean canDecide(String status) {
        return "SUBMITTED".equals(status) || "REVIEWING".equals(status);
    }
}
