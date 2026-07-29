package com.zoro.legaloa.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zoro.legaloa.common.BusinessException;
import org.junit.jupiter.api.Test;

class WorkTaskLifecyclePolicyTest {
    @Test
    void supportsTheExplicitTaskLifecycle() {
        assertThat(WorkTaskLifecyclePolicy.canTransition("TODO", "IN_PROGRESS")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.canTransition("IN_PROGRESS", "DONE")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.canTransition("DONE", "IN_PROGRESS")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.canTransition("CANCELLED", "TODO")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.canTransition("DONE", "TODO")).isFalse();
        assertThat(WorkTaskLifecyclePolicy.canTransition("CANCELLED", "DONE")).isFalse();
    }

    @Test
    void requiresEvidenceForCancellationAndReopening() {
        assertThat(WorkTaskLifecyclePolicy.requiresNote("TODO", "CANCELLED")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.requiresNote("DONE", "IN_PROGRESS")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.requiresNote("CANCELLED", "TODO")).isTrue();
        assertThat(WorkTaskLifecyclePolicy.requiresNote("IN_PROGRESS", "DONE")).isFalse();
    }

    @Test
    void normalizesMetadataAndNamesLifecycleActions() {
        assertThat(WorkTaskLifecyclePolicy.priority(null)).isEqualTo("NORMAL");
        assertThat(WorkTaskLifecyclePolicy.priority("urgent")).isEqualTo("URGENT");
        assertThat(WorkTaskLifecyclePolicy.status("in_progress")).isEqualTo("IN_PROGRESS");
        assertThat(WorkTaskLifecyclePolicy.action("IN_PROGRESS", "DONE")).isEqualTo("COMPLETED");
        assertThat(WorkTaskLifecyclePolicy.action("DONE", "IN_PROGRESS")).isEqualTo("REOPENED");
    }

    @Test
    void rejectsUnknownMetadata() {
        assertThatThrownBy(() -> WorkTaskLifecyclePolicy.priority("CRITICAL"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("WORK_TASK_PRIORITY_INVALID"));
        assertThatThrownBy(() -> WorkTaskLifecyclePolicy.status("BLOCKED"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("WORK_TASK_STATUS_INVALID"));
    }
}
