package com.zoro.legaloa.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictCheckPolicyTest {
    @Test
    void classifiesFirmWideHitsByMatterActivity() {
        assertEquals("CLEAR", ConflictCheckPolicy.riskLevel(List.of()));
        assertEquals("MEDIUM", ConflictCheckPolicy.riskLevel(List.of("CLOSED")));
        assertEquals("HIGH", ConflictCheckPolicy.riskLevel(List.of("CLOSED", "ACTIVE")));
    }

    @Test
    void normalizesDecisionAndMapsTerminalStatus() {
        assertEquals("CLEAR", ConflictCheckPolicy.normalizeDecision("clear"));
        assertEquals("APPROVED", ConflictCheckPolicy.terminalStatus("WAIVER_REQUIRED"));
        assertEquals("REJECTED", ConflictCheckPolicy.terminalStatus("REJECT"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ConflictCheckPolicy.normalizeDecision("IGNORE")
        );
    }

    @Test
    void enforcesSubmissionAndDecisionStates() {
        assertTrue(ConflictCheckPolicy.canSubmit("DRAFT"));
        assertFalse(ConflictCheckPolicy.canSubmit("SUBMITTED"));
        assertTrue(ConflictCheckPolicy.canDecide("SUBMITTED"));
        assertFalse(ConflictCheckPolicy.canDecide("APPROVED"));
    }
}
