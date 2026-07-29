package com.zoro.legaloa.party;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.party.ConflictCheckController.ConflictActionView;
import com.zoro.legaloa.party.ConflictCheckController.ConflictCheckView;
import com.zoro.legaloa.party.ConflictCheckController.ConflictDecisionRequest;
import com.zoro.legaloa.party.ConflictCheckController.ConflictHit;
import com.zoro.legaloa.party.ConflictCheckController.ConflictPartyRequest;
import com.zoro.legaloa.party.ConflictCheckController.ConflictPreview;
import com.zoro.legaloa.party.ConflictCheckController.ConflictPreviewRequest;
import com.zoro.legaloa.party.ConflictCheckController.CreateConflictCheckRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConflictCheckService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final OfficeAccessService officeAccessService;
    private final AuditService auditService;

    public ConflictCheckService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            OfficeAccessService officeAccessService,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.officeAccessService = officeAccessService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ConflictPreview preview(ConflictPreviewRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONFLICT_CHECK_CREATE");
        validatePartyIds(actor, request.partyIds());
        List<ConflictHit> rawHits = liveHits(actor, request.partyIds(), false);
        String riskLevel = ConflictCheckPolicy.riskLevel(
                rawHits.stream().map(ConflictHit::matterStatus).toList()
        );
        return new ConflictPreview(riskLevel, rawHits.size(), redactHits(actor, rawHits));
    }

    @Transactional(readOnly = true)
    public List<ConflictCheckView> list() {
        RequestActor actor = actorProvider.current();
        boolean viewAll = authorizationService.hasPermission(actor, "CONFLICT_CHECK_VIEW_ALL");
        return jdbcClient.sql("""
                        SELECT cc.id, cc.request_number, cc.office_id,
                               o.name_zh AS office_name_zh, o.name_en AS office_name_en,
                               cc.proposed_matter_title, cc.status, cc.risk_level, cc.decision,
                               cc.requested_by, requester.display_name AS requested_by_name,
                               cc.reviewed_by, reviewer.display_name AS reviewed_by_name,
                               cc.decision_rationale, cc.mitigation_plan,
                               cc.created_at, cc.submitted_at, cc.reviewed_at
                        FROM conflict_checks cc
                        JOIN users requester ON requester.id = cc.requested_by
                        LEFT JOIN users reviewer ON reviewer.id = cc.reviewed_by
                        LEFT JOIN offices o ON o.id = cc.office_id
                        WHERE cc.organization_id = :organizationId
                          AND (:viewAll OR cc.requested_by = :userId)
                        ORDER BY cc.created_at DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("viewAll", viewAll)
                .param("userId", actor.userId())
                .query(ConflictCheckService::mapBase)
                .list()
                .stream()
                .map(base -> toView(actor, base, viewAll))
                .toList();
    }

    @Transactional
    public ConflictCheckView create(CreateConflictCheckRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONFLICT_CHECK_CREATE");
        UUID officeId = officeAccessService.resolveAccessibleOffice(actor, request.officeId());
        List<ConflictPartyRequest> parties = normalizeParties(request.parties());
        validatePartyIds(actor, parties.stream().map(ConflictPartyRequest::partyId).toList());
        String requestNumber = "COI-" + Instant.now().toString()
                .replaceAll("[^0-9]", "").substring(0, 14)
                + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        List<ConflictHit> hits = liveHits(
                actor, parties.stream().map(ConflictPartyRequest::partyId).toList(), false
        );
        String riskLevel = ConflictCheckPolicy.riskLevel(
                hits.stream().map(ConflictHit::matterStatus).toList()
        );
        UUID checkId = jdbcClient.sql("""
                        INSERT INTO conflict_checks
                            (organization_id, request_number, proposed_client_id,
                             proposed_matter_title, status, risk_level, requested_by, office_id)
                        VALUES
                            (:organizationId, :requestNumber, :proposedClientId,
                             :matterTitle, 'DRAFT', :riskLevel, :requestedBy, :officeId)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("requestNumber", requestNumber)
                .param("proposedClientId", parties.getFirst().partyId())
                .param("matterTitle", request.matterTitle().trim())
                .param("riskLevel", riskLevel)
                .param("requestedBy", actor.userId())
                .param("officeId", officeId)
                .query(UUID.class)
                .single();
        for (ConflictPartyRequest party : parties) {
            jdbcClient.sql("""
                            INSERT INTO conflict_check_parties
                                (conflict_check_id, party_id, proposed_role)
                            VALUES (:checkId, :partyId, :proposedRole)
                            """)
                    .param("checkId", checkId)
                    .param("partyId", party.partyId())
                    .param("proposedRole", party.proposedRole())
                    .update();
        }
        snapshotHits(checkId, hits);
        recordAction(checkId, actor, "CREATED", riskLevel, null, null, null);
        auditService.record(
                actor, "CONFLICT_CHECK_CREATE", "CONFLICT_CHECK", checkId,
                "SUCCESS", null, Map.of(
                        "riskLevel", riskLevel,
                        "hitCount", hits.size(),
                        "partyCount", parties.size()
                )
        );
        return findVisible(actor, checkId);
    }

    @Transactional
    public ConflictCheckView submit(UUID id) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONFLICT_CHECK_CREATE");
        ConflictBase current = findBase(actor, id, false);
        if (!actor.userId().equals(current.requestedBy())) {
            throw new BusinessException(
                    "CONFLICT_CHECK_ACCESS_DENIED",
                    "只有申请人可以提交该利益冲突检查",
                    HttpStatus.FORBIDDEN
            );
        }
        if (!ConflictCheckPolicy.canSubmit(current.status())) {
            throw new BusinessException(
                    "CONFLICT_CHECK_SUBMIT_INVALID",
                    "只有草稿状态可以提交复核",
                    HttpStatus.CONFLICT
            );
        }
        int updated = jdbcClient.sql("""
                        UPDATE conflict_checks
                        SET status = 'SUBMITTED', submitted_at = now(),
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND requested_by = :userId AND status = 'DRAFT'
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .update();
        if (updated != 1) {
            throw stateChanged();
        }
        recordAction(id, actor, "SUBMITTED", current.riskLevel(), null, null, null);
        auditService.success(actor, "CONFLICT_CHECK_SUBMIT", "CONFLICT_CHECK", id);
        return findVisible(actor, id);
    }

    @Transactional
    public ConflictCheckView decide(UUID id, ConflictDecisionRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "CONFLICT_CHECK_REVIEW");
        ConflictBase current = findBase(actor, id, true);
        if (actor.userId().equals(current.requestedBy())) {
            throw new BusinessException(
                    "CONFLICT_SELF_REVIEW_FORBIDDEN",
                    "利益冲突检查申请人不能复核自己的申请",
                    HttpStatus.FORBIDDEN
            );
        }
        if (!ConflictCheckPolicy.canDecide(current.status())) {
            throw new BusinessException(
                    "CONFLICT_CHECK_DECISION_INVALID",
                    "只有已提交或复核中的检查可以作出结论",
                    HttpStatus.CONFLICT
            );
        }
        String decision;
        String riskLevel;
        try {
            decision = ConflictCheckPolicy.normalizeDecision(request.decision());
            riskLevel = ConflictCheckPolicy.normalizeRiskLevel(request.riskLevel());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    "CONFLICT_CHECK_DECISION_INVALID",
                    "利益冲突结论或风险等级无效",
                    HttpStatus.BAD_REQUEST
            );
        }
        String mitigation = trimToNull(request.mitigationPlan());
        if ("WAIVER_REQUIRED".equals(decision) && mitigation == null) {
            throw new BusinessException(
                    "CONFLICT_MITIGATION_REQUIRED",
                    "需要豁免时必须填写隔离和风险缓释措施",
                    HttpStatus.BAD_REQUEST
            );
        }
        String terminalStatus = ConflictCheckPolicy.terminalStatus(decision);
        int updated = jdbcClient.sql("""
                        UPDATE conflict_checks
                        SET status = :status, risk_level = :riskLevel, decision = :decision,
                            reviewed_by = :reviewedBy, review_notes = :rationale,
                            decision_rationale = :rationale, mitigation_plan = :mitigation,
                            reviewed_at = now(), updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND status IN ('SUBMITTED', 'REVIEWING')
                        """)
                .param("status", terminalStatus)
                .param("riskLevel", riskLevel)
                .param("decision", decision)
                .param("reviewedBy", actor.userId())
                .param("rationale", request.rationale().trim())
                .param("mitigation", mitigation)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        if (updated != 1) {
            throw stateChanged();
        }
        recordAction(
                id, actor, "DECIDED", riskLevel, decision,
                request.rationale().trim(), mitigation
        );
        auditService.record(
                actor, "CONFLICT_CHECK_DECIDE", "CONFLICT_CHECK", id,
                "SUCCESS", null, Map.of(
                        "decision", decision,
                        "riskLevel", riskLevel,
                        "terminalStatus", terminalStatus
                )
        );
        return findVisible(actor, id);
    }

    private ConflictCheckView findVisible(RequestActor actor, UUID id) {
        boolean viewAll = authorizationService.hasPermission(actor, "CONFLICT_CHECK_VIEW_ALL");
        return toView(actor, findBase(actor, id, viewAll), viewAll);
    }

    private ConflictBase findBase(RequestActor actor, UUID id, boolean requireViewAll) {
        boolean viewAll = authorizationService.hasPermission(actor, "CONFLICT_CHECK_VIEW_ALL");
        if (requireViewAll && !viewAll) {
            throw new BusinessException(
                    "CONFLICT_CHECK_ACCESS_DENIED",
                    "你没有查看全部利益冲突检查的权限",
                    HttpStatus.FORBIDDEN
            );
        }
        return jdbcClient.sql("""
                        SELECT cc.id, cc.request_number, cc.office_id,
                               o.name_zh AS office_name_zh, o.name_en AS office_name_en,
                               cc.proposed_matter_title, cc.status, cc.risk_level, cc.decision,
                               cc.requested_by, requester.display_name AS requested_by_name,
                               cc.reviewed_by, reviewer.display_name AS reviewed_by_name,
                               cc.decision_rationale, cc.mitigation_plan,
                               cc.created_at, cc.submitted_at, cc.reviewed_at
                        FROM conflict_checks cc
                        JOIN users requester ON requester.id = cc.requested_by
                        LEFT JOIN users reviewer ON reviewer.id = cc.reviewed_by
                        LEFT JOIN offices o ON o.id = cc.office_id
                        WHERE cc.id = :id AND cc.organization_id = :organizationId
                          AND (:viewAll OR cc.requested_by = :userId)
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .param("viewAll", viewAll)
                .param("userId", actor.userId())
                .query(ConflictCheckService::mapBase)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        "CONFLICT_CHECK_NOT_FOUND",
                        "利益冲突检查不存在或你无权查看",
                        HttpStatus.NOT_FOUND
                ));
    }

    private ConflictCheckView toView(
            RequestActor actor,
            ConflictBase base,
            boolean fullDetails
    ) {
        List<ConflictHit> hits = snapshotHits(base.id(), fullDetails);
        return new ConflictCheckView(
                base.id(),
                base.requestNumber(),
                base.officeId(),
                base.officeNameZh(),
                base.officeNameEn(),
                base.matterTitle(),
                base.status(),
                base.riskLevel(),
                base.decision(),
                base.requestedBy(),
                base.requestedByName(),
                base.reviewedBy(),
                base.reviewedByName(),
                base.decisionRationale(),
                base.mitigationPlan(),
                base.createdAt(),
                base.submittedAt(),
                base.reviewedAt(),
                !fullDetails && !hits.isEmpty(),
                parties(base.id()),
                hits,
                actions(base.id())
        );
    }

    private List<ConflictPartyRequest> parties(UUID checkId) {
        return jdbcClient.sql("""
                        SELECT party_id, proposed_role
                        FROM conflict_check_parties
                        WHERE conflict_check_id = :checkId
                        ORDER BY proposed_role, party_id
                        """)
                .param("checkId", checkId)
                .query((rs, rowNum) -> new ConflictPartyRequest(
                        rs.getObject("party_id", UUID.class),
                        rs.getString("proposed_role")
                ))
                .list();
    }

    private List<ConflictActionView> actions(UUID checkId) {
        return jdbcClient.sql("""
                        SELECT action.id, action.action, action.actor_user_id,
                               actor.display_name AS actor_name, action.risk_level,
                               action.decision, action.rationale, action.mitigation_plan,
                               action.occurred_at
                        FROM conflict_check_actions action
                        JOIN users actor ON actor.id = action.actor_user_id
                        WHERE action.conflict_check_id = :checkId
                        ORDER BY action.occurred_at, action.id
                        """)
                .param("checkId", checkId)
                .query((rs, rowNum) -> new ConflictActionView(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("risk_level"),
                        rs.getString("decision"),
                        rs.getString("rationale"),
                        rs.getString("mitigation_plan"),
                        rs.getTimestamp("occurred_at").toInstant()
                ))
                .list();
    }

    private List<ConflictHit> snapshotHits(UUID checkId, boolean fullDetails) {
        return jdbcClient.sql("""
                        SELECT party_id, party_name, matter_id, matter_number, matter_title,
                               party_role, side, matter_status
                        FROM conflict_check_hits
                        WHERE conflict_check_id = :checkId
                        ORDER BY captured_at, matter_number, party_name
                        """)
                .param("checkId", checkId)
                .query((rs, rowNum) -> new ConflictHit(
                        rs.getObject("party_id", UUID.class),
                        rs.getString("party_name"),
                        fullDetails ? rs.getObject("matter_id", UUID.class) : null,
                        fullDetails ? rs.getString("matter_number") : null,
                        fullDetails ? rs.getString("matter_title") : null,
                        fullDetails ? rs.getString("party_role") : null,
                        fullDetails ? rs.getString("side") : null,
                        fullDetails ? rs.getString("matter_status") : null,
                        !fullDetails
                ))
                .list();
    }

    private List<ConflictHit> liveHits(
            RequestActor actor,
            List<UUID> partyIds,
            boolean restricted
    ) {
        return jdbcClient.sql("""
                        SELECT p.id AS party_id, p.display_name AS party_name,
                               m.id AS matter_id, m.matter_number, m.title AS matter_title,
                               mp.party_role, mp.side, m.status AS matter_status
                        FROM matter_parties mp
                        JOIN parties p ON p.id = mp.party_id
                        JOIN matters m ON m.id = mp.matter_id
                        WHERE m.organization_id = :organizationId
                          AND m.deleted_at IS NULL
                          AND mp.party_id IN (:partyIds)
                        ORDER BY
                          CASE WHEN m.status IN ('ACTIVE', 'INTAKE', 'CONFLICT_REVIEW')
                               THEN 0 ELSE 1 END,
                          m.created_at DESC
                        """)
                .param("organizationId", actor.organizationId())
                .param("partyIds", partyIds)
                .query((rs, rowNum) -> new ConflictHit(
                        rs.getObject("party_id", UUID.class),
                        rs.getString("party_name"),
                        rs.getObject("matter_id", UUID.class),
                        rs.getString("matter_number"),
                        rs.getString("matter_title"),
                        rs.getString("party_role"),
                        rs.getString("side"),
                        rs.getString("matter_status"),
                        restricted
                ))
                .list();
    }

    private List<ConflictHit> redactHits(RequestActor actor, List<ConflictHit> hits) {
        if (authorizationService.hasPermission(actor, "CONFLICT_CHECK_VIEW_ALL")) {
            return hits;
        }
        return hits.stream()
                .map(hit -> new ConflictHit(
                        hit.partyId(), hit.partyName(), null, null, null,
                        null, null, null, true
                ))
                .toList();
    }

    private void snapshotHits(UUID checkId, List<ConflictHit> hits) {
        for (ConflictHit hit : hits) {
            jdbcClient.sql("""
                            INSERT INTO conflict_check_hits
                                (conflict_check_id, party_id, party_name, matter_id,
                                 matter_number, matter_title, party_role, side, matter_status)
                            VALUES
                                (:checkId, :partyId, :partyName, :matterId,
                                 :matterNumber, :matterTitle, :partyRole, :side, :matterStatus)
                            """)
                    .param("checkId", checkId)
                    .param("partyId", hit.partyId())
                    .param("partyName", hit.partyName())
                    .param("matterId", hit.matterId())
                    .param("matterNumber", hit.matterNumber())
                    .param("matterTitle", hit.matterTitle())
                    .param("partyRole", hit.partyRole())
                    .param("side", hit.side())
                    .param("matterStatus", hit.matterStatus())
                    .update();
        }
    }

    private void recordAction(
            UUID checkId,
            RequestActor actor,
            String action,
            String riskLevel,
            String decision,
            String rationale,
            String mitigationPlan
    ) {
        jdbcClient.sql("""
                        INSERT INTO conflict_check_actions
                            (conflict_check_id, action, actor_user_id, risk_level,
                             decision, rationale, mitigation_plan)
                        VALUES
                            (:checkId, :action, :actorId, :riskLevel,
                             :decision, :rationale, :mitigationPlan)
                        """)
                .param("checkId", checkId)
                .param("action", action)
                .param("actorId", actor.userId())
                .param("riskLevel", riskLevel)
                .param("decision", decision)
                .param("rationale", rationale)
                .param("mitigationPlan", mitigationPlan)
                .update();
    }

    private void validatePartyIds(RequestActor actor, List<UUID> partyIds) {
        long distinct = partyIds.stream().distinct().count();
        if (distinct != partyIds.size()) {
            throw invalidParties();
        }
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM parties
                        WHERE organization_id = :organizationId
                          AND id IN (:partyIds) AND deleted_at IS NULL
                        """)
                .param("organizationId", actor.organizationId())
                .param("partyIds", partyIds)
                .query(Integer.class)
                .single();
        if (count != partyIds.size()) {
            throw invalidParties();
        }
    }

    private static List<ConflictPartyRequest> normalizeParties(
            List<ConflictPartyRequest> supplied
    ) {
        LinkedHashMap<UUID, ConflictPartyRequest> unique = new LinkedHashMap<>();
        for (ConflictPartyRequest party : supplied) {
            unique.put(party.partyId(), new ConflictPartyRequest(
                    party.partyId(), party.proposedRole().trim()
            ));
        }
        if (unique.size() != supplied.size()) {
            throw invalidParties();
        }
        return new ArrayList<>(unique.values());
    }

    private static BusinessException invalidParties() {
        return new BusinessException(
                "CONFLICT_PARTIES_INVALID",
                "冲突检查主体不存在、重复或不属于当前组织",
                HttpStatus.BAD_REQUEST
        );
    }

    private static BusinessException stateChanged() {
        return new BusinessException(
                "CONFLICT_CHECK_STATE_CHANGED",
                "利益冲突检查状态已变化，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ConflictBase mapBase(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConflictBase(
                rs.getObject("id", UUID.class),
                rs.getString("request_number"),
                rs.getObject("office_id", UUID.class),
                rs.getString("office_name_zh"),
                rs.getString("office_name_en"),
                rs.getString("proposed_matter_title"),
                rs.getString("status"),
                rs.getString("risk_level"),
                rs.getString("decision"),
                rs.getObject("requested_by", UUID.class),
                rs.getString("requested_by_name"),
                rs.getObject("reviewed_by", UUID.class),
                rs.getString("reviewed_by_name"),
                rs.getString("decision_rationale"),
                rs.getString("mitigation_plan"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("reviewed_at"))
        );
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ConflictBase(
            UUID id,
            String requestNumber,
            UUID officeId,
            String officeNameZh,
            String officeNameEn,
            String matterTitle,
            String status,
            String riskLevel,
            String decision,
            UUID requestedBy,
            String requestedByName,
            UUID reviewedBy,
            String reviewedByName,
            String decisionRationale,
            String mitigationPlan,
            Instant createdAt,
            Instant submittedAt,
            Instant reviewedAt
    ) {}
}
