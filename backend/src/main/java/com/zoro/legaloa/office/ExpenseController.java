package com.zoro.legaloa.office;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/expense-claims")
public class ExpenseController {
    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping
    List<ExpenseView> list() {
        return service.list();
    }

    @PostMapping
    ExpenseView create(@Valid @RequestBody CreateExpenseRequest request) {
        return service.create(request);
    }

    @PostMapping("/receipt-check")
    ReceiptCheckView checkReceipt(@Valid @RequestBody ReceiptCheckRequest request) {
        return service.checkReceipt(request);
    }

    @PostMapping("/{id}/submit")
    ExpenseView submit(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return service.submit(id, idempotencyKey);
    }

    @PostMapping("/{id}/pay")
    ExpenseView markPaid(@PathVariable UUID id) {
        return service.markPaid(id);
    }

    public record CreateExpenseRequest(
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 1000) String purpose,
            UUID matterId,
            @NotEmpty List<@Valid ExpenseItemRequest> items
    ) {}

    public record ExpenseItemRequest(
            @NotBlank @Size(max = 80) String category,
            @NotNull LocalDate occurredOn,
            @NotBlank @Size(max = 500) String description,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            UUID receiptDocumentId
    ) {}

    public record ReceiptCheckRequest(
            @NotNull UUID matterId,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256
    ) {}

    public record ReceiptCheckView(boolean duplicate) {}

    public record ExpenseItemView(
            UUID id,
            String category,
            LocalDate occurredOn,
            String description,
            BigDecimal amount,
            UUID receiptDocumentId,
            UUID receiptVersionId,
            String receiptFilename
    ) {}

    public record ExpenseView(
            UUID id,
            String claimNumber,
            UUID applicantUserId,
            String applicantName,
            String title,
            String purpose,
            UUID matterId,
            String matterNumber,
            BigDecimal totalAmount,
            String currency,
            String status,
            Instant paidAt,
            Instant createdAt,
            UUID officeId,
            String officeNameZh,
            String officeNameEn,
            String reviewStage,
            String reviewTaskName,
            String reviewGroup,
            List<ExpenseItemView> items
    ) {}
}
