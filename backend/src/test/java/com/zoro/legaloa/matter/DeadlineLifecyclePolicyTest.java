package com.zoro.legaloa.matter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zoro.legaloa.common.BusinessException;
import org.junit.jupiter.api.Test;

class DeadlineLifecyclePolicyTest {
    @Test
    void activeAndOverdueDeadlinesRemainActionable() {
        assertThat(DeadlineLifecyclePolicy.canEdit("OPEN")).isTrue();
        assertThat(DeadlineLifecyclePolicy.canComplete("OVERDUE")).isTrue();
        assertThat(DeadlineLifecyclePolicy.canCancel("OPEN")).isTrue();
        assertThat(DeadlineLifecyclePolicy.canEdit("COMPLETED")).isFalse();
        assertThat(DeadlineLifecyclePolicy.canComplete("CANCELLED")).isFalse();
    }

    @Test
    void onlyTerminalDeadlinesCanBeReopened() {
        assertThat(DeadlineLifecyclePolicy.canReopen("COMPLETED")).isTrue();
        assertThat(DeadlineLifecyclePolicy.canReopen("CANCELLED")).isTrue();
        assertThat(DeadlineLifecyclePolicy.canReopen("OPEN")).isFalse();
        assertThat(DeadlineLifecyclePolicy.canReopen("OVERDUE")).isFalse();
    }

    @Test
    void normalizesSupportedLegalDeadlineMetadata() {
        assertThat(DeadlineLifecyclePolicy.priority(null)).isEqualTo("NORMAL");
        assertThat(DeadlineLifecyclePolicy.priority("urgent")).isEqualTo("URGENT");
        assertThat(DeadlineLifecyclePolicy.type("court")).isEqualTo("COURT");
        assertThat(DeadlineLifecyclePolicy.source("court_order")).isEqualTo("COURT_ORDER");
    }

    @Test
    void rejectsUnknownLegalDeadlineMetadata() {
        assertThatThrownBy(() -> DeadlineLifecyclePolicy.priority("CRITICAL"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("DEADLINE_PRIORITY_INVALID"));
        assertThatThrownBy(() -> DeadlineLifecyclePolicy.type("PERSONAL"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("DEADLINE_TYPE_INVALID"));
        assertThatThrownBy(() -> DeadlineLifecyclePolicy.source("EMAIL"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("DEADLINE_SOURCE_INVALID"));
    }
}
