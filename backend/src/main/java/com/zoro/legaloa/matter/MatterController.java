package com.zoro.legaloa.matter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/matters")
public class MatterController {
    private final MatterService matterService;

    public MatterController(MatterService matterService) {
        this.matterService = matterService;
    }

    @GetMapping
    List<MatterSummary> list(@RequestParam(required = false) String status) {
        return matterService.list(status);
    }

    @GetMapping("/{id}")
    MatterDetail get(@PathVariable UUID id) {
        return matterService.get(id);
    }

    @PostMapping
    MatterDetail create(@Valid @RequestBody CreateMatterRequest request) {
        return matterService.create(request);
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
}
