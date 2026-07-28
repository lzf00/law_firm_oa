package com.zoro.legaloa.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organization/sync")
public class OrganizationSyncController {
    private final OrganizationSyncService syncService;

    public OrganizationSyncController(OrganizationSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping
    OrganizationSyncResult sync(@Valid @RequestBody OrganizationSyncRequest request) {
        return syncService.sync(request);
    }

    @GetMapping("/runs")
    List<OrganizationSyncResult> runs() {
        return syncService.runs();
    }

    public record OrganizationSyncRequest(
            @NotBlank @Size(max = 32) String provider,
            @Size(max = 32) String mode,
            boolean deactivateMissingUsers,
            @NotEmpty List<@Valid DepartmentSnapshot> departments,
            List<@Valid UserSnapshot> users
    ) {
        public OrganizationSyncRequest {
            departments = departments == null ? List.of() : List.copyOf(departments);
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    public record DepartmentSnapshot(
            @NotBlank @Size(max = 150) String externalDepartmentId,
            @Size(max = 150) String parentExternalDepartmentId,
            @NotBlank @Size(max = 200) String name,
            int sortOrder
    ) {}

    public record UserSnapshot(
            @NotBlank @Size(max = 150) String externalUserId,
            @Size(max = 150) String externalOpenId,
            @Size(max = 150) String externalUnionId,
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 100) String displayName,
            @Size(max = 200) String email,
            @Size(max = 32) String status,
            List<@NotBlank @Size(max = 150) String> departmentExternalIds,
            Map<String, Object> rawProfile
    ) {
        public UserSnapshot {
            departmentExternalIds = departmentExternalIds == null
                    ? List.of() : List.copyOf(departmentExternalIds);
            rawProfile = rawProfile == null ? Map.of() : Map.copyOf(rawProfile);
        }
    }

    public record OrganizationSyncResult(
            UUID runId,
            String provider,
            String mode,
            String status,
            int departmentsSeen,
            int usersSeen,
            int departmentsChanged,
            int usersChanged
    ) {}
}
