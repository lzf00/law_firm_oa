package com.zoro.legaloa.party;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.party.PartyController.CreatePartyRequest;
import com.zoro.legaloa.party.PartyController.PartySummary;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartyService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuditService auditService;

    public PartyService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuditService auditService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PartySummary> search(String query) {
        RequestActor actor = actorProvider.current();
        String normalized = "%" + normalize(query) + "%";
        return jdbcClient.sql("""
                        SELECT p.id, p.party_type, p.display_name, p.unified_social_credit_code,
                               p.risk_level,
                               COALESCE(array_agg(pa.alias_name) FILTER (WHERE pa.id IS NOT NULL), '{}') AS aliases
                        FROM parties p
                        LEFT JOIN party_aliases pa ON pa.party_id = p.id
                        WHERE p.organization_id = :organizationId
                          AND p.deleted_at IS NULL
                          AND (:emptyQuery OR p.normalized_name ILIKE :query
                               OR EXISTS (
                                   SELECT 1 FROM party_aliases px
                                   WHERE px.party_id = p.id AND px.normalized_alias ILIKE :query
                               ))
                        GROUP BY p.id
                        ORDER BY p.display_name
                        LIMIT 100
                        """)
                .param("organizationId", actor.organizationId())
                .param("emptyQuery", query == null || query.isBlank())
                .param("query", normalized)
                .query((rs, rowNum) -> new PartySummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("party_type"),
                        rs.getString("display_name"),
                        rs.getString("unified_social_credit_code"),
                        rs.getString("risk_level"),
                        List.of((String[]) rs.getArray("aliases").getArray())
                ))
                .list();
    }

    @Transactional
    public PartySummary create(CreatePartyRequest request) {
        RequestActor actor = actorProvider.current();
        UUID id = jdbcClient.sql("""
                        INSERT INTO parties
                            (organization_id, party_type, normalized_name, display_name,
                             unified_social_credit_code, notes, created_by)
                        VALUES
                            (:organizationId, :partyType, :normalizedName, :displayName,
                             :creditCode, :notes, :createdBy)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("partyType", request.partyType().name())
                .param("normalizedName", normalize(request.displayName()))
                .param("displayName", request.displayName().trim())
                .param("creditCode", blankToNull(request.unifiedSocialCreditCode()))
                .param("notes", blankToNull(request.notes()))
                .param("createdBy", actor.userId())
                .query(UUID.class)
                .single();

        List<String> aliases = request.aliases() == null ? List.of() : request.aliases();
        aliases.stream()
                .map(String::trim)
                .filter(alias -> !alias.isBlank())
                .distinct()
                .forEach(alias -> jdbcClient.sql("""
                                INSERT INTO party_aliases (party_id, alias_name, normalized_alias)
                                VALUES (:partyId, :alias, :normalizedAlias)
                                ON CONFLICT (party_id, normalized_alias) DO NOTHING
                                """)
                        .param("partyId", id)
                        .param("alias", alias)
                        .param("normalizedAlias", normalize(alias))
                        .update());

        auditService.success(actor, "PARTY_CREATE", "PARTY", id);
        return new PartySummary(
                id,
                request.partyType().name(),
                request.displayName().trim(),
                blankToNull(request.unifiedSocialCreditCode()),
                "NORMAL",
                aliases
        );
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s·•._\\-（）()]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

