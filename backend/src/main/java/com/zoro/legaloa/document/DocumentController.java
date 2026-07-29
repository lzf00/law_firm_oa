package com.zoro.legaloa.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Future;
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
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documentService;
    private final DocumentGrantService grantService;

    public DocumentController(
            DocumentService documentService,
            DocumentGrantService grantService
    ) {
        this.documentService = documentService;
        this.grantService = grantService;
    }

    @GetMapping
    List<DocumentView> list(
            @RequestParam(required = false) UUID matterId,
            @RequestParam(required = false) UUID contractId
    ) {
        return documentService.list(matterId, contractId);
    }

    @PostMapping("/uploads")
    UploadTicket initiate(@Valid @RequestBody InitiateUploadRequest request) {
        return documentService.initiate(request);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    DocumentView complete(@PathVariable UUID uploadId) {
        return documentService.complete(uploadId);
    }

    @PostMapping("/{documentId}/versions/{versionId}/download-url")
    DownloadTicket download(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId
    ) {
        return documentService.download(documentId, versionId);
    }

    @PostMapping("/{documentId}/versions/{versionId}/preview-url")
    DownloadTicket preview(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId
    ) {
        return documentService.preview(documentId, versionId);
    }

    @GetMapping("/{documentId}/versions")
    List<DocumentVersionView> versions(@PathVariable UUID documentId) {
        return documentService.versions(documentId);
    }

    @GetMapping("/{documentId}/evidence")
    DocumentEvidenceView evidence(@PathVariable UUID documentId) {
        return documentService.evidence(documentId);
    }

    @GetMapping("/{documentId}/grants")
    List<DocumentGrantView> grants(@PathVariable UUID documentId) {
        return grantService.list(documentId);
    }

    @PostMapping("/{documentId}/grants")
    DocumentGrantView grant(
            @PathVariable UUID documentId,
            @Valid @RequestBody DocumentGrantRequest request
    ) {
        return grantService.grant(documentId, request);
    }

    @DeleteMapping("/{documentId}/grants/{grantId}")
    void revokeGrant(
            @PathVariable UUID documentId,
            @PathVariable UUID grantId
    ) {
        grantService.revoke(documentId, grantId);
    }

    public record InitiateUploadRequest(
            UUID documentId,
            UUID matterId,
            UUID contractId,
            @NotBlank @Size(max = 300) String logicalName,
            @NotBlank @Size(max = 80) String documentType,
            @NotBlank @Size(max = 300) String originalFilename,
            @NotBlank @Size(max = 150) String contentType,
            @Min(1) @Max(209715200) long sizeBytes,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256
    ) {}

    public record UploadTicket(
            UUID uploadId,
            String uploadUrl,
            String method,
            String requiredContentType,
            Instant expiresAt
    ) {}

    public record DownloadTicket(String downloadUrl, Instant expiresAt) {}

    public record DocumentVersionView(
            UUID id,
            int versionNumber,
            String filename,
            String contentType,
            String detectedContentType,
            long sizeBytes,
            String sha256,
            String status,
            String signatureStatus,
            String ingestionStatus,
            boolean current,
            UUID createdBy,
            String createdByName,
            Instant createdAt,
            Instant scanCompletedAt
    ) {}

    public record DocumentGrantRequest(
            UUID userId,
            @NotBlank @Size(max = 32) String permission,
            @Future Instant expiresAt
    ) {}

    public record DocumentGrantView(
            UUID id,
            UUID userId,
            String username,
            String displayName,
            String permission,
            UUID grantedBy,
            String grantedByName,
            Instant expiresAt,
            Instant createdAt,
            boolean active
    ) {}

    public record DocumentView(
            UUID id,
            UUID matterId,
            UUID contractId,
            String logicalName,
            String documentType,
            String confidentialityLevel,
            UUID currentVersionId,
            Integer versionNumber,
            String versionStatus,
            String signatureStatus,
            String originalFilename,
            long sizeBytes,
            Instant createdAt,
            String ingestionStatus,
            String scanFailureReason,
            Instant scanCompletedAt
    ) {}

    public record DocumentEvidenceView(
            UUID documentId,
            UUID matterId,
            String matterNumber,
            String matterTitle,
            UUID contractId,
            String contractNumber,
            String contractTitle,
            List<ContractVersionReferenceView> contractVersions,
            List<ArchiveReferenceView> archives
    ) {}

    public record ContractVersionReferenceView(
            UUID contractVersionId,
            int versionNumber,
            String versionStatus,
            String signatureStatus,
            boolean primaryFile,
            boolean signedFile
    ) {}

    public record ArchiveReferenceView(
            UUID archiveId,
            String archiveNumber,
            String archiveTitle,
            String archiveStatus,
            UUID pinnedDocumentVersionId,
            int sequenceNumber
    ) {}
}
