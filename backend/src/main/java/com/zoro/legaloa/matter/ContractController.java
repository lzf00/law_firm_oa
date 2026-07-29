package com.zoro.legaloa.matter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {
    private final ContractService contractService;
    private final ContractLifecycleService lifecycleService;

    public ContractController(
            ContractService contractService,
            ContractLifecycleService lifecycleService
    ) {
        this.contractService = contractService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    List<ContractView> list() {
        return contractService.list();
    }

    @GetMapping("/{id}")
    ContractDetailView detail(@PathVariable UUID id) {
        return lifecycleService.detail(id);
    }

    @PostMapping
    ContractView create(@Valid @RequestBody CreateContractRequest request) {
        return contractService.create(request);
    }

    @PutMapping("/{id}")
    ContractView update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateContractRequest request
    ) {
        return contractService.update(id, request);
    }

    @PostMapping("/{id}/versions")
    ContractDetailView createVersion(
            @PathVariable UUID id,
            @Valid @RequestBody CreateContractVersionRequest request
    ) {
        return lifecycleService.createVersion(id, request);
    }

    @PostMapping("/{id}/versions/{versionId}/finalize")
    ContractDetailView finalizeVersion(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @Valid @RequestBody ContractLifecycleComment request
    ) {
        return lifecycleService.finalizeVersion(id, versionId, request);
    }

    @PostMapping("/{id}/versions/{versionId}/signed-file")
    ContractDetailView archiveSignedFile(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @Valid @RequestBody ArchiveSignedFileRequest request
    ) {
        return lifecycleService.archiveSignedFile(id, versionId, request);
    }

    public record CreateContractRequest(
            @NotBlank @Size(max = 80) String contractNumber,
            @NotBlank @Size(max = 300) String title,
            UUID clientId,
            @NotNull UUID responsibleUserId,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            BigDecimal amount,
            @Size(min = 3, max = 3) String currency,
            List<UUID> matterIds
    ) {}

    public record ContractView(
            UUID id,
            String contractNumber,
            String title,
            String status,
            UUID clientId,
            String clientName,
            UUID responsibleUserId,
            String responsibleName,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            BigDecimal amount,
            String currency,
            int matterCount,
            List<UUID> matterIds,
            Integer currentVersionNumber,
            String currentVersionStatus,
            String signatureStatus,
            Instant signedAt
    ) {}

    public record CreateContractVersionRequest(
            @NotNull UUID documentVersionId,
            @NotBlank @Size(max = 500) String summary
    ) {}

    public record ContractLifecycleComment(@Size(max = 1000) String comment) {}

    public record ArchiveSignedFileRequest(
            @NotNull UUID signedDocumentVersionId,
            @Size(max = 1000) String comment
    ) {}

    public record ContractVersionView(
            UUID id,
            int versionNumber,
            String status,
            String summary,
            UUID primaryDocumentId,
            UUID primaryDocumentVersionId,
            String primaryFilename,
            String primarySha256,
            UUID signedDocumentId,
            UUID signedDocumentVersionId,
            String signedFilename,
            String signedSha256,
            String signatureStatus,
            UUID createdBy,
            String createdByName,
            Instant createdAt,
            UUID finalizedBy,
            String finalizedByName,
            Instant finalizedAt,
            UUID signedBy,
            String signedByName,
            Instant signedAt
    ) {}

    public record ContractLifecycleEventView(
            UUID id,
            String action,
            UUID actorUserId,
            String actorName,
            String fromContractStatus,
            String toContractStatus,
            UUID contractVersionId,
            UUID documentVersionId,
            String comment,
            Instant occurredAt
    ) {}

    public record ContractArchiveLinkView(
            UUID archiveId,
            String archiveNumber,
            String archiveTitle,
            String archiveStatus,
            UUID matterId,
            String matterNumber,
            UUID documentId,
            UUID documentVersionId,
            int sequenceNumber
    ) {}

    public record ContractDetailView(
            ContractView contract,
            List<ContractVersionView> versions,
            List<ContractLifecycleEventView> lifecycle,
            List<ContractArchiveLinkView> archives
    ) {}
}
