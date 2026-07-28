package com.zoro.legaloa.matter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MatterLifecyclePolicyTest {
    @Test
    void conflictReviewCanActivateOrReject() {
        assertThat(MatterLifecyclePolicy.canTransition("CONFLICT_REVIEW", "ACTIVE")).isTrue();
        assertThat(MatterLifecyclePolicy.canTransition("CONFLICT_REVIEW", "REJECTED")).isTrue();
    }

    @Test
    void activeCanSuspendOrClose() {
        assertThat(MatterLifecyclePolicy.canTransition("ACTIVE", "SUSPENDED")).isTrue();
        assertThat(MatterLifecyclePolicy.canTransition("ACTIVE", "CLOSED")).isTrue();
    }

    @Test
    void closedCanOnlyArchive() {
        assertThat(MatterLifecyclePolicy.canTransition("CLOSED", "ARCHIVED")).isTrue();
        assertThat(MatterLifecyclePolicy.canTransition("CLOSED", "ACTIVE")).isFalse();
    }

    @Test
    void terminalAndUnknownStatesCannotTransition() {
        assertThat(MatterLifecyclePolicy.canTransition("ARCHIVED", "ACTIVE")).isFalse();
        assertThat(MatterLifecyclePolicy.canTransition("REJECTED", "CONFLICT_REVIEW")).isFalse();
        assertThat(MatterLifecyclePolicy.canTransition("UNKNOWN", "ACTIVE")).isFalse();
    }
}
