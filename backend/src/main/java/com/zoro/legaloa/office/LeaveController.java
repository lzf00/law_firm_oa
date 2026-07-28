package com.zoro.legaloa.office;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
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
@RequestMapping("/api/leave-requests")
public class LeaveController {
    private final LeaveService service;

    public LeaveController(LeaveService service) {
        this.service = service;
    }

    @GetMapping
    List<LeaveView> list() {
        return service.list();
    }

    @PostMapping
    LeaveView create(@Valid @RequestBody CreateLeaveRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/submit")
    LeaveView submit(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return service.submit(id, idempotencyKey);
    }

    public record CreateLeaveRequest(
            @NotBlank @Size(max = 32) String leaveType,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotNull @DecimalMin("0.25") BigDecimal durationHours,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 200) String emergencyContact
    ) {}

    public record LeaveView(
            UUID id,
            String requestNumber,
            UUID applicantUserId,
            String applicantName,
            String leaveType,
            Instant startAt,
            Instant endAt,
            BigDecimal durationHours,
            String reason,
            String emergencyContact,
            String status,
            Instant createdAt,
            UUID officeId,
            String officeNameZh,
            String officeNameEn
    ) {}
}
