package com.zoro.legaloa.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminAccessRiskPolicyTest {
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void reportsInactiveUnscopedAndRolelessAccounts() {
        var warnings = AdminAccessRiskPolicy.warnings(
                "SUSPENDED", List.of(), List.of(), NOW
        );

        assertThat(warnings).containsExactly(
                "ACCOUNT_NOT_ACTIVE",
                "NO_ROLE",
                "NO_OFFICE_SCOPE"
        );
    }

    @Test
    void reportsExpiredAndSoonExpiringOfficeAssignments() {
        var warnings = AdminAccessRiskPolicy.warnings(
                "ACTIVE",
                List.of("LAWYER"),
                List.of(
                        new AdminAccessRiskPolicy.OfficeGrant(
                                false, NOW.minus(1, ChronoUnit.HOURS)
                        ),
                        new AdminAccessRiskPolicy.OfficeGrant(
                                true, NOW.plus(3, ChronoUnit.DAYS)
                        )
                ),
                NOW
        );

        assertThat(warnings).containsExactly(
                "EXPIRED_OFFICE_ACCESS",
                "ACCESS_EXPIRING_SOON"
        );
    }

    @Test
    void doesNotReportRisksForAnActiveScopedUser() {
        var warnings = AdminAccessRiskPolicy.warnings(
                "ACTIVE",
                List.of("LAWYER"),
                List.of(new AdminAccessRiskPolicy.OfficeGrant(true, null)),
                NOW
        );

        assertThat(warnings).isEmpty();
    }
}
