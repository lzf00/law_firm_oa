package com.zoro.legaloa.workflow;

import com.zoro.legaloa.common.BusinessException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class WorkflowPolicy {
    private static final Map<String, Definition> DEFINITIONS = Map.of(
            "MATTER", new Definition(
                    "matterIntake", "CONFLICT_REVIEW", "ACTIVE", "matters", "案件立案"
            ),
            "CONTRACT", new Definition(
                    "contractApproval", "REVIEWING", "APPROVED", "contracts", "合同"
            ),
            "SEAL_REQUEST", new Definition(
                    "sealRequest", "SUBMITTED", "APPROVED", "seal_requests", "用印"
            ),
            "LEAVE_REQUEST", new Definition(
                    "leaveApproval", "SUBMITTED", "APPROVED", "leave_requests", "请假"
            ),
            "EXPENSE_CLAIM", new Definition(
                    "expenseApproval", "SUBMITTED", "APPROVED", "expense_claims", "报销"
            )
    );

    private WorkflowPolicy() {}

    public static String normalizeBusinessType(String businessType) {
        String normalized = businessType.toUpperCase(Locale.ROOT);
        definition(normalized);
        return normalized;
    }

    public static String processKey(String businessType) {
        return definition(businessType).processKey();
    }

    public static String reviewState(String businessType) {
        return definition(businessType).reviewState();
    }

    public static String approvedState(String businessType) {
        return definition(businessType).approvedState();
    }

    public static String businessTable(String businessType) {
        return definition(businessType).table();
    }

    public static String businessLabel(String businessType) {
        return definition(businessType).label();
    }

    public static String normalizeDecision(String suppliedDecision, Map<String, Object> variables) {
        if (suppliedDecision != null && !suppliedDecision.isBlank()) {
            String decision = suppliedDecision.toUpperCase(Locale.ROOT);
            if (!Set.of("APPROVE", "REJECT").contains(decision)) {
                throw new BusinessException(
                        "WORKFLOW_DECISION_INVALID",
                        "审批决定只能是 APPROVE 或 REJECT",
                        HttpStatus.BAD_REQUEST
                );
            }
            return decision;
        }
        if (variables != null && Boolean.FALSE.equals(variables.get("approved"))) {
            return "REJECT";
        }
        return "APPROVE";
    }

    private static Definition definition(String businessType) {
        Definition definition = DEFINITIONS.get(businessType);
        if (definition == null) {
            throw new BusinessException(
                    "WORKFLOW_TYPE_INVALID", "不支持的审批业务类型", HttpStatus.BAD_REQUEST
            );
        }
        return definition;
    }

    private record Definition(
            String processKey,
            String reviewState,
            String approvedState,
            String table,
            String label
    ) {}
}
