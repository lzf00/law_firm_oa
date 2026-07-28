package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OfficeAccessService {
    private final JdbcClient jdbcClient;
    private final AuthorizationService authorizationService;

    public OfficeAccessService(
            JdbcClient jdbcClient,
            AuthorizationService authorizationService
    ) {
        this.jdbcClient = jdbcClient;
        this.authorizationService = authorizationService;
    }

    public OfficeAccessScope scope(RequestActor actor) {
        boolean global = authorizationService.hasAnyRole(
                actor, "ADMIN", "MANAGING_PARTNER"
        );
        List<OfficeMembership> memberships = jdbcClient.sql("""
                        SELECT uo.office_id, uo.is_primary, uo.access_level
                        FROM user_offices uo
                        JOIN offices o ON o.id = uo.office_id
                        WHERE uo.user_id = :userId
                          AND o.organization_id = :organizationId
                          AND o.status = 'ACTIVE'
                          AND (uo.valid_until IS NULL OR uo.valid_until > now())
                        ORDER BY uo.is_primary DESC, o.code
                        """)
                .param("userId", actor.userId())
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new OfficeMembership(
                        rs.getObject("office_id", UUID.class),
                        rs.getBoolean("is_primary"),
                        rs.getString("access_level")
                ))
                .list();
        LinkedHashSet<UUID> memberIds = new LinkedHashSet<>();
        LinkedHashSet<UUID> managerIds = new LinkedHashSet<>();
        UUID primaryId = null;
        for (OfficeMembership membership : memberships) {
            memberIds.add(membership.officeId());
            if (membership.primary() && primaryId == null) {
                primaryId = membership.officeId();
            }
            if ("MANAGER".equals(membership.accessLevel())) {
                managerIds.add(membership.officeId());
            }
        }
        if (primaryId == null) {
            primaryId = jdbcClient.sql("""
                            SELECT primary_office_id
                            FROM users
                            WHERE id = :userId AND organization_id = :organizationId
                            """)
                    .param("userId", actor.userId())
                    .param("organizationId", actor.organizationId())
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
            if (primaryId != null) {
                memberIds.add(primaryId);
            }
        }
        return new OfficeAccessScope(global, primaryId, memberIds, managerIds);
    }

    public UUID resolveAccessibleOffice(RequestActor actor, UUID requestedOfficeId) {
        OfficeAccessScope scope = scope(actor);
        UUID officeId = requestedOfficeId == null
                ? scope.primaryOfficeId() : requestedOfficeId;
        if (officeId == null || !officeExists(actor, officeId)) {
            throw new BusinessException(
                    "OFFICE_INVALID", "办公室不存在、已停用或账号尚未分配办公室",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (!scope.canAccess(officeId)) {
            throw new BusinessException(
                    "OFFICE_ACCESS_DENIED", "无权访问所选办公室",
                    HttpStatus.FORBIDDEN
            );
        }
        return officeId;
    }

    public UUID resolveManagedOffice(RequestActor actor, UUID requestedOfficeId) {
        UUID officeId = resolveAccessibleOffice(actor, requestedOfficeId);
        if (!scope(actor).canManage(officeId)) {
            throw new BusinessException(
                    "OFFICE_MANAGE_DENIED", "无权管理所选办公室",
                    HttpStatus.FORBIDDEN
            );
        }
        return officeId;
    }

    public void requireUserInOffice(RequestActor actor, UUID userId, UUID officeId) {
        Boolean exists = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM users u
                            LEFT JOIN user_offices uo
                              ON uo.user_id = u.id AND uo.office_id = :officeId
                             AND (uo.valid_until IS NULL OR uo.valid_until > now())
                            WHERE u.id = :userId
                              AND u.organization_id = :organizationId
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                              AND (uo.office_id IS NOT NULL OR u.primary_office_id = :officeId)
                        )
                        """)
                .param("userId", userId)
                .param("officeId", officeId)
                .param("organizationId", actor.organizationId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(
                    "USER_OFFICE_MISMATCH", "所选用户不属于该办公室",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    public boolean canAccessBusiness(
            RequestActor actor,
            String businessType,
            UUID businessId
    ) {
        OfficeAccessScope scope = scope(actor);
        BusinessScope business = switch (businessType) {
            case "MATTER" -> new BusinessScope(
                    "matters",
                    "b.office_id",
                    """
                    EXISTS (
                      SELECT 1 FROM matter_members mm
                      WHERE mm.matter_id = b.id AND mm.user_id = :userId
                        AND mm.left_at IS NULL
                    )
                    """
            );
            case "CONTRACT" -> new BusinessScope(
                    "contracts",
                    """
                    COALESCE(
                      (SELECT m.office_id
                       FROM contract_matters cm
                       JOIN matters m ON m.id = cm.matter_id
                       WHERE cm.contract_id = b.id
                       ORDER BY m.created_at
                       LIMIT 1),
                      (SELECT u.primary_office_id FROM users u WHERE u.id = b.created_by)
                    )
                    """,
                    """
                    EXISTS (
                      SELECT 1 FROM contract_members cm
                      WHERE cm.contract_id = b.id AND cm.user_id = :userId
                    )
                    """
            );
            case "SEAL_REQUEST" -> new BusinessScope(
                    "seal_requests",
                    """
                    COALESCE(
                      (SELECT m.office_id FROM matters m WHERE m.id = b.matter_id),
                      (SELECT m.office_id
                       FROM contract_matters cm
                       JOIN matters m ON m.id = cm.matter_id
                       WHERE cm.contract_id = b.contract_id
                       ORDER BY m.created_at
                       LIMIT 1),
                      (SELECT u.primary_office_id FROM users u WHERE u.id = b.requested_by)
                    )
                    """,
                    "b.requested_by = :userId"
            );
            case "LEAVE_REQUEST" -> new BusinessScope(
                    "leave_requests", "b.office_id", "b.applicant_user_id = :userId"
            );
            case "EXPENSE_CLAIM" -> new BusinessScope(
                    "expense_claims", "b.office_id", "b.applicant_user_id = :userId"
            );
            default -> null;
        };
        if (business == null) {
            return false;
        }
        Boolean visible = jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM %s b
                          WHERE b.id = :businessId
                            AND b.organization_id = :organizationId
                            AND (
                              :globalAccess
                              OR %s
                              OR %s IN (:officeIds)
                            )
                        )
                        """.formatted(
                                business.table(),
                                business.directAccess(),
                                business.officeExpression()
                        ))
                .param("businessId", businessId)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(visible);
    }

    private boolean officeExists(RequestActor actor, UUID officeId) {
        Boolean exists = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM offices
                            WHERE id = :officeId
                              AND organization_id = :organizationId
                              AND status = 'ACTIVE'
                        )
                        """)
                .param("officeId", officeId)
                .param("organizationId", actor.organizationId())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private record OfficeMembership(UUID officeId, boolean primary, String accessLevel) {}

    private record BusinessScope(
            String table,
            String officeExpression,
            String directAccess
    ) {}
}
