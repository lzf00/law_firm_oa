package com.zoro.legaloa.matter;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.matter.ContractController.ContractView;
import com.zoro.legaloa.matter.ContractController.CreateContractRequest;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;

    public ContractService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService,
            AuthorizationService authorizationService,
            OfficeAccessService officeAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
        this.authorizationService = authorizationService;
        this.officeAccessService = officeAccessService;
    }

    @Transactional(readOnly = true)
    public List<ContractView> list() {
        RequestActor actor = actorProvider.current();
        return jdbcClient.sql("""
                        SELECT c.id, c.contract_number, c.title, c.status, c.client_id,
                               p.display_name AS client_name,
                               c.responsible_user_id, u.display_name AS responsible_name,
                               c.effective_date, c.expiry_date, c.amount, c.currency,
                               COUNT(cm.matter_id) AS matter_count,
                               COALESCE(array_agg(cm.matter_id) FILTER (WHERE cm.matter_id IS NOT NULL), '{}')
                                   AS matter_ids,
                               current_version.version_number AS current_version_number,
                               current_version.status AS current_version_status,
                               current_version.signature_status,
                               current_version.signed_at
                        FROM contracts c
                        JOIN users u ON u.id = c.responsible_user_id
                        LEFT JOIN clients cl ON cl.id = c.client_id
                        LEFT JOIN parties p ON p.id = cl.party_id
                        LEFT JOIN contract_matters cm ON cm.contract_id = c.id
                        LEFT JOIN LATERAL (
                            SELECT cv.version_number, cv.status, cv.signature_status, cv.signed_at
                            FROM contract_versions cv
                            WHERE cv.contract_id = c.id
                            ORDER BY
                                CASE cv.status WHEN 'FINAL' THEN 0 ELSE 1 END,
                                cv.version_number DESC
                            LIMIT 1
                        ) current_version ON TRUE
                        WHERE c.organization_id = :organizationId AND c.deleted_at IS NULL
                          AND (
                            EXISTS (
                              SELECT 1 FROM contract_members visible_cm
                              WHERE visible_cm.contract_id = c.id
                                AND visible_cm.user_id = :userId
                            )
                            OR EXISTS (
                              SELECT 1 FROM user_roles ur
                              JOIN roles r ON r.id = ur.role_id
                              WHERE ur.user_id = :userId
                                AND r.code IN ('ADMIN', 'MANAGING_PARTNER')
                            )
                            OR EXISTS (
                              SELECT 1 FROM user_roles ur
                              JOIN role_permissions rp ON rp.role_id = ur.role_id
                              JOIN permissions permission ON permission.id = rp.permission_id
                              WHERE ur.user_id = :userId
                                AND permission.code IN (
                                  'CONTRACT_FINALIZE', 'CONTRACT_SIGN_ARCHIVE'
                                )
                            )
                          )
                        GROUP BY c.id, p.display_name, u.display_name,
                                 current_version.version_number, current_version.status,
                                 current_version.signature_status, current_version.signed_at
                        ORDER BY c.updated_at DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .query((rs, rowNum) -> new ContractView(
                        rs.getObject("id", UUID.class),
                        rs.getString("contract_number"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getObject("client_id", UUID.class),
                        rs.getString("client_name"),
                        rs.getObject("responsible_user_id", UUID.class),
                        rs.getString("responsible_name"),
                        rs.getDate("effective_date") == null ? null : rs.getDate("effective_date").toLocalDate(),
                        rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getInt("matter_count"),
                        List.of((UUID[]) rs.getArray("matter_ids").getArray()),
                        rs.getObject("current_version_number", Integer.class),
                        rs.getString("current_version_status"),
                        rs.getString("signature_status"),
                        rs.getTimestamp("signed_at") == null
                                ? null : rs.getTimestamp("signed_at").toInstant()
                ))
                .list();
    }

    @Transactional
    public ContractView create(CreateContractRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONTRACT_CREATE");
        validateDates(request);
        List<UUID> matterIds = validateMatterAccess(actor, request.matterIds());
        UUID contractId = jdbcClient.sql("""
                        INSERT INTO contracts
                            (organization_id, contract_number, title, client_id,
                             responsible_user_id, effective_date, expiry_date,
                             amount, currency, created_by)
                        SELECT :organizationId, :contractNumber, :title, :clientId,
                               u.id, :effectiveDate, :expiryDate, :amount, :currency, :createdBy
                        FROM users u
                        WHERE u.id = :responsibleUserId AND u.organization_id = :organizationId
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          AND (:noClient OR EXISTS (
                              SELECT 1 FROM clients cl
                              JOIN parties p ON p.id = cl.party_id
                              WHERE cl.id = :clientId AND p.organization_id = :organizationId
                          ))
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("contractNumber", request.contractNumber().trim())
                .param("title", request.title().trim())
                .param("clientId", request.clientId())
                .param("noClient", request.clientId() == null)
                .param("responsibleUserId", request.responsibleUserId())
                .param("effectiveDate", request.effectiveDate())
                .param("expiryDate", request.expiryDate())
                .param("amount", request.amount())
                .param("currency", request.currency() == null ? "CNY" : request.currency().toUpperCase())
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "CONTRACT_CONTEXT_INVALID", "客户或合同负责人无效", HttpStatus.BAD_REQUEST
                ));

        jdbcClient.sql("""
                        INSERT INTO contract_members (contract_id, user_id, member_role, can_download)
                        VALUES (:contractId, :userId, 'RESPONSIBLE', TRUE)
                        """)
                .param("contractId", contractId)
                .param("userId", request.responsibleUserId())
                .update();
        if (!actor.userId().equals(request.responsibleUserId())) {
            jdbcClient.sql("""
                            INSERT INTO contract_members
                                (contract_id, user_id, member_role, can_download)
                            VALUES (:contractId, :userId, 'COUNSEL', TRUE)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("contractId", contractId)
                    .param("userId", actor.userId())
                    .update();
        }

        for (UUID matterId : matterIds) {
            jdbcClient.sql("""
                            INSERT INTO contract_matters (contract_id, matter_id)
                            SELECT :contractId, m.id FROM matters m
                            WHERE m.id = :matterId AND m.organization_id = :organizationId
                              AND m.deleted_at IS NULL
                            ON CONFLICT DO NOTHING
                            """)
                    .param("contractId", contractId)
                    .param("matterId", matterId)
                    .param("organizationId", actor.organizationId())
                    .update();
        }
        auditService.success(actor, "CONTRACT_CREATE", "CONTRACT", contractId);
        return list().stream().filter(item -> item.id().equals(contractId)).findFirst().orElseThrow();
    }

    @Transactional
    public ContractView update(UUID id, CreateContractRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONTRACT_MANAGE");
        validateDates(request);
        String currentStatus = jdbcClient.sql("""
                        SELECT c.status
                        FROM contracts c
                        WHERE c.id = :id AND c.organization_id = :organizationId
                          AND c.deleted_at IS NULL
                          AND (
                            EXISTS (
                              SELECT 1 FROM contract_members cm
                              WHERE cm.contract_id = c.id AND cm.user_id = :actorId
                            )
                            OR EXISTS (
                              SELECT 1 FROM user_roles ur
                              JOIN roles r ON r.id = ur.role_id
                              WHERE ur.user_id = :actorId
                                AND r.code IN ('ADMIN', 'MANAGING_PARTNER')
                            )
                          )
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("actorId", actor.userId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "CONTRACT_CONTEXT_INVALID",
                        "合同不存在或当前用户无权编辑",
                        HttpStatus.NOT_FOUND
                ));
        if (!ContractLifecyclePolicy.canEdit(currentStatus)) {
            throw new BusinessException(
                    "CONTRACT_EDIT_LOCKED",
                    "合同进入审批后，商业字段不可直接修改",
                    HttpStatus.CONFLICT
            );
        }
        List<UUID> matterIds = validateMatterAccess(actor, request.matterIds());
        int updated = jdbcClient.sql("""
                        UPDATE contracts c
                        SET contract_number = :contractNumber,
                            title = :title,
                            client_id = :clientId,
                            responsible_user_id = :responsibleUserId,
                            effective_date = :effectiveDate,
                            expiry_date = :expiryDate,
                            amount = :amount,
                            currency = :currency,
                            updated_at = now()
                        WHERE c.id = :id AND c.organization_id = :organizationId
                          AND c.deleted_at IS NULL
                          AND c.status IN ('DRAFT', 'REJECTED')
                          AND EXISTS (
                              SELECT 1
                              WHERE EXISTS (
                                  SELECT 1 FROM contract_members member
                                  WHERE member.contract_id = c.id
                                    AND member.user_id = :actorId
                              )
                              OR EXISTS (
                                  SELECT 1 FROM user_roles ur
                                  JOIN roles r ON r.id = ur.role_id
                                  WHERE ur.user_id = :actorId
                                    AND r.code IN ('ADMIN', 'MANAGING_PARTNER')
                              )
                          )
                          AND EXISTS (
                              SELECT 1 FROM users u
                              WHERE u.id = :responsibleUserId
                                AND u.organization_id = :organizationId
                                AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          )
                          AND (:noClient OR EXISTS (
                              SELECT 1 FROM clients cl
                              JOIN parties p ON p.id = cl.party_id
                              WHERE cl.id = :clientId
                                AND p.organization_id = :organizationId
                                AND cl.deleted_at IS NULL AND p.deleted_at IS NULL
                          ))
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("actorId", actor.userId())
                .param("contractNumber", request.contractNumber().trim())
                .param("title", request.title().trim())
                .param("clientId", request.clientId())
                .param("noClient", request.clientId() == null)
                .param("responsibleUserId", request.responsibleUserId())
                .param("effectiveDate", request.effectiveDate())
                .param("expiryDate", request.expiryDate())
                .param("amount", request.amount())
                .param("currency", request.currency() == null ? "CNY" : request.currency().toUpperCase())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "CONTRACT_CONTEXT_INVALID", "合同、客户或负责人无效，或当前用户无权编辑",
                    HttpStatus.BAD_REQUEST
            );
        }
        jdbcClient.sql("DELETE FROM contract_matters WHERE contract_id = :contractId")
                .param("contractId", id)
                .update();
        for (UUID matterId : matterIds) {
            int linked = jdbcClient.sql("""
                            INSERT INTO contract_matters (contract_id, matter_id)
                            SELECT :contractId, m.id FROM matters m
                            WHERE m.id = :matterId AND m.organization_id = :organizationId
                              AND m.deleted_at IS NULL
                            ON CONFLICT DO NOTHING
                            """)
                    .param("contractId", id)
                    .param("matterId", matterId)
                    .param("organizationId", actor.organizationId())
                    .param("actorId", actor.userId())
                    .update();
            if (linked == 0) {
                throw new BusinessException(
                        "CONTRACT_MATTER_INVALID", "关联案件不存在或无权访问", HttpStatus.BAD_REQUEST
                );
            }
        }
        jdbcClient.sql("""
                        INSERT INTO contract_members
                            (contract_id, user_id, member_role, can_download)
                        VALUES (:contractId, :userId, 'RESPONSIBLE', TRUE)
                        ON CONFLICT (contract_id, user_id)
                        DO UPDATE SET member_role = 'RESPONSIBLE', can_download = TRUE
                        """)
                .param("contractId", id)
                .param("userId", request.responsibleUserId())
                .update();
        auditService.success(actor, "CONTRACT_UPDATE", "CONTRACT", id);
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static void validateDates(CreateContractRequest request) {
        if (request.effectiveDate() != null && request.expiryDate() != null
                && request.expiryDate().isBefore(request.effectiveDate())) {
            throw new BusinessException(
                    "CONTRACT_DATES_INVALID", "合同到期日不得早于生效日", HttpStatus.BAD_REQUEST
            );
        }
    }

    private List<UUID> validateMatterAccess(RequestActor actor, List<UUID> supplied) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(
                supplied == null ? List.of() : supplied
        );
        if (supplied != null && unique.size() != supplied.size()) {
            throw invalidMatter();
        }
        for (UUID matterId : unique) {
            if (!officeAccessService.canAccessBusiness(actor, "MATTER", matterId)) {
                throw invalidMatter();
            }
        }
        return List.copyOf(unique);
    }

    private static BusinessException invalidMatter() {
        return new BusinessException(
                "CONTRACT_MATTER_INVALID",
                "关联案件不存在、重复或无权访问",
                HttpStatus.BAD_REQUEST
        );
    }
}
