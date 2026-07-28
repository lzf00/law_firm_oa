package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.OfficeController.OfficeMemberView;
import com.zoro.legaloa.identity.OfficeController.UpsertOfficeMemberRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficeMembershipService {
    private static final Set<String> ACCESS_LEVELS = Set.of("MEMBER", "MANAGER");

    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final OfficeAccessService officeAccessService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public OfficeMembershipService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            OfficeAccessService officeAccessService,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.officeAccessService = officeAccessService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<OfficeMemberView> list(UUID officeId) {
        RequestActor actor = actorProvider.current();
        UUID managedOfficeId = officeAccessService.resolveManagedOffice(actor, officeId);
        return jdbcClient.sql("""
                        SELECT u.id, u.username, u.display_name, u.email,
                               uo.access_level, uo.is_primary, uo.valid_until,
                               uo.created_at, uo.updated_at,
                               assigned.display_name AS assigned_by_name
                        FROM user_offices uo
                        JOIN users u ON u.id = uo.user_id
                        LEFT JOIN users assigned ON assigned.id = uo.assigned_by
                        WHERE uo.office_id = :officeId
                          AND u.organization_id = :organizationId
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          AND (uo.valid_until IS NULL OR uo.valid_until > now())
                        ORDER BY uo.is_primary DESC, u.display_name
                        """)
                .param("officeId", managedOfficeId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new OfficeMemberView(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("access_level"),
                        rs.getBoolean("is_primary"),
                        toInstant(rs.getTimestamp("valid_until")),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        rs.getString("assigned_by_name")
                ))
                .list();
    }

    @Transactional
    public OfficeMemberView upsert(
            UUID officeId,
            UUID userId,
            UpsertOfficeMemberRequest request
    ) {
        RequestActor actor = actorProvider.current();
        UUID managedOfficeId = officeAccessService.resolveManagedOffice(actor, officeId);
        boolean globalAdministrator = authorizationService.hasAnyRole(
                actor, "ADMIN", "MANAGING_PARTNER"
        );
        String accessLevel = request.accessLevel().toUpperCase(Locale.ROOT);
        if (!ACCESS_LEVELS.contains(accessLevel)) {
            throw new BusinessException(
                    "OFFICE_ACCESS_LEVEL_INVALID", "办公室授权级别无效", HttpStatus.BAD_REQUEST
            );
        }
        if (!OfficeMembershipPolicy.canGrant(
                globalAdministrator, accessLevel, request.primary()
        )) {
            throw new BusinessException(
                    "OFFICE_GRANT_DENIED", "办公室管理员只能授予普通成员权限",
                    HttpStatus.FORBIDDEN
            );
        }
        if (!OfficeMembershipPolicy.validPrimaryExpiry(
                request.primary(), request.validUntil()
        )) {
            throw new BusinessException(
                    "OFFICE_PRIMARY_EXPIRY_INVALID", "主办公室授权不能设置到期时间",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (request.validUntil() != null && !request.validUntil().isAfter(Instant.now())) {
            throw new BusinessException(
                    "OFFICE_GRANT_EXPIRED", "授权到期时间必须晚于当前时间",
                    HttpStatus.BAD_REQUEST
            );
        }
        requireActiveUser(actor, userId);

        ExistingMembership existing = jdbcClient.sql("""
                        SELECT access_level, is_primary
                        FROM user_offices
                        WHERE user_id = :userId AND office_id = :officeId
                        """)
                .param("userId", userId)
                .param("officeId", managedOfficeId)
                .query((rs, rowNum) -> new ExistingMembership(
                        rs.getString("access_level"), rs.getBoolean("is_primary")
                ))
                .optional()
                .orElse(null);
        if (existing != null && !OfficeMembershipPolicy.canModifyExisting(
                globalAdministrator, existing.accessLevel(), existing.primary()
        )) {
            throw new BusinessException(
                    "OFFICE_GRANT_DENIED",
                    "办公室管理员不能调整主办公室或其他办公室管理员",
                    HttpStatus.FORBIDDEN
            );
        }
        if (existing != null && existing.primary() && !request.primary()) {
            throw new BusinessException(
                    "OFFICE_PRIMARY_REVOKE_DENIED",
                    "请先为该成员设置新的主办公室，再调整当前授权",
                    HttpStatus.CONFLICT
            );
        }
        if (request.primary()) {
            jdbcClient.sql("""
                            UPDATE user_offices
                            SET is_primary = FALSE, updated_at = now()
                            WHERE user_id = :userId AND is_primary = TRUE
                            """)
                    .param("userId", userId)
                    .update();
        }
        jdbcClient.sql("""
                        INSERT INTO user_offices
                            (user_id, office_id, is_primary, access_level,
                             valid_until, assigned_by, updated_at)
                        VALUES
                            (:userId, :officeId, :primary, :accessLevel,
                             :validUntil, :actorId, now())
                        ON CONFLICT (user_id, office_id) DO UPDATE SET
                            is_primary = EXCLUDED.is_primary,
                            access_level = EXCLUDED.access_level,
                            valid_until = EXCLUDED.valid_until,
                            assigned_by = EXCLUDED.assigned_by,
                            updated_at = now()
                        """)
                .param("userId", userId)
                .param("officeId", managedOfficeId)
                .param("primary", request.primary())
                .param("accessLevel", accessLevel)
                .param("validUntil", timestamp(request.validUntil()))
                .param("actorId", actor.userId())
                .update();
        if (request.primary()) {
            jdbcClient.sql("""
                            UPDATE users SET primary_office_id = :officeId, updated_at = now()
                            WHERE id = :userId AND organization_id = :organizationId
                            """)
                    .param("officeId", managedOfficeId)
                    .param("userId", userId)
                    .param("organizationId", actor.organizationId())
                    .update();
        }
        recordHistory(
                actor, managedOfficeId, userId, existing != null ? "UPDATED" : "ASSIGNED",
                accessLevel, request.primary(), request.validUntil()
        );
        auditService.record(
                actor,
                existing != null ? "OFFICE_MEMBER_UPDATE" : "OFFICE_MEMBER_ASSIGN",
                "OFFICE", managedOfficeId, "SUCCESS", null,
                Map.of("targetUserId", userId, "accessLevel", accessLevel)
        );
        return find(managedOfficeId, userId);
    }

    @Transactional
    public void revoke(UUID officeId, UUID userId) {
        RequestActor actor = actorProvider.current();
        UUID managedOfficeId = officeAccessService.resolveManagedOffice(actor, officeId);
        Membership membership = jdbcClient.sql("""
                        SELECT access_level, is_primary, valid_until
                        FROM user_offices
                        WHERE user_id = :userId AND office_id = :officeId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("officeId", managedOfficeId)
                .query((rs, rowNum) -> new Membership(
                        rs.getString("access_level"),
                        rs.getBoolean("is_primary"),
                        toInstant(rs.getTimestamp("valid_until"))
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "OFFICE_MEMBERSHIP_NOT_FOUND", "办公室成员授权不存在",
                        HttpStatus.NOT_FOUND
                ));
        if (membership.primary()) {
            throw new BusinessException(
                    "OFFICE_PRIMARY_REVOKE_DENIED",
                    "不能直接撤销主办公室，请先为该成员设置新的主办公室",
                    HttpStatus.CONFLICT
            );
        }
        if ("MANAGER".equals(membership.accessLevel())
                && !authorizationService.hasAnyRole(actor, "ADMIN", "MANAGING_PARTNER")) {
            throw new BusinessException(
                    "OFFICE_GRANT_DENIED", "办公室管理员不能撤销管理员授权",
                    HttpStatus.FORBIDDEN
            );
        }
        jdbcClient.sql("""
                        DELETE FROM user_offices
                        WHERE user_id = :userId AND office_id = :officeId
                        """)
                .param("userId", userId)
                .param("officeId", managedOfficeId)
                .update();
        recordHistory(
                actor, managedOfficeId, userId, "REVOKED",
                membership.accessLevel(), membership.primary(), membership.validUntil()
        );
        auditService.record(
                actor, "OFFICE_MEMBER_REVOKE", "OFFICE", managedOfficeId,
                "SUCCESS", null, Map.of("targetUserId", userId)
        );
    }

    private OfficeMemberView find(UUID officeId, UUID userId) {
        return list(officeId).stream()
                .filter(item -> item.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Saved membership is not visible"));
    }

    private void requireActiveUser(RequestActor actor, UUID userId) {
        Boolean exists = jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM users
                          WHERE id = :userId AND organization_id = :organizationId
                            AND status = 'ACTIVE' AND deleted_at IS NULL
                        )
                        """)
                .param("userId", userId)
                .param("organizationId", actor.organizationId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(
                    "OFFICE_MEMBER_INVALID", "待授权用户不存在或不属于当前组织",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void recordHistory(
            RequestActor actor,
            UUID officeId,
            UUID userId,
            String action,
            String accessLevel,
            boolean primary,
            Instant validUntil
    ) {
        jdbcClient.sql("""
                        INSERT INTO office_membership_history
                            (organization_id, office_id, user_id, action, access_level,
                             is_primary, valid_until, actor_user_id)
                        VALUES
                            (:organizationId, :officeId, :userId, :action, :accessLevel,
                             :primary, :validUntil, :actorId)
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", officeId)
                .param("userId", userId)
                .param("action", action)
                .param("accessLevel", accessLevel)
                .param("primary", primary)
                .param("validUntil", timestamp(validUntil))
                .param("actorId", actor.userId())
                .update();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private record Membership(String accessLevel, boolean primary, Instant validUntil) {}

    private record ExistingMembership(String accessLevel, boolean primary) {}
}
