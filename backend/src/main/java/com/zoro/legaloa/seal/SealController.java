package com.zoro.legaloa.seal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seals")
public class SealController {
    private final SealService sealService;

    public SealController(SealService sealService) {
        this.sealService = sealService;
    }

    @GetMapping
    List<SealView> listSeals() {
        return sealService.listSeals();
    }

    @GetMapping("/requests")
    List<SealRequestView> listRequests() {
        return sealService.listRequests();
    }

    @PostMapping("/requests")
    SealRequestView createRequest(@Valid @RequestBody CreateSealRequest request) {
        return sealService.createRequest(request);
    }

    public record CreateSealRequest(
            @NotNull UUID sealId,
            UUID matterId,
            UUID contractId,
            @NotBlank @Size(max = 500) String purpose,
            @Min(1) int copies
    ) {}

    public record SealView(
            UUID id,
            String name,
            String sealType,
            String custodianName,
            String status
    ) {}

    public record SealRequestView(
            UUID id,
            UUID sealId,
            String sealName,
            UUID matterId,
            UUID contractId,
            String purpose,
            int copies,
            String status,
            String requestedByName,
            Instant createdAt
    ) {}
}
