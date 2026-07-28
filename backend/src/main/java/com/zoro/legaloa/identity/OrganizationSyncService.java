package com.zoro.legaloa.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.OrganizationSyncController.DepartmentSnapshot;
import com.zoro.legaloa.identity.OrganizationSyncController.OrganizationSyncRequest;
import com.zoro.legaloa.identity.OrganizationSyncController.OrganizationSyncResult;
import com.zoro.legaloa.identity.OrganizationSyncController.OrganizationSyncDryRun;
import com.zoro.legaloa.identity.OrganizationSyncController.UserSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrganizationSyncService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OrganizationSyncService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            AuditService auditService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public OrganizationSyncResult sync(OrganizationSyncRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ORGANIZATION_SYNC");
        String provider = request.provider().toUpperCase(Locale.ROOT);
        String mode = request.mode() == null ? "SNAPSHOT" : request.mode().toUpperCase(Locale.ROOT);
        if (!Set.of("DINGTALK", "FEISHU", "MANUAL").contains(provider)) {
            throw new BusinessException(
                    "SYNC_PROVIDER_INVALID", "组织同步来源无效", HttpStatus.BAD_REQUEST
            );
        }
        if (!Set.of("FULL", "INCREMENTAL", "SNAPSHOT").contains(mode)) {
            throw new BusinessException(
                    "SYNC_MODE_INVALID", "组织同步模式无效", HttpStatus.BAD_REQUEST
            );
        }
        UUID runId = jdbcClient.sql("""
                        INSERT INTO organization_sync_runs
                            (organization_id, provider, mode, status,
                             departments_seen, users_seen, requested_by)
                        VALUES
                            (:organizationId, :provider, :mode, 'RUNNING',
                             :departmentsSeen, :usersSeen, :requestedBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("provider", provider)
                .param("mode", mode)
                .param("departmentsSeen", request.departments().size())
                .param("usersSeen", request.users().size())
                .param("requestedBy", actor.userId())
                .query(UUID.class)
                .single();
        try {
            SyncCounts counts = transactionTemplate.execute(status ->
                    applySnapshot(actor, provider, mode, request)
            );
            OrganizationSyncResult result = new OrganizationSyncResult(
                    runId,
                    provider,
                    mode,
                    "SUCCEEDED",
                    request.departments().size(),
                    request.users().size(),
                    counts == null ? 0 : counts.departmentsChanged(),
                    counts == null ? 0 : counts.usersChanged()
            );
            jdbcClient.sql("""
                            UPDATE organization_sync_runs
                            SET status = 'SUCCEEDED',
                                departments_changed = :departmentsChanged,
                                users_changed = :usersChanged,
                                completed_at = now()
                            WHERE id = :runId
                            """)
                    .param("departmentsChanged", result.departmentsChanged())
                    .param("usersChanged", result.usersChanged())
                    .param("runId", runId)
                    .update();
            auditService.record(
                    actor,
                    "ORGANIZATION_SYNC",
                    "ORGANIZATION",
                    actor.organizationId(),
                    "SUCCESS",
                    null,
                    Map.of(
                            "provider", provider,
                            "mode", mode,
                            "departmentsChanged", result.departmentsChanged(),
                            "usersChanged", result.usersChanged()
                    )
            );
            return result;
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            jdbcClient.sql("""
                            UPDATE organization_sync_runs
                            SET status = 'FAILED', error_message = :message, completed_at = now()
                            WHERE id = :runId
                            """)
                    .param("message", message.substring(0, Math.min(message.length(), 1000)))
                    .param("runId", runId)
                    .update();
            auditService.failure(
                    actor, "ORGANIZATION_SYNC", "ORGANIZATION", actor.organizationId(), message
            );
            throw exception;
        }
    }

    public OrganizationSyncDryRun dryRun(OrganizationSyncRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ORGANIZATION_SYNC");
        String provider = request.provider().toUpperCase(Locale.ROOT);
        if (!Set.of("DINGTALK", "FEISHU", "MANUAL").contains(provider)) {
            throw new BusinessException(
                    "SYNC_PROVIDER_INVALID", "组织同步来源无效", HttpStatus.BAD_REQUEST
            );
        }
        int departmentsToCreate = 0;
        int departmentsToUpdate = 0;
        for (DepartmentSnapshot department : request.departments()) {
            if (findExternalDepartment(
                    actor.organizationId(), provider, department.externalDepartmentId()
            ) == null) {
                departmentsToCreate++;
            } else {
                departmentsToUpdate++;
            }
        }
        int usersToCreate = 0;
        int usersToUpdate = 0;
        Set<UUID> seen = new HashSet<>();
        for (UserSnapshot user : request.users()) {
            UUID existing = findExternalUser(
                    actor.organizationId(), provider, user.externalUserId()
            );
            if (existing == null) {
                existing = jdbcClient.sql("""
                                SELECT id FROM users
                                WHERE organization_id = :organizationId
                                  AND username = :username
                                """)
                        .param("organizationId", actor.organizationId())
                        .param("username", user.username().trim())
                        .query(UUID.class).optional().orElse(null);
            }
            if (existing == null) {
                usersToCreate++;
            } else {
                usersToUpdate++;
                seen.add(existing);
            }
        }
        int usersToDeactivate = 0;
        if ("FULL".equalsIgnoreCase(request.mode()) && request.deactivateMissingUsers()) {
            usersToDeactivate = jdbcClient.sql("""
                            SELECT COUNT(*)
                            FROM users u
                            JOIN external_identities ei ON ei.user_id = u.id
                            WHERE u.organization_id = :organizationId
                              AND ei.provider = :provider AND u.status = 'ACTIVE'
                              AND u.id NOT IN (:seenUsers)
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("provider", provider)
                    .param("seenUsers", seen.isEmpty() ? List.of(new UUID(0, 0)) : seen)
                    .query(Integer.class).single();
        }
        List<String> warnings = request.users().isEmpty()
                ? List.of("Snapshot contains no users; apply is intentionally not automatic")
                : List.of();
        auditService.record(
                actor, "ORGANIZATION_SYNC_DRY_RUN", "ORGANIZATION", actor.organizationId(),
                "SUCCESS", null, Map.of(
                        "provider", provider,
                        "departmentsToCreate", departmentsToCreate,
                        "usersToCreate", usersToCreate,
                        "usersToDeactivate", usersToDeactivate
                )
        );
        return new OrganizationSyncDryRun(
                provider, departmentsToCreate, departmentsToUpdate,
                usersToCreate, usersToUpdate, usersToDeactivate, warnings
        );
    }

    public List<OrganizationSyncResult> runs() {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ORGANIZATION_SYNC");
        return jdbcClient.sql("""
                        SELECT id, provider, mode, status, departments_seen, users_seen,
                               departments_changed, users_changed
                        FROM organization_sync_runs
                        WHERE organization_id = :organizationId
                        ORDER BY started_at DESC
                        LIMIT 100
                        """)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new OrganizationSyncResult(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("mode"),
                        rs.getString("status"),
                        rs.getInt("departments_seen"),
                        rs.getInt("users_seen"),
                        rs.getInt("departments_changed"),
                        rs.getInt("users_changed")
                ))
                .list();
    }

    private SyncCounts applySnapshot(
            RequestActor actor,
            String provider,
            String mode,
            OrganizationSyncRequest request
    ) {
        int departmentsChanged = 0;
        int usersChanged = 0;
        Map<String, UUID> departments = new HashMap<>();
        for (DepartmentSnapshot item : request.departments()) {
            UUID departmentId = findExternalDepartment(
                    actor.organizationId(), provider, item.externalDepartmentId()
            );
            if (departmentId == null) {
                departmentId = jdbcClient.sql("""
                                INSERT INTO departments
                                    (organization_id, name, sort_order, dingtalk_department_id)
                                VALUES
                                    (:organizationId, :name, :sortOrder, :dingtalkDepartmentId)
                                RETURNING id
                                """)
                        .param("organizationId", actor.organizationId())
                        .param("name", item.name().trim())
                        .param("sortOrder", item.sortOrder())
                        .param(
                                "dingtalkDepartmentId",
                                "DINGTALK".equals(provider) ? item.externalDepartmentId() : null
                        )
                        .query(UUID.class)
                        .single();
                jdbcClient.sql("""
                                INSERT INTO external_departments
                                    (organization_id, department_id, provider, external_department_id)
                                VALUES
                                    (:organizationId, :departmentId, :provider, :externalDepartmentId)
                                """)
                        .param("organizationId", actor.organizationId())
                        .param("departmentId", departmentId)
                        .param("provider", provider)
                        .param("externalDepartmentId", item.externalDepartmentId())
                        .update();
            } else {
                jdbcClient.sql("""
                                UPDATE departments
                                SET name = :name, sort_order = :sortOrder,
                                    deleted_at = NULL, updated_at = now()
                                WHERE id = :departmentId
                                """)
                        .param("name", item.name().trim())
                        .param("sortOrder", item.sortOrder())
                        .param("departmentId", departmentId)
                        .update();
                jdbcClient.sql("""
                                UPDATE external_departments
                                SET last_synced_at = now()
                                WHERE organization_id = :organizationId
                                  AND provider = :provider
                                  AND external_department_id = :externalDepartmentId
                                """)
                        .param("organizationId", actor.organizationId())
                        .param("provider", provider)
                        .param("externalDepartmentId", item.externalDepartmentId())
                        .update();
            }
            departments.put(item.externalDepartmentId(), departmentId);
            departmentsChanged++;
        }
        for (DepartmentSnapshot item : request.departments()) {
            UUID departmentId = departments.get(item.externalDepartmentId());
            UUID parentId = item.parentExternalDepartmentId() == null
                    ? null : departments.get(item.parentExternalDepartmentId());
            if (item.parentExternalDepartmentId() != null && parentId == null) {
                throw new BusinessException(
                        "SYNC_PARENT_DEPARTMENT_MISSING",
                        "上级部门不在本次快照中：" + item.parentExternalDepartmentId(),
                        HttpStatus.BAD_REQUEST
                );
            }
            jdbcClient.sql("""
                            UPDATE departments SET parent_id = :parentId, updated_at = now()
                            WHERE id = :departmentId
                            """)
                    .param("parentId", parentId)
                    .param("departmentId", departmentId)
                    .update();
        }

        Set<UUID> seenUsers = new HashSet<>();
        for (UserSnapshot item : request.users()) {
            UUID userId = findExternalUser(
                    actor.organizationId(), provider, item.externalUserId()
            );
            if (userId == null) {
                userId = jdbcClient.sql("""
                                SELECT id FROM users
                                WHERE organization_id = :organizationId AND username = :username
                                """)
                        .param("organizationId", actor.organizationId())
                        .param("username", item.username().trim())
                        .query(UUID.class)
                        .optional()
                        .orElse(null);
            }
            if (userId == null) {
                userId = jdbcClient.sql("""
                                INSERT INTO users
                                    (organization_id, username, display_name, email,
                                     dingtalk_user_id, status)
                                VALUES
                                    (:organizationId, :username, :displayName, :email,
                                     :dingtalkUserId, :status)
                                RETURNING id
                                """)
                        .param("organizationId", actor.organizationId())
                        .param("username", item.username().trim())
                        .param("displayName", item.displayName().trim())
                        .param("email", item.email())
                        .param(
                                "dingtalkUserId",
                                "DINGTALK".equals(provider) ? item.externalUserId() : null
                        )
                        .param("status", normalizedUserStatus(item.status()))
                        .query(UUID.class)
                        .single();
            } else {
                jdbcClient.sql("""
                                UPDATE users
                                SET display_name = :displayName, email = :email,
                                    status = :status, deleted_at = NULL, updated_at = now()
                                WHERE id = :userId
                                """)
                        .param("displayName", item.displayName().trim())
                        .param("email", item.email())
                        .param("status", normalizedUserStatus(item.status()))
                        .param("userId", userId)
                        .update();
            }
            jdbcClient.sql("""
                            INSERT INTO external_identities
                                (organization_id, user_id, provider, external_user_id,
                                 external_open_id, external_union_id, raw_profile)
                            VALUES
                                (:organizationId, :userId, :provider, :externalUserId,
                                 :externalOpenId, :externalUnionId, CAST(:rawProfile AS jsonb))
                            ON CONFLICT (organization_id, provider, external_user_id)
                            DO UPDATE SET
                                user_id = EXCLUDED.user_id,
                                external_open_id = EXCLUDED.external_open_id,
                                external_union_id = EXCLUDED.external_union_id,
                                raw_profile = EXCLUDED.raw_profile,
                                last_synced_at = now()
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("userId", userId)
                    .param("provider", provider)
                    .param("externalUserId", item.externalUserId())
                    .param("externalOpenId", item.externalOpenId())
                    .param("externalUnionId", item.externalUnionId())
                    .param("rawProfile", json(item.rawProfile()))
                    .update();
            jdbcClient.sql("DELETE FROM department_members WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            for (String externalDepartmentId : item.departmentExternalIds()) {
                UUID departmentId = departments.get(externalDepartmentId);
                if (departmentId == null) {
                    throw new BusinessException(
                            "SYNC_USER_DEPARTMENT_MISSING",
                            "用户所属部门不在本次快照中：" + externalDepartmentId,
                            HttpStatus.BAD_REQUEST
                    );
                }
                jdbcClient.sql("""
                                INSERT INTO department_members (department_id, user_id)
                                VALUES (:departmentId, :userId)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("departmentId", departmentId)
                        .param("userId", userId)
                        .update();
            }
            seenUsers.add(userId);
            usersChanged++;
        }
        if ("FULL".equals(mode) && request.deactivateMissingUsers()) {
            List<UUID> safeSeenUsers = seenUsers.isEmpty()
                    ? List.of(new UUID(0, 0)) : seenUsers.stream().toList();
            jdbcClient.sql("""
                            UPDATE users u
                            SET status = 'INACTIVE', updated_at = now()
                            WHERE u.organization_id = :organizationId
                              AND u.id NOT IN (:seenUsers)
                              AND EXISTS (
                                  SELECT 1 FROM external_identities ei
                                  WHERE ei.user_id = u.id AND ei.provider = :provider
                              )
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("seenUsers", safeSeenUsers)
                    .param("provider", provider)
                    .update();
        }
        return new SyncCounts(departmentsChanged, usersChanged);
    }

    private UUID findExternalDepartment(UUID organizationId, String provider, String externalId) {
        return jdbcClient.sql("""
                        SELECT department_id FROM external_departments
                        WHERE organization_id = :organizationId
                          AND provider = :provider
                          AND external_department_id = :externalId
                        """)
                .param("organizationId", organizationId)
                .param("provider", provider)
                .param("externalId", externalId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private UUID findExternalUser(UUID organizationId, String provider, String externalId) {
        return jdbcClient.sql("""
                        SELECT user_id FROM external_identities
                        WHERE organization_id = :organizationId
                          AND provider = :provider
                          AND external_user_id = :externalId
                        """)
                .param("organizationId", organizationId)
                .param("provider", provider)
                .param("externalId", externalId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private static String normalizedUserStatus(String status) {
        return status == null || status.isBlank() ? "ACTIVE" : status.toUpperCase(Locale.ROOT);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("组织用户原始数据无法序列化", exception);
        }
    }

    private record SyncCounts(int departmentsChanged, int usersChanged) {}
}
