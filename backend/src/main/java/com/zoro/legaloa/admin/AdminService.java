package com.zoro.legaloa.admin;

import com.zoro.legaloa.admin.AdminController.ImportErrorView;
import com.zoro.legaloa.admin.AdminController.ImportJobView;
import com.zoro.legaloa.admin.AdminController.ImportRequest;
import com.zoro.legaloa.admin.AdminController.EffectiveAccessView;
import com.zoro.legaloa.admin.AdminController.EffectiveOfficeView;
import com.zoro.legaloa.admin.AdminController.EffectivePermissionView;
import com.zoro.legaloa.admin.AdminController.EffectiveRoleView;
import com.zoro.legaloa.admin.AdminController.IntegrationHealthView;
import com.zoro.legaloa.admin.AdminController.OfficeAssignment;
import com.zoro.legaloa.admin.AdminController.OrganizationSettingsView;
import com.zoro.legaloa.admin.AdminController.PermissionView;
import com.zoro.legaloa.admin.AdminController.RolePermissionsRequest;
import com.zoro.legaloa.admin.AdminController.RoleView;
import com.zoro.legaloa.admin.AdminController.SettingsRequest;
import com.zoro.legaloa.admin.AdminController.UserAccessRequest;
import com.zoro.legaloa.admin.AdminController.UserAccessView;
import com.zoro.legaloa.admin.AdminController.WorkflowRuleRequest;
import com.zoro.legaloa.admin.AdminController.WorkflowRuleView;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.common.PagePolicy;
import com.zoro.legaloa.common.PageResponse;
import com.zoro.legaloa.document.AntivirusScanner;
import com.zoro.legaloa.document.ObjectStorageService;
import com.zoro.legaloa.document.OcrProvider;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "INACTIVE", "SUSPENDED");
    private static final Set<String> ACCESS_LEVELS = Set.of("MEMBER", "MANAGER");
    private static final Set<String> IMPORT_TYPES = Set.of("USERS", "MATTERS", "CLIENTS");
    private static final Set<String> SYSTEM_ROLES = Set.of(
            "ADMIN", "MANAGING_PARTNER", "PARTNER", "LAWYER", "ASSOCIATE",
            "PARALEGAL", "FINANCE", "HR", "ADMINISTRATION", "OFFICE_ADMIN"
    );

    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;
    private final AuditService auditService;
    private final ObjectStorageService storageService;
    private final AntivirusScanner scanner;
    private final OcrProvider ocrProvider;
    private final String authMode;
    private final String dingTalkAppKey;

    public AdminService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            OfficeAccessService officeAccessService,
            AuditService auditService,
            ObjectStorageService storageService,
            AntivirusScanner scanner,
            OcrProvider ocrProvider,
            @Value("${app.auth.mode:dev}") String authMode,
            @Value("${app.auth.dingtalk.app-key:}") String dingTalkAppKey
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.officeAccessService = officeAccessService;
        this.auditService = auditService;
        this.storageService = storageService;
        this.scanner = scanner;
        this.ocrProvider = ocrProvider;
        this.authMode = authMode;
        this.dingTalkAppKey = dingTalkAppKey;
    }

    @Transactional(readOnly = true)
    public OrganizationSettingsView settings() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ADMIN_CONSOLE_VIEW");
        return settings(actor.organizationId());
    }

    @Transactional
    public OrganizationSettingsView updateSettings(SettingsRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "TENANT_BRAND_MANAGE");
        if (!Set.of("zh-CN", "en-US").contains(request.defaultLocale())) {
            throw invalid("DEFAULT_LOCALE_INVALID");
        }
        String currency = request.baseCurrency().toUpperCase(java.util.Locale.ROOT);
        jdbcClient.sql("""
                        UPDATE organization_settings
                        SET brand_name_zh = :brandNameZh, brand_name_en = :brandNameEn,
                            short_name_zh = :shortNameZh, short_name_en = :shortNameEn,
                            default_locale = :defaultLocale,
                            primary_timezone = :timezone, base_currency = :currency,
                            website_url = :websiteUrl, updated_at = now()
                        WHERE organization_id = :organizationId
                        """)
                .param("brandNameZh", request.brandNameZh().trim())
                .param("brandNameEn", request.brandNameEn().trim())
                .param("shortNameZh", request.shortNameZh().trim())
                .param("shortNameEn", request.shortNameEn().trim())
                .param("defaultLocale", request.defaultLocale())
                .param("timezone", request.primaryTimezone().trim())
                .param("currency", currency)
                .param("websiteUrl", request.websiteUrl())
                .param("organizationId", actor.organizationId())
                .update();
        auditService.success(
                actor, "ORGANIZATION_SETTINGS_UPDATE", "ORGANIZATION", actor.organizationId()
        );
        return settings(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public PageResponse<UserAccessView> users(String query, Integer page, Integer size) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ADMIN_CONSOLE_VIEW");
        var spec = PagePolicy.bounded(page, size, "displayNameAsc",
                Map.of("displayNameAsc", "display_name"), "displayNameAsc");
        String pattern = "%" + escapeLike(query == null ? "" : query.trim()) + "%";
        long total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM users
                        WHERE organization_id = :organizationId AND deleted_at IS NULL
                          AND (display_name ILIKE :pattern ESCAPE '\\'
                               OR username ILIKE :pattern ESCAPE '\\'
                               OR COALESCE(email, '') ILIKE :pattern ESCAPE '\\')
                        """)
                .param("organizationId", actor.organizationId())
                .param("pattern", pattern)
                .query(Long.class).single();
        List<UserAccessView> items = jdbcClient.sql("""
                        SELECT id FROM users
                        WHERE organization_id = :organizationId AND deleted_at IS NULL
                          AND (display_name ILIKE :pattern ESCAPE '\\'
                               OR username ILIKE :pattern ESCAPE '\\'
                               OR COALESCE(email, '') ILIKE :pattern ESCAPE '\\')
                        ORDER BY display_name, username
                        LIMIT :size OFFSET :offset
                        """)
                .param("organizationId", actor.organizationId())
                .param("pattern", pattern)
                .param("size", spec.size()).param("offset", spec.offset())
                .query(UUID.class).list().stream()
                .map(id -> user(id, actor.organizationId())).toList();
        return PageResponse.of(items, spec.page(), spec.size(), total);
    }

    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ADMIN_CONSOLE_VIEW");
        return jdbcClient.sql("""
                        SELECT r.id, r.code, r.name,
                               COUNT(DISTINCT ur.user_id) AS user_count,
                               COALESCE(array_agg(DISTINCT p.code)
                                 FILTER (WHERE p.code IS NOT NULL), ARRAY[]::varchar[]) AS permissions
                        FROM roles r
                        LEFT JOIN user_roles ur ON ur.role_id = r.id
                        LEFT JOIN role_permissions rp ON rp.role_id = r.id
                        LEFT JOIN permissions p ON p.id = rp.permission_id
                        WHERE r.organization_id = :organizationId
                        GROUP BY r.id
                        ORDER BY r.code
                        """)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new RoleView(
                        rs.getString("code"), rs.getString("name"),
                        rs.getInt("user_count"),
                        List.of((String[]) rs.getArray("permissions").getArray()),
                        SYSTEM_ROLES.contains(rs.getString("code"))
                )).list();
    }

    @Transactional(readOnly = true)
    public List<PermissionView> permissions() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ADMIN_CONSOLE_VIEW");
        return jdbcClient.sql("""
                        SELECT p.code, p.name, p.resource_type, p.action,
                               COUNT(DISTINCT rp.role_id) FILTER (
                                 WHERE r.organization_id = :organizationId
                               ) AS assigned_role_count
                        FROM permissions p
                        LEFT JOIN role_permissions rp ON rp.permission_id = p.id
                        LEFT JOIN roles r ON r.id = rp.role_id
                        GROUP BY p.id
                        ORDER BY p.resource_type, p.action, p.code
                        """)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new PermissionView(
                        rs.getString("code"), rs.getString("name"),
                        rs.getString("resource_type"), rs.getString("action"),
                        rs.getInt("assigned_role_count")
                )).list();
    }

    @Transactional
    public RoleView updateRolePermissions(
            String requestedRoleCode,
            RolePermissionsRequest request
    ) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ROLE_PERMISSION_MANAGE");
        String roleCode = code(requestedRoleCode);
        RoleId role = jdbcClient.sql("""
                        SELECT id, code FROM roles
                        WHERE organization_id = :organizationId AND code = :code
                        FOR UPDATE
                        """)
                .param("organizationId", actor.organizationId())
                .param("code", roleCode)
                .query((rs, rowNum) -> new RoleId(
                        rs.getObject("id", UUID.class), rs.getString("code")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "ROLE_NOT_FOUND", "角色不存在", HttpStatus.NOT_FOUND
                ));
        Set<String> requestedPermissions = request.permissionCodes().stream()
                .map(AdminService::code)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (!AdminPermissionPolicy.canReplacePermissions(
                roleCode, requestedPermissions
        )) {
            throw new BusinessException(
                    "ADMIN_PERMISSION_PROTECTED",
                    "管理员角色必须保留管理控制台、用户权限、角色权限与审计权限",
                    HttpStatus.CONFLICT
            );
        }
        List<PermissionId> permissions = jdbcClient.sql("""
                        SELECT id, code FROM permissions
                        WHERE code IN (:codes)
                        """)
                .param("codes", requestedPermissions.isEmpty()
                        ? List.of("__NONE__") : requestedPermissions)
                .query((rs, rowNum) -> new PermissionId(
                        rs.getObject("id", UUID.class), rs.getString("code")
                )).list();
        if (permissions.size() != requestedPermissions.size()) {
            throw new BusinessException(
                    "PERMISSION_INVALID", "包含不存在的权限", HttpStatus.BAD_REQUEST
            );
        }
        jdbcClient.sql("DELETE FROM role_permissions WHERE role_id = :roleId")
                .param("roleId", role.id()).update();
        for (PermissionId permission : permissions) {
            jdbcClient.sql("""
                            INSERT INTO role_permissions (role_id, permission_id)
                            VALUES (:roleId, :permissionId)
                            """)
                    .param("roleId", role.id())
                    .param("permissionId", permission.id())
                    .update();
        }
        auditService.record(
                actor, "ROLE_PERMISSIONS_UPDATE", "ROLE", role.id(),
                "SUCCESS", null, Map.of(
                        "roleCode", roleCode,
                        "permissionCount", requestedPermissions.size()
                )
        );
        return role(actor.organizationId(), roleCode);
    }

    @Transactional
    public UserAccessView updateUserAccess(UUID userId, UserAccessRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "USER_ACCESS_MANAGE");
        String status = code(request.status());
        if (!USER_STATUSES.contains(status)) {
            throw invalid("USER_STATUS_INVALID");
        }
        Boolean targetExists = jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM users WHERE id = :id
                            AND organization_id = :organizationId AND deleted_at IS NULL
                        )
                        """)
                .param("id", userId).param("organizationId", actor.organizationId())
                .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(targetExists)) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
        Set<String> requestedRoles = request.roleCodes().stream()
                .map(AdminService::code).collect(java.util.stream.Collectors.toSet());
        List<RoleId> roles = jdbcClient.sql("""
                        SELECT id, code FROM roles
                        WHERE organization_id = :organizationId AND code IN (:codes)
                        """)
                .param("organizationId", actor.organizationId())
                .param("codes", requestedRoles.isEmpty() ? List.of("__NONE__") : requestedRoles)
                .query((rs, rowNum) -> new RoleId(
                        rs.getObject("id", UUID.class), rs.getString("code")
                )).list();
        if (roles.size() != requestedRoles.size()) {
            throw invalid("ROLE_INVALID");
        }
        boolean currentlyAdmin = authorizationService.roles(new RequestActor(
                userId, actor.organizationId(), "", ""
        )).contains("ADMIN");
        boolean willBeAdmin = requestedRoles.contains("ADMIN") && "ACTIVE".equals(status);
        if (currentlyAdmin && !willBeAdmin) {
            long otherAdmins = jdbcClient.sql("""
                            SELECT COUNT(DISTINCT u.id)
                            FROM users u
                            JOIN user_roles ur ON ur.user_id = u.id
                            JOIN roles r ON r.id = ur.role_id
                            WHERE u.organization_id = :organizationId
                              AND u.id <> :userId AND u.status = 'ACTIVE'
                              AND u.deleted_at IS NULL AND r.code = 'ADMIN'
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("userId", userId)
                    .query(Long.class).single();
            if (otherAdmins == 0) {
                throw new BusinessException(
                        "LAST_ADMIN_PROTECTED", "不能停用或移除最后一名管理员",
                        HttpStatus.CONFLICT
                );
            }
        }
        long primaryCount = request.offices().stream().filter(OfficeAssignment::primary).count();
        if (primaryCount > 1) {
            throw invalid("MULTIPLE_PRIMARY_OFFICES");
        }
        for (OfficeAssignment assignment : request.offices()) {
            if (assignment.officeId() == null
                    || !ACCESS_LEVELS.contains(code(assignment.accessLevel()))) {
                throw invalid("OFFICE_ASSIGNMENT_INVALID");
            }
            officeAccessService.resolveManagedOffice(actor, assignment.officeId());
            if (assignment.validUntil() != null && !assignment.validUntil().isAfter(Instant.now())) {
                throw invalid("TEMPORARY_ACCESS_EXPIRED");
            }
        }
        jdbcClient.sql("""
                        UPDATE users SET status = :status,
                            primary_office_id = :primaryOfficeId, updated_at = now()
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("primaryOfficeId", request.offices().stream()
                        .filter(OfficeAssignment::primary).map(OfficeAssignment::officeId)
                        .findFirst().orElse(null))
                .param("id", userId).update();
        jdbcClient.sql("DELETE FROM user_roles WHERE user_id = :userId")
                .param("userId", userId).update();
        for (RoleId role : roles) {
            jdbcClient.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:userId, :roleId)")
                    .param("userId", userId).param("roleId", role.id()).update();
        }
        jdbcClient.sql("DELETE FROM user_offices WHERE user_id = :userId")
                .param("userId", userId).update();
        for (OfficeAssignment assignment : request.offices()) {
            jdbcClient.sql("""
                            INSERT INTO user_offices
                                (user_id, office_id, is_primary, access_level,
                                 valid_until, assigned_by)
                            VALUES
                                (:userId, :officeId, :primary, :accessLevel,
                                 :validUntil, :assignedBy)
                            """)
                    .param("userId", userId)
                    .param("officeId", assignment.officeId())
                    .param("primary", assignment.primary())
                    .param("accessLevel", code(assignment.accessLevel()))
                    .param("validUntil", assignment.validUntil() == null
                            ? null : java.sql.Timestamp.from(assignment.validUntil()))
                    .param("assignedBy", actor.userId()).update();
            jdbcClient.sql("""
                            INSERT INTO office_membership_history
                                (organization_id, office_id, user_id, action,
                                 access_level, is_primary, valid_until, actor_user_id)
                            VALUES
                                (:organizationId, :officeId, :userId, 'ASSIGNED',
                                 :accessLevel, :primary, :validUntil, :actorId)
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("officeId", assignment.officeId())
                    .param("userId", userId)
                    .param("accessLevel", code(assignment.accessLevel()))
                    .param("primary", assignment.primary())
                    .param("validUntil", assignment.validUntil() == null
                            ? null : java.sql.Timestamp.from(assignment.validUntil()))
                    .param("actorId", actor.userId()).update();
        }
        auditService.record(
                actor, "USER_ACCESS_UPDATE", "USER", userId,
                "SUCCESS", null, Map.of(
                        "roles", requestedRoles,
                        "officeCount", request.offices().size(),
                        "status", status
                )
        );
        return user(userId, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public EffectiveAccessView effectiveAccess(UUID userId) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ADMIN_CONSOLE_VIEW");
        UserAccessView target = userOptional(userId, actor.organizationId())
                .orElseThrow(() -> new BusinessException(
                        "USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND
                ));
        List<EffectiveRoleView> roles = jdbcClient.sql("""
                        SELECT r.code, r.name, COUNT(DISTINCT rp.permission_id) AS permission_count
                        FROM roles r
                        JOIN user_roles ur ON ur.role_id = r.id
                        LEFT JOIN role_permissions rp ON rp.role_id = r.id
                        WHERE ur.user_id = :userId
                          AND r.organization_id = :organizationId
                        GROUP BY r.id
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new EffectiveRoleView(
                        rs.getString("code"), rs.getString("name"),
                        rs.getInt("permission_count")
                )).list();
        List<EffectivePermissionView> permissions = jdbcClient.sql("""
                        SELECT p.code, p.name, p.resource_type, p.action,
                               array_agg(DISTINCT r.code ORDER BY r.code) AS source_roles
                        FROM permissions p
                        JOIN role_permissions rp ON rp.permission_id = p.id
                        JOIN roles r ON r.id = rp.role_id
                        JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                          AND r.organization_id = :organizationId
                        GROUP BY p.id
                        ORDER BY p.resource_type, p.action, p.code
                        """)
                .param("userId", userId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new EffectivePermissionView(
                        rs.getString("code"), rs.getString("name"),
                        rs.getString("resource_type"), rs.getString("action"),
                        List.of((String[]) rs.getArray("source_roles").getArray())
                )).list();
        Instant evaluatedAt = Instant.now();
        List<EffectiveOfficeView> offices = jdbcClient.sql("""
                        SELECT o.id, o.code, o.name_zh, o.name_en,
                               uo.access_level, uo.is_primary, uo.valid_until
                        FROM user_offices uo
                        JOIN offices o ON o.id = uo.office_id
                        WHERE uo.user_id = :userId
                          AND o.organization_id = :organizationId
                        ORDER BY uo.is_primary DESC, o.code
                        """)
                .param("userId", userId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> {
                    Instant validUntil = rs.getTimestamp("valid_until") == null ? null
                            : rs.getTimestamp("valid_until").toInstant();
                    return new EffectiveOfficeView(
                            rs.getObject("id", UUID.class), rs.getString("code"),
                            rs.getString("name_zh"), rs.getString("name_en"),
                            rs.getString("access_level"), rs.getBoolean("is_primary"),
                            validUntil, validUntil == null || validUntil.isAfter(evaluatedAt)
                    );
                }).list();
        List<String> warnings = AdminAccessRiskPolicy.warnings(
                target.status(),
                roles.stream().map(EffectiveRoleView::code).toList(),
                offices.stream()
                        .map(office -> new AdminAccessRiskPolicy.OfficeGrant(
                                office.primary(), office.validUntil()
                        )).toList(),
                evaluatedAt
        );
        return new EffectiveAccessView(
                target.id(), target.displayName(), target.status(),
                roles, permissions, offices, warnings, evaluatedAt
        );
    }

    @Transactional(readOnly = true)
    public List<WorkflowRuleView> workflowRules() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ADMIN_CONSOLE_VIEW");
        var scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT id, office_id, business_type, amount_threshold, currency,
                               assignee_role_code, fallback_user_id, reminder_minutes,
                               escalation_minutes, enabled, priority, updated_at
                        FROM workflow_assignment_rules
                        WHERE organization_id = :organizationId
                          AND (:globalAccess OR office_id IS NULL OR office_id IN (:officeIds))
                        ORDER BY business_type, priority
                        LIMIT 500
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(AdminService::mapWorkflowRule).list();
    }

    @Transactional
    public WorkflowRuleView createWorkflowRule(WorkflowRuleRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "WORKFLOW_CONFIG_MANAGE");
        if (request.escalationMinutes() < request.reminderMinutes()) {
            throw invalid("WORKFLOW_ESCALATION_INVALID");
        }
        UUID officeId = request.officeId() == null ? null
                : officeAccessService.resolveManagedOffice(actor, request.officeId());
        if (request.fallbackUserId() != null) {
            if (officeId == null) {
                Boolean active = jdbcClient.sql("""
                                SELECT EXISTS (
                                  SELECT 1 FROM users WHERE id = :id
                                    AND organization_id = :organizationId
                                    AND status = 'ACTIVE' AND deleted_at IS NULL
                                )
                                """)
                        .param("id", request.fallbackUserId())
                        .param("organizationId", actor.organizationId())
                        .query(Boolean.class).single();
                if (!Boolean.TRUE.equals(active)) {
                    throw invalid("WORKFLOW_FALLBACK_INVALID");
                }
            } else {
                officeAccessService.requireUserInOffice(
                        actor, request.fallbackUserId(), officeId
                );
            }
        }
        if (request.assigneeRoleCode() != null) {
            Boolean role = jdbcClient.sql("""
                            SELECT EXISTS (
                              SELECT 1 FROM roles WHERE organization_id = :organizationId
                                AND code = :code
                            )
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("code", code(request.assigneeRoleCode()))
                    .query(Boolean.class).single();
            if (!Boolean.TRUE.equals(role)) {
                throw invalid("WORKFLOW_ROLE_INVALID");
            }
        }
        UUID id = jdbcClient.sql("""
                        INSERT INTO workflow_assignment_rules
                            (organization_id, office_id, business_type, amount_threshold,
                             currency, assignee_role_code, fallback_user_id,
                             reminder_minutes, escalation_minutes, priority, created_by)
                        VALUES
                            (:organizationId, :officeId, :businessType, :threshold,
                             :currency, :roleCode, :fallbackUserId,
                             :reminder, :escalation, :priority, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("businessType", code(request.businessType()))
                .param("threshold", request.amountThreshold())
                .param("currency", request.currency() == null ? null
                        : request.currency().toUpperCase(java.util.Locale.ROOT))
                .param("roleCode", request.assigneeRoleCode() == null ? null
                        : code(request.assigneeRoleCode()))
                .param("fallbackUserId", request.fallbackUserId())
                .param("reminder", request.reminderMinutes())
                .param("escalation", request.escalationMinutes())
                .param("priority", request.priority())
                .param("createdBy", actor.userId())
                .query(UUID.class).single();
        auditService.success(actor, "WORKFLOW_RULE_CREATE", "WORKFLOW_RULE", id);
        return workflowRule(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<IntegrationHealthView> integrations() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "INTEGRATION_HEALTH_VIEW");
        Instant now = Instant.now();
        List<IntegrationHealthView> result = new ArrayList<>();
        boolean dingtalkConfigured = "dingtalk".equalsIgnoreCase(authMode)
                && dingTalkAppKey != null && !dingTalkAppKey.isBlank();
        result.add(new IntegrationHealthView(
                "DINGTALK", "DINGTALK_OAUTH",
                dingtalkConfigured ? "CONFIGURED" : "DISCONNECTED",
                dingtalkConfigured ? "UP" : "UNKNOWN",
                dingtalkConfigured ? "OAuth configuration present" : "Customer credentials required",
                now
        ));
        boolean storage = storageService.isHealthy();
        result.add(new IntegrationHealthView(
                "STORAGE", "MINIO_S3", "CONFIGURED", storage ? "UP" : "DOWN",
                storage ? "Private bucket available" : "Private bucket unavailable", now
        ));
        boolean scan = scanner.isHealthy();
        result.add(new IntegrationHealthView(
                "SCANNER", scanner.provider(), "CONFIGURED", scan ? "UP" : "DOWN",
                scan ? "Scanner responded" : "Scanner unavailable", now
        ));
        result.add(new IntegrationHealthView(
                "OCR", ocrProvider.provider(), "CONFIGURED", "UP",
                "Safe OCR adapter loaded", now
        ));
        for (String type : List.of("ESIGN", "MAIL", "CALENDAR", "FAPIAO", "ACCOUNTING")) {
            result.add(new IntegrationHealthView(
                    type, "NOT_CONFIGURED", "DISCONNECTED", "UNKNOWN",
                    "Provider adapter boundary ready; customer configuration required", now
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> importTemplate(String requestedType) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "DATA_IMPORT_MANAGE");
        String type = code(requestedType);
        String header = switch (type) {
            case "USERS" -> "username,display_name,email,status,office_code,role_code\n";
            case "MATTERS" -> "matter_number,title,matter_type,office_code,responsible_username\n";
            case "CLIENTS" -> "client_number,name,client_type,owner_username\n";
            default -> throw invalid("IMPORT_TYPE_INVALID");
        };
        byte[] bytes = header.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(type.toLowerCase(java.util.Locale.ROOT) + "-template.csv")
                .build());
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @Transactional
    public ImportJobView validateImport(ImportRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "DATA_IMPORT_MANAGE");
        String type = code(request.importType());
        if (!IMPORT_TYPES.contains(type)) {
            throw invalid("IMPORT_TYPE_INVALID");
        }
        UUID jobId = jdbcClient.sql("""
                        INSERT INTO data_import_jobs
                            (organization_id, import_type, original_filename,
                             total_rows, requested_by)
                        VALUES
                            (:organizationId, :type, :filename, :totalRows, :requestedBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("type", type)
                .param("filename", request.originalFilename().trim())
                .param("totalRows", request.rows().size())
                .param("requestedBy", actor.userId())
                .query(UUID.class).single();
        List<ImportErrorView> errors = new ArrayList<>();
        Set<String> uniqueKeys = new HashSet<>();
        int rowNumber = 1;
        for (Map<String, String> row : request.rows()) {
            rowNumber++;
            String keyField = switch (type) {
                case "USERS" -> "username";
                case "MATTERS" -> "matter_number";
                default -> "client_number";
            };
            String key = trim(row.get(keyField));
            if (key == null) {
                errors.add(new ImportErrorView(
                        rowNumber, keyField, "REQUIRED", "Required value is missing", null
                ));
            } else if (!uniqueKeys.add(key)) {
                errors.add(new ImportErrorView(
                        rowNumber, keyField, "DUPLICATE_IN_FILE",
                        "Duplicate key in import file", key
                ));
            }
            String officeCode = trim(row.get("office_code"));
            if (officeCode != null && !officeCodeExists(actor.organizationId(), officeCode)) {
                errors.add(new ImportErrorView(
                        rowNumber, "office_code", "OFFICE_NOT_FOUND",
                        "Office does not belong to this organization", officeCode
                ));
            }
        }
        for (ImportErrorView error : errors) {
            jdbcClient.sql("""
                            INSERT INTO data_import_errors
                                (import_job_id, row_number, field_name, error_code,
                                 error_message, rejected_value)
                            VALUES
                                (:jobId, :rowNumber, :field, :code, :message, :value)
                            """)
                    .param("jobId", jobId).param("rowNumber", error.rowNumber())
                    .param("field", error.fieldName()).param("code", error.errorCode())
                    .param("message", error.errorMessage()).param("value", error.rejectedValue())
                    .update();
        }
        int valid = request.rows().size() - errors.stream()
                .map(ImportErrorView::rowNumber).collect(java.util.stream.Collectors.toSet()).size();
        jdbcClient.sql("""
                        UPDATE data_import_jobs
                        SET status = :status, valid_rows = :validRows,
                            error_rows = :errorRows, completed_at = now()
                        WHERE id = :id
                        """)
                .param("status", errors.isEmpty() ? "VALIDATED" : "FAILED")
                .param("validRows", valid)
                .param("errorRows", request.rows().size() - valid)
                .param("id", jobId).update();
        auditService.record(
                actor, "DATA_IMPORT_VALIDATE", "DATA_IMPORT_JOB", jobId,
                errors.isEmpty() ? "SUCCESS" : "FAILURE",
                errors.isEmpty() ? null : "ROW_VALIDATION_FAILED",
                Map.of("totalRows", request.rows().size(), "validRows", valid)
        );
        return new ImportJobView(
                jobId, type, errors.isEmpty() ? "VALIDATED" : "FAILED",
                request.rows().size(), valid, request.rows().size() - valid, 0,
                List.copyOf(errors), Instant.now(), Instant.now()
        );
    }

    private OrganizationSettingsView settings(UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT organization_id, brand_name_zh, brand_name_en,
                               short_name_zh, short_name_en, default_locale,
                               supported_locales, primary_timezone, base_currency,
                               website_url, updated_at
                        FROM organization_settings WHERE organization_id = :organizationId
                        """)
                .param("organizationId", organizationId)
                .query((rs, rowNum) -> new OrganizationSettingsView(
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("brand_name_zh"), rs.getString("brand_name_en"),
                        rs.getString("short_name_zh"), rs.getString("short_name_en"),
                        rs.getString("default_locale"),
                        List.of((String[]) rs.getArray("supported_locales").getArray()),
                        rs.getString("primary_timezone"), rs.getString("base_currency"),
                        rs.getString("website_url"), rs.getTimestamp("updated_at").toInstant()
                )).single();
    }

    private UserAccessView user(UUID id, UUID organizationId) {
        return userOptional(id, organizationId).orElseThrow(() -> new BusinessException(
                "USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND
        ));
    }

    private java.util.Optional<UserAccessView> userOptional(UUID id, UUID organizationId) {
        java.util.Optional<UserBase> base = jdbcClient.sql("""
                        SELECT id, username, display_name, email, status, updated_at
                        FROM users WHERE id = :id AND organization_id = :organizationId
                          AND deleted_at IS NULL
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query((rs, rowNum) -> new UserBase(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("email"),
                        rs.getString("status"), rs.getTimestamp("updated_at").toInstant()
                )).optional();
        if (base.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<String> roleCodes = jdbcClient.sql("""
                        SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId ORDER BY r.code
                        """)
                .param("userId", id).query(String.class).list();
        List<OfficeAssignment> offices = jdbcClient.sql("""
                        SELECT office_id, is_primary, access_level, valid_until
                        FROM user_offices WHERE user_id = :userId
                        ORDER BY is_primary DESC, office_id
                        """)
                .param("userId", id)
                .query((rs, rowNum) -> new OfficeAssignment(
                        rs.getObject("office_id", UUID.class),
                        rs.getBoolean("is_primary"), rs.getString("access_level"),
                        rs.getTimestamp("valid_until") == null ? null
                                : rs.getTimestamp("valid_until").toInstant()
                )).list();
        UserBase value = base.orElseThrow();
        return java.util.Optional.of(new UserAccessView(
                value.id(), value.username(), value.displayName(), value.email(), value.status(),
                roleCodes, offices, value.updatedAt()
        ));
    }

    private WorkflowRuleView workflowRule(UUID id, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT id, office_id, business_type, amount_threshold, currency,
                               assignee_role_code, fallback_user_id, reminder_minutes,
                               escalation_minutes, enabled, priority, updated_at
                        FROM workflow_assignment_rules
                        WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("id", id).param("organizationId", organizationId)
                .query(AdminService::mapWorkflowRule).single();
    }

    private RoleView role(UUID organizationId, String roleCode) {
        return jdbcClient.sql("""
                        SELECT r.id, r.code, r.name,
                               COUNT(DISTINCT ur.user_id) AS user_count,
                               COALESCE(array_agg(DISTINCT p.code)
                                 FILTER (WHERE p.code IS NOT NULL), ARRAY[]::varchar[]) AS permissions
                        FROM roles r
                        LEFT JOIN user_roles ur ON ur.role_id = r.id
                        LEFT JOIN role_permissions rp ON rp.role_id = r.id
                        LEFT JOIN permissions p ON p.id = rp.permission_id
                        WHERE r.organization_id = :organizationId AND r.code = :code
                        GROUP BY r.id
                        """)
                .param("organizationId", organizationId)
                .param("code", roleCode)
                .query((rs, rowNum) -> new RoleView(
                        rs.getString("code"), rs.getString("name"),
                        rs.getInt("user_count"),
                        List.of((String[]) rs.getArray("permissions").getArray()),
                        SYSTEM_ROLES.contains(rs.getString("code"))
                )).single();
    }

    private static WorkflowRuleView mapWorkflowRule(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new WorkflowRuleView(
                rs.getObject("id", UUID.class), rs.getObject("office_id", UUID.class),
                rs.getString("business_type"), rs.getBigDecimal("amount_threshold"),
                rs.getString("currency"), rs.getString("assignee_role_code"),
                rs.getObject("fallback_user_id", UUID.class),
                rs.getInt("reminder_minutes"), rs.getInt("escalation_minutes"),
                rs.getBoolean("enabled"), rs.getInt("priority"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private boolean officeCodeExists(UUID organizationId, String officeCode) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM offices WHERE organization_id = :organizationId
                            AND code = :code AND status = 'ACTIVE'
                        )
                        """)
                .param("organizationId", organizationId)
                .param("code", officeCode.trim().toUpperCase(java.util.Locale.ROOT))
                .query(Boolean.class).single());
    }

    private static String code(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BusinessException invalid(String code) {
        return new BusinessException(code, "管理配置参数不合法", HttpStatus.BAD_REQUEST);
    }

    private record RoleId(UUID id, String code) {}
    private record PermissionId(UUID id, String code) {}
    private record UserBase(
            UUID id, String username, String displayName, String email,
            String status, Instant updatedAt
    ) {}
}
