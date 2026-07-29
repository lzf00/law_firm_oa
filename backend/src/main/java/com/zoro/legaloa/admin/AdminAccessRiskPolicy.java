package com.zoro.legaloa.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class AdminAccessRiskPolicy {
    private static final Duration EXPIRY_WARNING_WINDOW = Duration.ofDays(7);

    private AdminAccessRiskPolicy() {}

    static List<String> warnings(
            String status,
            Collection<String> roleCodes,
            Collection<OfficeGrant> officeGrants,
            Instant now
    ) {
        List<String> warnings = new ArrayList<>();
        if (!"ACTIVE".equals(status)) {
            warnings.add("ACCOUNT_NOT_ACTIVE");
        }
        if (roleCodes.isEmpty()) {
            warnings.add("NO_ROLE");
        }
        if (officeGrants.isEmpty()) {
            warnings.add("NO_OFFICE_SCOPE");
            return List.copyOf(warnings);
        }
        boolean expired = officeGrants.stream()
                .map(OfficeGrant::validUntil)
                .filter(java.util.Objects::nonNull)
                .anyMatch(validUntil -> !validUntil.isAfter(now));
        if (expired) {
            warnings.add("EXPIRED_OFFICE_ACCESS");
        }
        boolean expiringSoon = officeGrants.stream()
                .map(OfficeGrant::validUntil)
                .filter(java.util.Objects::nonNull)
                .anyMatch(validUntil -> validUntil.isAfter(now)
                        && !validUntil.isAfter(now.plus(EXPIRY_WARNING_WINDOW)));
        if (expiringSoon) {
            warnings.add("ACCESS_EXPIRING_SOON");
        }
        return List.copyOf(warnings);
    }

    record OfficeGrant(boolean primary, Instant validUntil) {}
}
