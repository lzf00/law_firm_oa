package com.zoro.legaloa.matter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {
    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping
    List<ContractView> list() {
        return contractService.list();
    }

    @PostMapping
    ContractView create(@Valid @RequestBody CreateContractRequest request) {
        return contractService.create(request);
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
            String clientName,
            UUID responsibleUserId,
            String responsibleName,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            BigDecimal amount,
            String currency,
            int matterCount
    ) {}
}

