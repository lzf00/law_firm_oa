package com.zoro.legaloa.matter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matters")
public class MatterController {
    private final MatterService matterService;
    private final MatterWorkspaceService matterWorkspaceService;

    public MatterController(
            MatterService matterService,
            MatterWorkspaceService matterWorkspaceService
    ) {
        this.matterService = matterService;
        this.matterWorkspaceService = matterWorkspaceService;
    }

    @GetMapping
    List<MatterSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query
    ) {
        return matterService.list(status, query);
    }

    @GetMapping("/{id}")
    MatterDetail get(@PathVariable UUID id) {
        return matterService.get(id);
    }

    @GetMapping("/{id}/workspace")
    MatterWorkspace workspace(@PathVariable UUID id) {
        return matterWorkspaceService.get(id);
    }

    @PostMapping
    MatterDetail create(@Valid @RequestBody CreateMatterRequest request) {
        return matterService.create(request);
    }

    @PutMapping("/{id}")
    MatterDetail update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMatterRequest request
    ) {
        return matterService.update(id, request);
    }

    @PostMapping("/{id}/lifecycle")
    MatterDetail transition(
            @PathVariable UUID id,
            @Valid @RequestBody MatterLifecycleRequest request
    ) {
        return matterService.transition(id, request);
    }

    public record CreateMatterRequest(
            @NotBlank @Size(max = 80) String matterNumber,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 80) String matterType,
            @NotNull UUID responsibleUserId,
            LocalDate openedAt,
            @Size(max = 300) String courtName,
            @Size(max = 150) String caseNumber,
            @Size(max = 4000) String description,
            UUID officeId,
            @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Size(max = 200) String jurisdiction,
            @Pattern(regexp = "^(zh-CN|en-US|ar)$") String workingLanguage,
            @Pattern(regexp = "^[A-Z]{3}$") String billingCurrency,
            List<@NotNull UUID> clientIds,
            List<@Valid MatterPartyInput> parties
    ) {}

    public record MatterPartyInput(
            @NotNull UUID partyId,
            @NotBlank @Size(max = 80) String partyRole,
            @NotBlank @Size(max = 32) String side
    ) {}

    public record UpdateMatterRequest(
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 80) String matterType,
            @NotNull UUID responsibleUserId,
            LocalDate openedAt,
            @Size(max = 300) String courtName,
            @Size(max = 150) String caseNumber,
            @Size(max = 4000) String description,
            UUID officeId,
            @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Size(max = 200) String jurisdiction,
            @Pattern(regexp = "^(zh-CN|en-US|ar)$") String workingLanguage,
            @Pattern(regexp = "^[A-Z]{3}$") String billingCurrency
    ) {}

    public record MatterLifecycleRequest(
            @NotBlank
            @Pattern(regexp = "^(CONFLICT_REVIEW|ACTIVE|SUSPENDED|CLOSED|ARCHIVED|REJECTED)$")
            String targetStatus,
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record MatterSummary(
            UUID id,
            String matterNumber,
            String title,
            String matterType,
            String status,
            String confidentialityLevel,
            UUID responsibleUserId,
            String responsibleName,
            LocalDate openedAt,
            int memberCount,
            UUID officeId,
            String officeNameZh,
            String officeNameEn,
            String countryCode,
            String jurisdiction,
            String workingLanguage,
            String billingCurrency
    ) {}

    public record MatterDetail(
            MatterSummary summary,
            String courtName,
            String caseNumber,
            String description,
            List<MatterPartyView> parties
    ) {}

    public record MatterPartyView(
            UUID partyId,
            String partyName,
            String partyRole,
            String side
    ) {}

    public record MatterWorkspace(
            MatterDetail detail,
            List<MatterMemberView> team,
            List<ConflictReference> conflicts,
            List<ContractReference> contracts,
            List<ApprovalReference> approvals,
            List<com.zoro.legaloa.archive.ArchiveController.ArchiveVolumeView> archives,
            List<com.zoro.legaloa.matter.DeadlineController.DeadlineView> deadlines,
            List<com.zoro.legaloa.document.DocumentController.DocumentView> documents,
            List<MatterEventView> activity
    ) {}

    public record MatterMemberView(
            UUID userId,
            String displayName,
            String memberRole,
            boolean canDownload,
            Instant joinedAt
    ) {}

    public record ConflictReference(
            UUID id,
            String requestNumber,
            String proposedMatterTitle,
            String status,
            String riskLevel,
            String decision,
            Instant createdAt
    ) {}

    public record ContractReference(
            UUID id,
            String contractNumber,
            String title,
            String status,
            BigDecimal amount,
            String currency
    ) {}

    public record ApprovalReference(
            UUID id,
            String businessType,
            UUID businessId,
            String processDefinitionKey,
            String status,
            String decision,
            Instant startedAt,
            Instant completedAt
    ) {}

    public record MatterEventView(
            UUID id,
            String eventType,
            String title,
            String description,
            Instant eventAt,
            String createdByName
    ) {}
}
