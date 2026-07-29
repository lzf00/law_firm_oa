package com.zoro.legaloa.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/parties")
public class PartyController {
    private final PartyService partyService;

    public PartyController(PartyService partyService) {
        this.partyService = partyService;
    }

    @GetMapping
    List<PartySummary> list(@RequestParam(defaultValue = "") String query) {
        return partyService.search(query);
    }

    @PostMapping
    PartySummary create(@Valid @RequestBody CreatePartyRequest request) {
        return partyService.create(request);
    }

    @PutMapping("/{id}")
    PartySummary update(
            @PathVariable UUID id,
            @Valid @RequestBody CreatePartyRequest request
    ) {
        return partyService.update(id, request);
    }

    public record CreatePartyRequest(
            @NotNull PartyType partyType,
            @NotBlank @Size(max = 300) String displayName,
            @Size(max = 64) String unifiedSocialCreditCode,
            @Size(max = 500) String notes,
            List<@NotBlank @Size(max = 300) String> aliases
    ) {}

    public enum PartyType {
        PERSON, ORGANIZATION, GOVERNMENT, OTHER
    }

    public record PartySummary(
            UUID id,
            String partyType,
            String displayName,
            String unifiedSocialCreditCode,
            String riskLevel,
            List<String> aliases,
            String notes
    ) {}
}
