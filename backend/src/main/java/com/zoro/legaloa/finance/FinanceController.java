package com.zoro.legaloa.finance;

import com.zoro.legaloa.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {
    private final FinanceService service;

    public FinanceController(FinanceService service) {
        this.service = service;
    }

    @GetMapping("/engagements")
    List<EngagementView> engagements() {
        return service.engagements();
    }

    @PostMapping("/engagements")
    EngagementView createEngagement(@Valid @RequestBody EngagementRequest request) {
        return service.createEngagement(request);
    }

    @PostMapping("/engagements/{id}/approve")
    EngagementView approveEngagement(@PathVariable UUID id) {
        return service.approveEngagement(id);
    }

    @GetMapping("/time-entries")
    PageResponse<TimeEntryView> timeEntries(
            @RequestParam(required = false) UUID matterId,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) Integer size
    ) {
        return service.timeEntries(matterId, page, size);
    }

    @PostMapping("/time-entries")
    TimeEntryView createTimeEntry(@Valid @RequestBody TimeEntryRequest request) {
        return service.createTimeEntry(request);
    }

    @PostMapping("/time-entries/{id}/submit")
    TimeEntryView submitTimeEntry(@PathVariable UUID id) {
        return service.transitionTimeEntry(id, "SUBMITTED");
    }

    @PostMapping("/time-entries/{id}/approve")
    TimeEntryView approveTimeEntry(@PathVariable UUID id) {
        return service.transitionTimeEntry(id, "APPROVED");
    }

    @GetMapping("/invoices")
    PageResponse<InvoiceView> invoices(
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) Integer size
    ) {
        return service.invoices(page, size);
    }

    @PostMapping("/invoices/draft")
    InvoiceView draftInvoice(@Valid @RequestBody DraftInvoiceRequest request) {
        return service.draftInvoice(request);
    }

    @PostMapping("/invoices/{id}/review")
    InvoiceView reviewInvoice(@PathVariable UUID id) {
        return service.transitionInvoice(id, "UNDER_REVIEW", null);
    }

    @PostMapping("/invoices/{id}/issue")
    InvoiceView issueInvoice(@PathVariable UUID id) {
        return service.transitionInvoice(id, "ISSUED", null);
    }

    @PostMapping("/invoices/{id}/cancel")
    InvoiceView cancelInvoice(
            @PathVariable UUID id,
            @Valid @RequestBody CancelInvoiceRequest request
    ) {
        return service.transitionInvoice(id, "CANCELLED", request.reason());
    }

    @PostMapping("/payments")
    PaymentView recordPayment(@Valid @RequestBody PaymentRequest request) {
        return service.recordPayment(request);
    }

    @PostMapping("/invoices/{id}/collections")
    CollectionView collection(
            @PathVariable UUID id,
            @Valid @RequestBody CollectionRequest request
    ) {
        return service.recordCollection(id, request);
    }

    @GetMapping("/reports")
    FinanceReport report(@RequestParam(required = false) UUID officeId) {
        return service.report(officeId);
    }

    public record EngagementRequest(
            UUID officeId,
            UUID matterId,
            UUID clientId,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 32) String feeType,
            @NotNull @DecimalMin("0.00") BigDecimal rateAmount,
            @DecimalMin("0.00") BigDecimal capAmount,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotNull @DecimalMin("0.00") BigDecimal taxRate
    ) {}

    public record EngagementView(
            UUID id, UUID officeId, UUID matterId, String matterNumber,
            String engagementNumber, String title, String currency, String status,
            String feeType, BigDecimal rateAmount, BigDecimal capAmount,
            BigDecimal taxRate, LocalDate effectiveFrom, LocalDate effectiveTo,
            Instant approvedAt, Instant updatedAt
    ) {}

    public record TimeEntryRequest(
            UUID matterId,
            @NotNull LocalDate workDate,
            @Min(1) @Max(1440) int minutes,
            @NotBlank @Size(max = 2000) String description,
            boolean billable
    ) {}

    public record TimeEntryView(
            UUID id, UUID officeId, UUID matterId, String matterNumber,
            UUID professionalUserId, String professionalName, LocalDate workDate,
            int minutes, String description, boolean billable, String currency,
            BigDecimal rateSnapshot, BigDecimal amount, String status,
            Instant approvedAt, Instant updatedAt
    ) {}

    public record DraftInvoiceRequest(
            UUID engagementId,
            @NotNull LocalDate dueDate,
            @Size(max = 2000) String fixedFeeDescription,
            @DecimalMin("0.00") BigDecimal fixedFeeAmount
    ) {}

    public record InvoiceLineView(
            UUID id, int lineNumber, String lineType, String description,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
            UUID sourceTimeEntryId, UUID professionalUserId, LocalDate workDate
    ) {}

    public record InvoiceView(
            UUID id, UUID officeId, UUID engagementId, UUID matterId,
            String matterNumber, String invoiceNumber, String currency, String status,
            BigDecimal subtotal, BigDecimal taxAmount, BigDecimal totalAmount,
            BigDecimal paidAmount, BigDecimal outstandingAmount, LocalDate dueDate,
            Instant issuedAt, Instant cancelledAt, String cancellationReason,
            Instant createdAt, List<InvoiceLineView> lines
    ) {}

    public record CancelInvoiceRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record AllocationRequest(
            UUID invoiceId,
            @NotNull @DecimalMin("0.01") BigDecimal amount
    ) {}

    public record PaymentRequest(
            UUID officeId,
            @NotBlank @Size(max = 120) String paymentReference,
            @NotNull LocalDate receivedOn,
            @NotBlank @Size(max = 300) String payerName,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank @Size(max = 32) String method,
            @Size(max = 200) String bankReference,
            @NotEmpty List<@Valid AllocationRequest> allocations
    ) {}

    public record PaymentView(
            UUID id, String paymentReference, LocalDate receivedOn, String payerName,
            String currency, BigDecimal amount, BigDecimal unallocatedAmount,
            String method, String status, Instant createdAt
    ) {}

    public record CollectionRequest(
            @NotBlank @Size(max = 32) String activityType,
            @NotBlank @Size(max = 2000) String notes,
            Instant nextActionAt,
            UUID ownerUserId
    ) {}

    public record CollectionView(
            UUID id, UUID invoiceId, String activityType, Instant occurredAt,
            String notes, Instant nextActionAt, UUID ownerUserId, UUID createdBy
    ) {}

    public record AgingBucket(
            String bucket, long invoiceCount, BigDecimal outstandingAmount
    ) {}

    public record FinanceReport(
            UUID officeId, BigDecimal approvedWip, BigDecimal unbilledTime,
            BigDecimal issuedRevenue, BigDecimal receivables,
            BigDecimal collectedAmount, BigDecimal utilizationPercent,
            List<AgingBucket> aging, Instant generatedAt
    ) {}
}
