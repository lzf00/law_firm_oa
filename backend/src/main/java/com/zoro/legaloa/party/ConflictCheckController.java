package com.zoro.legaloa.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conflict-checks")
public class ConflictCheckController {
    private final ConflictCheckService conflictCheckService;

    public ConflictCheckController(ConflictCheckService conflictCheckService) {
        this.conflictCheckService = conflictCheckService;
    }

    @PostMapping("/preview")
    ConflictPreview preview(@Valid @RequestBody ConflictPreviewRequest request) {
        return conflictCheckService.preview(request);
    }

    public record ConflictPreviewRequest(
            @NotBlank @Size(max = 300) String matterTitle,
            @NotEmpty List<@NotNull UUID> partyIds
    ) {}

    public record ConflictHit(
            UUID partyId,
            String partyName,
            UUID matterId,
            String matterNumber,
            String matterTitle,
            String partyRole,
            String side,
            String matterStatus
    ) {}

    public record ConflictPreview(String riskLevel, int hitCount, List<ConflictHit> hits) {}
}

