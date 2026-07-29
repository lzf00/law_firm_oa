package com.zoro.legaloa.admin;

import com.zoro.legaloa.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    OrganizationSettingsView settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    OrganizationSettingsView updateSettings(@Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request);
    }

    @GetMapping("/users")
    PageResponse<UserAccessView> users(
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) Integer size
    ) {
        return service.users(query, page, size);
    }

    @GetMapping("/roles")
    List<RoleView> roles() {
        return service.roles();
    }

    @GetMapping("/permissions")
    List<PermissionView> permissions() {
        return service.permissions();
    }

    @PutMapping("/roles/{code}/permissions")
    RoleView updateRolePermissions(
            @PathVariable String code,
            @Valid @RequestBody RolePermissionsRequest request
    ) {
        return service.updateRolePermissions(code, request);
    }

    @PutMapping("/users/{id}/access")
    UserAccessView updateUserAccess(
            @PathVariable UUID id,
            @Valid @RequestBody UserAccessRequest request
    ) {
        return service.updateUserAccess(id, request);
    }

    @GetMapping("/workflow-rules")
    List<WorkflowRuleView> workflowRules() {
        return service.workflowRules();
    }

    @PostMapping("/workflow-rules")
    WorkflowRuleView createWorkflowRule(@Valid @RequestBody WorkflowRuleRequest request) {
        return service.createWorkflowRule(request);
    }

    @GetMapping("/integrations")
    List<IntegrationHealthView> integrations() {
        return service.integrations();
    }

    @GetMapping("/imports/template")
    ResponseEntity<byte[]> importTemplate(@RequestParam @NotBlank String type) {
        return service.importTemplate(type);
    }

    @PostMapping("/imports/validate")
    ImportJobView validateImport(@Valid @RequestBody ImportRequest request) {
        return service.validateImport(request);
    }

    public record SettingsRequest(
            @NotBlank @Size(max = 200) String brandNameZh,
            @NotBlank @Size(max = 200) String brandNameEn,
            @NotBlank @Size(max = 80) String shortNameZh,
            @NotBlank @Size(max = 80) String shortNameEn,
            @NotBlank @Size(max = 16) String defaultLocale,
            @NotBlank @Size(max = 80) String primaryTimezone,
            @NotBlank @Size(min = 3, max = 3) String baseCurrency,
            @Size(max = 500) String websiteUrl
    ) {}

    public record OrganizationSettingsView(
            UUID organizationId, String brandNameZh, String brandNameEn,
            String shortNameZh, String shortNameEn, String defaultLocale,
            List<String> supportedLocales, String primaryTimezone,
            String baseCurrency, String websiteUrl, Instant updatedAt
    ) {}

    public record OfficeAssignment(
            UUID officeId, boolean primary, @NotBlank @Size(max = 32) String accessLevel,
            Instant validUntil
    ) {}

    public record UserAccessRequest(
            @NotBlank @Size(max = 32) String status,
            @NotEmpty List<@NotBlank @Size(max = 100) String> roleCodes,
            List<@Valid OfficeAssignment> offices
    ) {
        public UserAccessRequest {
            roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
            offices = offices == null ? List.of() : List.copyOf(offices);
        }
    }

    public record UserAccessView(
            UUID id, String username, String displayName, String email, String status,
            List<String> roleCodes, List<OfficeAssignment> offices, Instant updatedAt
    ) {}

    public record RoleView(
            String code, String name, int userCount,
            List<String> permissions, boolean systemRole
    ) {}

    public record PermissionView(
            String code, String name, String resourceType,
            String action, int assignedRoleCount
    ) {}

    public record RolePermissionsRequest(
            List<@NotBlank @Size(max = 150) String> permissionCodes
    ) {
        public RolePermissionsRequest {
            permissionCodes = permissionCodes == null
                    ? List.of() : List.copyOf(permissionCodes);
        }
    }

    public record WorkflowRuleRequest(
            UUID officeId,
            @NotBlank @Size(max = 64) String businessType,
            BigDecimal amountThreshold,
            @Size(min = 3, max = 3) String currency,
            @Size(max = 100) String assigneeRoleCode,
            UUID fallbackUserId,
            @Min(5) @Max(525600) int reminderMinutes,
            @Min(5) @Max(525600) int escalationMinutes,
            @Min(1) @Max(10000) int priority
    ) {}

    public record WorkflowRuleView(
            UUID id, UUID officeId, String businessType, BigDecimal amountThreshold,
            String currency, String assigneeRoleCode, UUID fallbackUserId,
            int reminderMinutes, int escalationMinutes, boolean enabled,
            int priority, Instant updatedAt
    ) {}

    public record IntegrationHealthView(
            String integrationType, String providerCode, String configurationStatus,
            String healthStatus, String message, Instant checkedAt
    ) {}

    public record ImportRequest(
            @NotBlank @Size(max = 64) String importType,
            @NotBlank @Size(max = 300) String originalFilename,
            @NotEmpty @Size(max = 1000) List<Map<String, String>> rows
    ) {}

    public record ImportErrorView(
            int rowNumber, String fieldName, String errorCode,
            String errorMessage, String rejectedValue
    ) {}

    public record ImportJobView(
            UUID id, String importType, String status, int totalRows,
            int validRows, int errorRows, int appliedRows,
            List<ImportErrorView> errors, Instant createdAt, Instant completedAt
    ) {}
}
