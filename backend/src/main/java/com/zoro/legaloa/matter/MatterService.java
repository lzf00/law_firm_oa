package com.zoro.legaloa.matter;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.matter.MatterController.CreateMatterRequest;
import com.zoro.legaloa.matter.MatterController.MatterDetail;
import com.zoro.legaloa.matter.MatterController.MatterPartyInput;
import com.zoro.legaloa.matter.MatterController.MatterPartyView;
import com.zoro.legaloa.matter.MatterController.MatterSummary;
import com.zoro.legaloa.matter.MatterController.MatterLifecycleRequest;
import com.zoro.legaloa.matter.MatterController.UpdateMatterRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatterService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;
    private final OfficeAccessService officeAccessService;
    private final AuthorizationService authorizationService;

    public MatterService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService,
            OfficeAccessService officeAccessService,
            AuthorizationService authorizationService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
        this.officeAccessService = officeAccessService;
        this.authorizationService = authorizationService;
    }

    @Transactional(readOnly = true)
    public List<MatterSummary> list(String status, String query) {
        RequestActor actor = actorProvider.current();
        boolean viewAll = authorizationService.hasPermission(actor, "MATTER_MANAGE");
        return jdbcClient.sql("""
                        SELECT m.id, m.matter_number, m.title, m.matter_type, m.status,
                               m.confidentiality_level, m.responsible_user_id,
                               u.display_name AS responsible_name, m.opened_at,
                               m.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en, m.country_code,
                               m.jurisdiction, m.working_language, m.billing_currency,
                               COUNT(mm.user_id) FILTER (WHERE mm.left_at IS NULL) AS member_count
                        FROM matters m
                        JOIN users u ON u.id = m.responsible_user_id
                        LEFT JOIN offices o ON o.id = m.office_id
                        LEFT JOIN matter_members mm ON mm.matter_id = m.id
                        WHERE m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND (:allStatuses OR m.status = :status)
                          AND (
                            :noQuery
                            OR m.matter_number ILIKE :query
                            OR m.title ILIKE :query
                            OR u.display_name ILIKE :query
                            OR COALESCE(m.case_number, '') ILIKE :query
                          )
                          AND (
                            :viewAll
                            OR
                            EXISTS (
                              SELECT 1 FROM matter_members visible_mm
                              WHERE visible_mm.matter_id = m.id
                                AND visible_mm.user_id = :userId
                                AND visible_mm.left_at IS NULL
                            )
                          )
                        GROUP BY m.id, u.display_name, o.id
                        ORDER BY m.updated_at DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .param("allStatuses", status == null || status.isBlank())
                .param("status", status == null ? "" : status)
                .param("noQuery", query == null || query.isBlank())
                .param("query", "%" + (query == null ? "" : query.trim()) + "%")
                .query(MatterService::mapSummary)
                .list();
    }

    @Transactional(readOnly = true)
    public MatterDetail get(UUID id) {
        RequestActor actor = actorProvider.current();
        boolean viewAll = authorizationService.hasPermission(actor, "MATTER_MANAGE");
        Map<String, Object> row = jdbcClient.sql("""
                        SELECT m.id, m.matter_number, m.title, m.matter_type, m.status,
                               m.confidentiality_level, m.responsible_user_id,
                               u.display_name AS responsible_name, m.opened_at,
                               m.court_name, m.case_number, m.description,
                               m.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en, m.country_code,
                               m.jurisdiction, m.working_language, m.billing_currency,
                               (SELECT COUNT(*) FROM matter_members mm
                                WHERE mm.matter_id = m.id AND mm.left_at IS NULL) AS member_count
                        FROM matters m
                        JOIN users u ON u.id = m.responsible_user_id
                        LEFT JOIN offices o ON o.id = m.office_id
                        WHERE m.id = :id AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND (
                            :viewAll
                            OR
                            EXISTS (
                              SELECT 1 FROM matter_members visible_mm
                              WHERE visible_mm.matter_id = m.id
                                AND visible_mm.user_id = :userId
                                AND visible_mm.left_at IS NULL
                            )
                          )
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("viewAll", viewAll)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "MATTER_NOT_FOUND", "案件不存在或无权访问", HttpStatus.NOT_FOUND
                ));

        MatterSummary summary = new MatterSummary(
                (UUID) row.get("id"),
                (String) row.get("matter_number"),
                (String) row.get("title"),
                (String) row.get("matter_type"),
                (String) row.get("status"),
                (String) row.get("confidentiality_level"),
                (UUID) row.get("responsible_user_id"),
                (String) row.get("responsible_name"),
                row.get("opened_at") == null ? null : ((java.sql.Date) row.get("opened_at")).toLocalDate(),
                ((Number) row.get("member_count")).intValue(),
                (UUID) row.get("office_id"),
                (String) row.get("office_name_zh"),
                (String) row.get("office_name_en"),
                (String) row.get("country_code"),
                (String) row.get("jurisdiction"),
                (String) row.get("working_language"),
                (String) row.get("billing_currency")
        );
        List<MatterPartyView> parties = jdbcClient.sql("""
                        SELECT p.id, p.display_name, mp.party_role, mp.side
                        FROM matter_parties mp
                        JOIN parties p ON p.id = mp.party_id
                        WHERE mp.matter_id = :matterId
                        ORDER BY mp.side, p.display_name
                        """)
                .param("matterId", id)
                .query((rs, rowNum) -> new MatterPartyView(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("party_role"),
                        rs.getString("side")
                ))
                .list();
        return new MatterDetail(
                summary,
                (String) row.get("court_name"),
                (String) row.get("case_number"),
                (String) row.get("description"),
                parties
        );
    }

    @Transactional
    public MatterDetail create(CreateMatterRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "MATTER_CREATE");
        UUID accessibleOfficeId = officeAccessService.resolveAccessibleOffice(
                actor, request.officeId()
        );
        boolean responsibleUserValid = jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM users
                            WHERE id = :userId AND organization_id = :organizationId
                              AND status = 'ACTIVE' AND deleted_at IS NULL
                        )
                        """)
                .param("userId", request.responsibleUserId())
                .param("organizationId", actor.organizationId())
                .query(Boolean.class)
                .single();
        if (!responsibleUserValid) {
            throw new BusinessException(
                    "RESPONSIBLE_USER_INVALID", "承办律师不属于当前组织或已停用", HttpStatus.BAD_REQUEST
            );
        }
        if (!officeAccessService.scope(actor).globalAccess()) {
            officeAccessService.requireUserInOffice(
                    actor, request.responsibleUserId(), accessibleOfficeId
            );
        }

        OfficeContext office = jdbcClient.sql("""
                        SELECT o.id, o.country_code, o.default_currency
                        FROM offices o
                        WHERE o.organization_id = :organizationId
                          AND o.status = 'ACTIVE'
                          AND o.id = :officeId
                        ORDER BY o.code
                        LIMIT 1
                        """)
                .param("organizationId", actor.organizationId())
                .param("officeId", accessibleOfficeId)
                .query((rs, rowNum) -> new OfficeContext(
                        rs.getObject("id", UUID.class),
                        rs.getString("country_code"),
                        rs.getString("default_currency")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "OFFICE_INVALID", "承办办公室不存在或已停用", HttpStatus.BAD_REQUEST
                ));

        UUID matterId = jdbcClient.sql("""
                        INSERT INTO matters
                            (organization_id, matter_number, title, matter_type, status,
                             responsible_user_id, opened_at, court_name, case_number,
                             description, office_id, country_code, jurisdiction,
                             working_language, billing_currency, created_by)
                        SELECT :organizationId, :matterNumber, :title, :matterType,
                               'CONFLICT_REVIEW', u.id, :openedAt, :courtName, :caseNumber,
                               :description, :officeId, :countryCode, :jurisdiction,
                               :workingLanguage, :billingCurrency, :createdBy
                        FROM users u
                        WHERE u.id = :responsibleUserId
                          AND u.organization_id = :organizationId
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("matterNumber", request.matterNumber().trim())
                .param("title", request.title().trim())
                .param("matterType", request.matterType().trim())
                .param("responsibleUserId", request.responsibleUserId())
                .param("openedAt", request.openedAt())
                .param("courtName", request.courtName())
                .param("caseNumber", request.caseNumber())
                .param("description", request.description())
                .param("officeId", office.id())
                .param("countryCode", request.countryCode() == null
                        ? office.countryCode() : request.countryCode())
                .param("jurisdiction", request.jurisdiction())
                .param("workingLanguage", request.workingLanguage() == null
                        ? "zh-CN" : request.workingLanguage())
                .param("billingCurrency", request.billingCurrency() == null
                        ? office.defaultCurrency() : request.billingCurrency())
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "RESPONSIBLE_USER_INVALID", "承办律师不属于当前组织或已停用", HttpStatus.BAD_REQUEST
                ));

        jdbcClient.sql("""
                        INSERT INTO matter_members (matter_id, user_id, member_role, can_download)
                        VALUES (:matterId, :userId, 'RESPONSIBLE', TRUE)
                        """)
                .param("matterId", matterId)
                .param("userId", request.responsibleUserId())
                .update();
        if (!actor.userId().equals(request.responsibleUserId())) {
            jdbcClient.sql("""
                            INSERT INTO matter_members
                                (matter_id, user_id, member_role, can_download)
                            VALUES (:matterId, :userId, 'COUNSEL', TRUE)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("matterId", matterId)
                    .param("userId", actor.userId())
                    .update();
        }

        List<UUID> clientIds = request.clientIds() == null
                ? List.of()
                : request.clientIds().stream().distinct().toList();
        for (int index = 0; index < clientIds.size(); index++) {
            UUID clientId = clientIds.get(index);
            int inserted = jdbcClient.sql("""
                            INSERT INTO matter_clients (matter_id, client_id, is_primary)
                            SELECT :matterId, c.id, :isPrimary FROM clients c
                            JOIN parties p ON p.id = c.party_id
                            WHERE c.id = :clientId AND p.organization_id = :organizationId
                              AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                            """)
                    .param("matterId", matterId)
                    .param("clientId", clientId)
                    .param("isPrimary", index == 0)
                    .param("organizationId", actor.organizationId())
                    .update();
            if (inserted == 0) {
                throw new BusinessException(
                        "CLIENT_INVALID", "案件客户不存在或不属于当前组织", HttpStatus.BAD_REQUEST
                );
            }
        }

        for (MatterPartyInput party : request.parties() == null ? List.<MatterPartyInput>of() : request.parties()) {
            int inserted = jdbcClient.sql("""
                            INSERT INTO matter_parties (matter_id, party_id, party_role, side)
                            SELECT :matterId, p.id, :partyRole, :side
                            FROM parties p
                            WHERE p.id = :partyId AND p.organization_id = :organizationId
                              AND p.deleted_at IS NULL
                            ON CONFLICT DO NOTHING
                            """)
                    .param("matterId", matterId)
                    .param("partyId", party.partyId())
                    .param("partyRole", party.partyRole().trim())
                    .param("side", party.side().trim())
                    .param("organizationId", actor.organizationId())
                    .update();
            if (inserted == 0) {
                throw new BusinessException(
                        "PARTY_INVALID", "案件当事人不存在或不属于当前组织", HttpStatus.BAD_REQUEST
                );
            }
        }

        auditService.success(actor, "MATTER_CREATE", "MATTER", matterId);
        return get(matterId);
    }

    @Transactional
    public MatterDetail update(UUID id, UpdateMatterRequest request) {
        RequestActor actor = actorProvider.current();
        requireWriteAccess(actor, id);
        UUID officeId = officeAccessService.resolveAccessibleOffice(actor, request.officeId());
        OfficeContext office = officeContext(actor, officeId);
        int updated = jdbcClient.sql("""
                        UPDATE matters m
                        SET title = :title,
                            matter_type = :matterType,
                            responsible_user_id = :responsibleUserId,
                            opened_at = :openedAt,
                            court_name = :courtName,
                            case_number = :caseNumber,
                            description = :description,
                            office_id = :officeId,
                            country_code = :countryCode,
                            jurisdiction = :jurisdiction,
                            working_language = :workingLanguage,
                            billing_currency = :billingCurrency,
                            updated_at = now()
                        WHERE m.id = :id AND m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND EXISTS (
                            SELECT 1 FROM users u
                            WHERE u.id = :responsibleUserId
                              AND u.organization_id = :organizationId
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                          )
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("title", request.title().trim())
                .param("matterType", request.matterType().trim())
                .param("responsibleUserId", request.responsibleUserId())
                .param("openedAt", request.openedAt())
                .param("courtName", trimToNull(request.courtName()))
                .param("caseNumber", trimToNull(request.caseNumber()))
                .param("description", trimToNull(request.description()))
                .param("officeId", office.id())
                .param("countryCode", request.countryCode() == null
                        ? office.countryCode() : request.countryCode())
                .param("jurisdiction", trimToNull(request.jurisdiction()))
                .param("workingLanguage", request.workingLanguage() == null
                        ? "zh-CN" : request.workingLanguage())
                .param("billingCurrency", request.billingCurrency() == null
                        ? office.defaultCurrency() : request.billingCurrency())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "MATTER_CONTEXT_INVALID", "案件或承办律师无效", HttpStatus.BAD_REQUEST
            );
        }
        jdbcClient.sql("""
                        UPDATE matter_members
                        SET member_role = CASE
                            WHEN member_role = 'RESPONSIBLE' THEN 'COUNSEL' ELSE member_role END
                        WHERE matter_id = :matterId AND member_role = 'RESPONSIBLE'
                        """)
                .param("matterId", id)
                .update();
        jdbcClient.sql("""
                        INSERT INTO matter_members
                            (matter_id, user_id, member_role, can_download)
                        VALUES (:matterId, :userId, 'RESPONSIBLE', TRUE)
                        ON CONFLICT (matter_id, user_id)
                        DO UPDATE SET member_role = 'RESPONSIBLE',
                                      can_download = TRUE, left_at = NULL
                        """)
                .param("matterId", id)
                .param("userId", request.responsibleUserId())
                .update();
        addMatterEvent(id, actor, "MATTER_UPDATED", "案件资料已更新", request.title());
        auditService.success(actor, "MATTER_UPDATE", "MATTER", id);
        return get(id);
    }

    @Transactional
    public MatterDetail transition(UUID id, MatterLifecycleRequest request) {
        RequestActor actor = actorProvider.current();
        requireWriteAccess(actor, id);
        String current = jdbcClient.sql("""
                        SELECT status FROM matters
                        WHERE id = :id AND organization_id = :organizationId
                          AND deleted_at IS NULL
                        FOR UPDATE
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "MATTER_NOT_FOUND", "案件不存在或无权访问", HttpStatus.NOT_FOUND
                ));
        String target = request.targetStatus();
        if (!MatterLifecyclePolicy.canTransition(current, target)) {
            throw new BusinessException(
                    "MATTER_TRANSITION_INVALID",
                    "案件不能从 " + current + " 转为 " + target,
                    HttpStatus.CONFLICT
            );
        }
        jdbcClient.sql("""
                        UPDATE matters
                        SET status = :target,
                            closed_at = CASE
                              WHEN :target IN ('CLOSED', 'ARCHIVED') THEN COALESCE(closed_at, CURRENT_DATE)
                              WHEN :target = 'ACTIVE' THEN NULL
                              ELSE closed_at
                            END,
                            updated_at = now()
                        WHERE id = :id AND organization_id = :organizationId
                        """)
                .param("target", target)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        jdbcClient.sql("""
                        INSERT INTO business_state_transitions
                            (business_type, business_id, from_state, to_state, changed_by, reason)
                        VALUES ('MATTER', :matterId, :fromState, :toState, :changedBy, :reason)
                        """)
                .param("matterId", id)
                .param("fromState", current)
                .param("toState", target)
                .param("changedBy", actor.userId())
                .param("reason", request.reason().trim())
                .update();
        addMatterEvent(
                id, actor, "STATUS_CHANGED", "案件状态：" + current + " → " + target,
                request.reason().trim()
        );
        auditService.record(
                actor, "MATTER_STATUS_CHANGE", "MATTER", id, "SUCCESS",
                request.reason().trim(), Map.of("from", current, "to", target)
        );
        return get(id);
    }

    private void requireWriteAccess(RequestActor actor, UUID matterId) {
        if (authorizationService.hasPermission(actor, "MATTER_MANAGE")) {
            return;
        }
        Boolean allowed = jdbcClient.sql("""
                        SELECT EXISTS (
                          SELECT 1 FROM matters m
                          WHERE m.id = :matterId
                            AND m.organization_id = :organizationId
                            AND m.deleted_at IS NULL
                            AND (
                              EXISTS (
                                SELECT 1 FROM matter_members mm
                                WHERE mm.matter_id = m.id
                                  AND mm.user_id = :userId AND mm.left_at IS NULL
                                  AND mm.member_role IN ('RESPONSIBLE', 'LEAD')
                              )
                            )
                        )
                        """)
                .param("matterId", matterId)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(allowed)) {
            throw new BusinessException(
                    "MATTER_WRITE_DENIED", "当前用户无权编辑该案件", HttpStatus.FORBIDDEN
            );
        }
    }

    private OfficeContext officeContext(RequestActor actor, UUID officeId) {
        return jdbcClient.sql("""
                        SELECT id, country_code, default_currency
                        FROM offices
                        WHERE id = :officeId AND organization_id = :organizationId
                          AND status = 'ACTIVE'
                        """)
                .param("officeId", officeId)
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new OfficeContext(
                        rs.getObject("id", UUID.class),
                        rs.getString("country_code"),
                        rs.getString("default_currency")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "OFFICE_INVALID", "承办办公室不存在或已停用", HttpStatus.BAD_REQUEST
                ));
    }

    private void addMatterEvent(
            UUID matterId,
            RequestActor actor,
            String eventType,
            String title,
            String description
    ) {
        jdbcClient.sql("""
                        INSERT INTO matter_events
                            (matter_id, event_type, title, description, event_at, created_by)
                        VALUES (:matterId, :eventType, :title, :description, now(), :createdBy)
                        """)
                .param("matterId", matterId)
                .param("eventType", eventType)
                .param("title", title)
                .param("description", description)
                .param("createdBy", actor.userId())
                .update();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static MatterSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Date openedAt = rs.getDate("opened_at");
        return new MatterSummary(
                rs.getObject("id", UUID.class),
                rs.getString("matter_number"),
                rs.getString("title"),
                rs.getString("matter_type"),
                rs.getString("status"),
                rs.getString("confidentiality_level"),
                rs.getObject("responsible_user_id", UUID.class),
                rs.getString("responsible_name"),
                openedAt == null ? null : openedAt.toLocalDate(),
                rs.getInt("member_count"),
                rs.getObject("office_id", UUID.class),
                rs.getString("office_name_zh"),
                rs.getString("office_name_en"),
                rs.getString("country_code"),
                rs.getString("jurisdiction"),
                rs.getString("working_language"),
                rs.getString("billing_currency")
        );
    }

    private record OfficeContext(UUID id, String countryCode, String defaultCurrency) {}
}
