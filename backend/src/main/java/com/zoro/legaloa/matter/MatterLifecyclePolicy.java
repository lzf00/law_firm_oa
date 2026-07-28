package com.zoro.legaloa.matter;

import java.util.Map;
import java.util.Set;

public final class MatterLifecyclePolicy {
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "INTAKE", Set.of("CONFLICT_REVIEW"),
            "CONFLICT_REVIEW", Set.of("ACTIVE", "REJECTED"),
            "ACTIVE", Set.of("SUSPENDED", "CLOSED"),
            "SUSPENDED", Set.of("ACTIVE", "CLOSED"),
            "CLOSED", Set.of("ARCHIVED")
    );

    private MatterLifecyclePolicy() {}

    public static boolean canTransition(String current, String target) {
        return ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }
}
