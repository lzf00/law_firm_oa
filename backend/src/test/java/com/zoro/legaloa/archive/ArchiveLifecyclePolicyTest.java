package com.zoro.legaloa.archive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchiveLifecyclePolicyTest {
    @Test
    void onlyOpenVolumesCanBeEdited() {
        assertThat(ArchiveLifecyclePolicy.canEdit("OPEN")).isTrue();
        assertThat(ArchiveLifecyclePolicy.canEdit("ARCHIVED")).isFalse();
        assertThat(ArchiveLifecyclePolicy.canEdit("UNKNOWN")).isFalse();
    }

    @Test
    void closingRequiresAnOpenVolumeWithAtLeastOneAvailableItem() {
        assertThat(ArchiveLifecyclePolicy.canClose("OPEN", 1, 0)).isTrue();
        assertThat(ArchiveLifecyclePolicy.canClose("OPEN", 0, 0)).isFalse();
        assertThat(ArchiveLifecyclePolicy.canClose("OPEN", 2, 1)).isFalse();
        assertThat(ArchiveLifecyclePolicy.canClose("ARCHIVED", 2, 0)).isFalse();
    }
}
