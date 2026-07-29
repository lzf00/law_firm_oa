package com.zoro.legaloa.matter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContractLifecyclePolicyTest {
    @Test
    void locksCommercialFieldsAfterReviewStarts() {
        assertTrue(ContractLifecyclePolicy.canEdit("DRAFT"));
        assertTrue(ContractLifecyclePolicy.canEdit("REJECTED"));
        assertFalse(ContractLifecyclePolicy.canEdit("REVIEWING"));
        assertFalse(ContractLifecyclePolicy.canEdit("SIGNED"));
    }

    @Test
    void finalizationRequiresApprovedContractAndDraftOrReviewedVersion() {
        assertTrue(ContractLifecyclePolicy.canCreateVersion("DRAFT"));
        assertFalse(ContractLifecyclePolicy.canCreateVersion("APPROVED"));
        assertTrue(ContractLifecyclePolicy.canFinalize("APPROVED", "DRAFT"));
        assertTrue(ContractLifecyclePolicy.canFinalize("APPROVED", "REVIEWED"));
        assertFalse(ContractLifecyclePolicy.canFinalize("REVIEWING", "DRAFT"));
        assertFalse(ContractLifecyclePolicy.canFinalize("APPROVED", "FINAL"));
    }

    @Test
    void signatureArchiveRequiresFinalUnsignedVersion() {
        assertTrue(ContractLifecyclePolicy.canArchiveSignature("APPROVED", "FINAL", "PENDING"));
        assertFalse(ContractLifecyclePolicy.canArchiveSignature("DRAFT", "FINAL", "PENDING"));
        assertFalse(ContractLifecyclePolicy.canArchiveSignature("APPROVED", "DRAFT", "PENDING"));
        assertFalse(ContractLifecyclePolicy.canArchiveSignature("APPROVED", "FINAL", "SIGNED"));
    }
}
