package com.zoro.legaloa.identity;

import java.time.Instant;

final class OfficeMembershipPolicy {
    private OfficeMembershipPolicy() {}

    static boolean active(Instant validUntil, Instant now) {
        return validUntil == null || validUntil.isAfter(now);
    }

    static boolean canGrant(
            boolean globalAdministrator,
            String requestedAccessLevel,
            boolean primary
    ) {
        return globalAdministrator
                || ("MEMBER".equals(requestedAccessLevel) && !primary);
    }

    static boolean validPrimaryExpiry(boolean primary, Instant validUntil) {
        return !primary || validUntil == null;
    }

    static boolean canModifyExisting(
            boolean globalAdministrator,
            String existingAccessLevel,
            boolean existingPrimary
    ) {
        return globalAdministrator
                || (!"MANAGER".equals(existingAccessLevel) && !existingPrimary);
    }
}
