package com.zoro.legaloa.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conflict-checks")
public class ConflictCheckController {
    private final ConflictCheckService conflictCheckService;

    public ConflictCheckController(ConflictCheckService conflictCheckService) {
        this.conflictCheckService = conflictCheckService;
    }

    @PostMapping("/preview")
    ConflictPreview preview(@Valid @RequestBody ConflictPreviewRequest request) {
        return conflictCheckService.preview(request);
    }

    @GetMapping
    List<ConflictCheckView> list() {
        return conflictCheckService.list();
    }

    @PostMapping
    ConflictCheckView create(@Valid @RequestBody CreateConflictCheckRequest request) {
        return conflictCheckService.create(request);
    }

    @PostMapping("/{id}/submit")
    ConflictCheckView submit(@PathVariable UUID id) {
        return conflictCheckService.submit(id);
    }

    @PostMapping("/{id}/decision")
    ConflictCheckView decide(
            @PathVariable UUID id,
            @Valid @RequestBody ConflictDecisionRequest request
    ) {
        return conflictCheckService.decide(id, request);
    }

    public record ConflictPreviewRequest(
            @NotBlank @Size(max = 300) String matterTitle,
            @NotEmpty List<@NotNull UUID> partyIds
    ) {}

    public record ConflictPartyRequest(
            @NotNull UUID partyId,
            @NotBlank @Size(max = 80) String proposedRole
    ) {}

    public record CreateConflictCheckRequest(
            UUID officeId,
            @NotBlank @Size(max = 300) String matterTitle,
            @NotEmpty List<@Valid ConflictPartyRequest> parties
    ) {}

    public record ConflictDecisionRequest(
            @NotBlank @Pattern(regexp = "^(CLEAR|WAIVER_REQUIRED|REJECT)$") String decision,
            @NotBlank @Pattern(regexp = "^(CLEAR|MEDIUM|HIGH)$") String riskLevel,
            @NotBlank @Size(max = 4000) String rationale,
            @Size(max = 4000) String mitigationPlan
    ) {}

    public record ConflictHit(
            UUID partyId,
            String partyName,
            UUID matterId,
            String matterNumber,
            String matterTitle,
            String partyRole,
            String side,
            String matterStatus,
            boolean restricted
    ) {}

    public record ConflictPreview(String riskLevel, int hitCount, List<ConflictHit> hits) {}

    public record ConflictActionView(
            UUID id,
            String action,
            UUID actorUserId,
            String actorName,
            String riskLevel,
            String decision,
            String rationale,
            String mitigationPlan,
            Instant occurredAt
    ) {}

    public record ConflictCheckView(
            UUID id,
            String requestNumber,
            UUID officeId,
            String officeNameZh,
            String officeNameEn,
            String matterTitle,
            String status,
            String riskLevel,
            String decision,
            UUID requestedBy,
            String requestedByName,
            UUID reviewedBy,
            String reviewedByName,
            String decisionRationale,
            String mitigationPlan,
            Instant createdAt,
            Instant submittedAt,
            Instant reviewedAt,
            boolean restrictedDetails,
            List<ConflictPartyRequest> parties,
            List<ConflictHit> hits,
            List<ConflictActionView> actions
    ) {}
}
