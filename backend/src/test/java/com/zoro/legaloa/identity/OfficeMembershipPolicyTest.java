package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OfficeMembershipPolicyTest {
    @Test
    void localManagerCanOnlyGrantNonPrimaryMemberAccess() {
        assertThat(OfficeMembershipPolicy.canGrant(false, "MEMBER", false)).isTrue();
        assertThat(OfficeMembershipPolicy.canGrant(false, "MANAGER", false)).isFalse();
        assertThat(OfficeMembershipPolicy.canGrant(false, "MEMBER", true)).isFalse();
    }

    @Test
    void globalAdministratorCanGrantManagerAndPrimaryAccess() {
        assertThat(OfficeMembershipPolicy.canGrant(true, "MANAGER", true)).isTrue();
    }

    @Test
    void localManagerCannotModifyAnotherManagerOrPrimaryMembership() {
        assertThat(OfficeMembershipPolicy.canModifyExisting(false, "MANAGER", false))
                .isFalse();
        assertThat(OfficeMembershipPolicy.canModifyExisting(false, "MEMBER", true))
                .isFalse();
        assertThat(OfficeMembershipPolicy.canModifyExisting(false, "MEMBER", false))
                .isTrue();
        assertThat(OfficeMembershipPolicy.canModifyExisting(true, "MANAGER", true))
                .isTrue();
    }

    @Test
    void expiringPrimaryMembershipIsRejected() {
        assertThat(OfficeMembershipPolicy.validPrimaryExpiry(true, Instant.now().plusSeconds(60)))
                .isFalse();
        assertThat(OfficeMembershipPolicy.validPrimaryExpiry(true, null)).isTrue();
    }

    @Test
    void expiredTemporaryMembershipIsInactive() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        assertThat(OfficeMembershipPolicy.active(now.minusSeconds(1), now)).isFalse();
        assertThat(OfficeMembershipPolicy.active(now.plusSeconds(1), now)).isTrue();
        assertThat(OfficeMembershipPolicy.active(null, now)).isTrue();
    }
}
