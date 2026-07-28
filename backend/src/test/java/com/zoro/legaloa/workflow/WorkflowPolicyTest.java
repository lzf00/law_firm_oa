package com.zoro.legaloa.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zoro.legaloa.common.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowPolicyTest {
    @Test
    void shouldMapEverySupportedBusinessTypeToItsProcessAndStates() {
        assertThat(WorkflowPolicy.processKey("MATTER")).isEqualTo("matterIntake");
        assertThat(WorkflowPolicy.reviewState("MATTER")).isEqualTo("CONFLICT_REVIEW");
        assertThat(WorkflowPolicy.approvedState("MATTER")).isEqualTo("ACTIVE");

        assertThat(WorkflowPolicy.processKey("CONTRACT")).isEqualTo("contractApproval");
        assertThat(WorkflowPolicy.reviewState("CONTRACT")).isEqualTo("REVIEWING");
        assertThat(WorkflowPolicy.approvedState("CONTRACT")).isEqualTo("APPROVED");

        assertThat(WorkflowPolicy.processKey("SEAL_REQUEST")).isEqualTo("sealRequest");
        assertThat(WorkflowPolicy.reviewState("SEAL_REQUEST")).isEqualTo("SUBMITTED");
        assertThat(WorkflowPolicy.approvedState("SEAL_REQUEST")).isEqualTo("APPROVED");

        assertThat(WorkflowPolicy.processKey("LEAVE_REQUEST")).isEqualTo("leaveApproval");
        assertThat(WorkflowPolicy.businessTable("LEAVE_REQUEST")).isEqualTo("leave_requests");
        assertThat(WorkflowPolicy.processKey("EXPENSE_CLAIM")).isEqualTo("expenseApproval");
        assertThat(WorkflowPolicy.businessTable("EXPENSE_CLAIM")).isEqualTo("expense_claims");
    }

    @Test
    void shouldNormalizeBusinessTypeWithoutChangingSupportedMeaning() {
        assertThat(WorkflowPolicy.normalizeBusinessType("contract")).isEqualTo("CONTRACT");
    }

    @Test
    void shouldRejectUnsupportedBusinessType() {
        assertThatThrownBy(() -> WorkflowPolicy.normalizeBusinessType("EXPENSE"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不支持的审批业务类型");
    }

    @Test
    void shouldUseExplicitDecisionBeforeLegacyVariables() {
        assertThat(WorkflowPolicy.normalizeDecision("approve", Map.of("approved", false)))
                .isEqualTo("APPROVE");
    }

    @Test
    void shouldSupportLegacyApprovedFalseAsRejection() {
        assertThat(WorkflowPolicy.normalizeDecision(null, Map.of("approved", false)))
                .isEqualTo("REJECT");
    }

    @Test
    void shouldDefaultMissingDecisionToApprovalForBackwardCompatibility() {
        assertThat(WorkflowPolicy.normalizeDecision(null, Map.of())).isEqualTo("APPROVE");
    }

    @Test
    void shouldRejectUnknownDecision() {
        assertThatThrownBy(() -> WorkflowPolicy.normalizeDecision("SKIP", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APPROVE");
    }
}
