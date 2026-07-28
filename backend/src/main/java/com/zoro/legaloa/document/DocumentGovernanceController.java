package com.zoro.legaloa.document;

import com.zoro.legaloa.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/document-governance")
public class DocumentGovernanceController {
    private final DocumentGovernanceService service;

    public DocumentGovernanceController(DocumentGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/documents/{documentId}/versions")
    List<VersionView> versions(@PathVariable UUID documentId) {
        return service.versions(documentId);
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/index")
    IndexView index(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        return service.index(documentId, versionId);
    }

    @GetMapping("/search")
    PageResponse<SearchHit> search(
            @RequestParam @NotBlank @Size(max = 200) String query,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) Integer size
    ) {
        return service.search(query, page, size);
    }

    @GetMapping("/templates")
    List<TemplateView> templates() {
        return service.templates();
    }

    @PostMapping("/templates")
    TemplateView createTemplate(@Valid @RequestBody TemplateRequest request) {
        return service.createTemplate(request);
    }

    @GetMapping("/clauses")
    List<ClauseView> clauses() {
        return service.clauses();
    }

    @PostMapping("/clauses")
    ClauseView createClause(@Valid @RequestBody ClauseRequest request) {
        return service.createClause(request);
    }

    @GetMapping("/retention-rules")
    List<RetentionRuleView> retentionRules() {
        return service.retentionRules();
    }

    @PostMapping("/retention-rules")
    RetentionRuleView createRetentionRule(@Valid @RequestBody RetentionRuleRequest request) {
        return service.createRetentionRule(request);
    }

    @GetMapping("/legal-holds")
    List<LegalHoldView> legalHolds() {
        return service.legalHolds();
    }

    @PostMapping("/legal-holds")
    LegalHoldView createLegalHold(@Valid @RequestBody LegalHoldRequest request) {
        return service.createLegalHold(request);
    }

    @PostMapping("/legal-holds/{id}/release")
    LegalHoldView releaseLegalHold(
            @PathVariable UUID id,
            @Valid @RequestBody ReleaseHoldRequest request
    ) {
        return service.releaseLegalHold(id, request.reason());
    }

    @DeleteMapping("/documents/{documentId}")
    DeletionView deleteDocument(
            @PathVariable UUID documentId,
            @Valid @RequestBody DeleteDocumentRequest request
    ) {
        return service.deleteDocument(documentId, request.reason());
    }

    @PostMapping("/exports")
    ExportJobView createExport(@Valid @RequestBody ExportRequest request) {
        return service.createExport(request);
    }

    @GetMapping("/exports/{id}")
    ExportJobView export(@PathVariable UUID id) {
        return service.export(id);
    }

    public record VersionView(
            UUID id, int versionNumber, String filename, String contentType,
            long sizeBytes, String sha256, String status, String ingestionStatus,
            UUID createdBy, Instant createdAt
    ) {}

    public record IndexView(
            UUID versionId, String provider, String status, String language,
            Integer pageCount, Instant indexedAt, String failureReason
    ) {}

    public record SearchHit(
            UUID documentId, UUID versionId, String logicalName, String filename,
            String documentType, String excerpt, double rank, Instant updatedAt
    ) {}

    public record TemplateRequest(
            UUID officeId,
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 200) String nameZh,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Size(max = 80) String category,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 200000) String bodyMarkdown,
            @Size(max = 500) String changeNote
    ) {}

    public record TemplateView(
            UUID id, UUID officeId, String code, String nameZh, String nameEn,
            String category, String status, UUID currentVersionId, int versionNumber,
            String title, String bodyMarkdown, Instant updatedAt
    ) {}

    public record ClauseRequest(
            UUID officeId,
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 300) String titleZh,
            @NotBlank @Size(max = 300) String titleEn,
            @NotBlank @Size(max = 80) String category,
            @NotBlank @Size(max = 24) String riskLevel,
            @NotBlank @Size(max = 100000) String bodyZh,
            @NotBlank @Size(max = 100000) String bodyEn,
            @Size(max = 10000) String guidanceZh,
            @Size(max = 10000) String guidanceEn,
            @Size(max = 500) String changeNote
    ) {}

    public record ClauseView(
            UUID id, UUID officeId, String code, String titleZh, String titleEn,
            String category, String riskLevel, String status, int versionNumber,
            String bodyZh, String bodyEn, String guidanceZh, String guidanceEn,
            Instant updatedAt
    ) {}

    public record RetentionRuleRequest(
            UUID officeId,
            @NotBlank @Size(max = 64) String resourceType,
            @Size(max = 80) String documentType,
            @Min(1) @Max(100) int retentionYears,
            @NotBlank @Size(max = 24) String dispositionAction
    ) {}

    public record RetentionRuleView(
            UUID id, UUID officeId, String resourceType, String documentType,
            int retentionYears, String dispositionAction, boolean enabled, Instant updatedAt
    ) {}

    public record LegalHoldRequest(
            @NotBlank @Size(max = 300) String name,
            @NotBlank @Size(max = 5000) String reason,
            @NotEmpty List<@Valid HoldResourceRequest> resources
    ) {}

    public record HoldResourceRequest(
            @NotBlank @Size(max = 64) String resourceType,
            UUID resourceId
    ) {}

    public record ReleaseHoldRequest(@NotBlank @Size(max = 5000) String reason) {}

    public record LegalHoldView(
            UUID id, String name, String reason, String status, int resourceCount,
            UUID createdBy, Instant createdAt, UUID releasedBy, Instant releasedAt
    ) {}

    public record DeleteDocumentRequest(@NotBlank @Size(max = 5000) String reason) {}

    public record DeletionView(
            UUID requestId, UUID documentId, String status, String reason, Instant decidedAt
    ) {}

    public record ExportRequest(
            @NotBlank @Size(max = 64) String resourceType,
            @Size(max = 200) String query
    ) {}

    public record ExportJobView(
            UUID id, String resourceType, String status, Integer rowCount,
            String downloadUrl, String sha256, String errorMessage,
            Instant createdAt, Instant completedAt, Instant expiresAt
    ) {}
}
