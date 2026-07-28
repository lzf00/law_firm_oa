package com.zoro.legaloa.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offices")
public class OfficeController {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final OfficeAccessService officeAccessService;
    private final OfficeMembershipService membershipService;

    public OfficeController(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            OfficeAccessService officeAccessService,
            OfficeMembershipService membershipService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.officeAccessService = officeAccessService;
        this.membershipService = membershipService;
    }

    @GetMapping
    List<OfficeView> list() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT o.id, o.code, o.name_zh, o.name_en, o.country_code,
                               o.city_zh, o.city_en, o.timezone, o.default_currency,
                               o.address_zh, o.address_en, o.status,
                               (SELECT COUNT(*) FROM user_offices uo
                                WHERE uo.office_id = o.id
                                  AND (uo.valid_until IS NULL OR uo.valid_until > now()))
                                  AS member_count
                        FROM offices o
                        WHERE o.organization_id = :organizationId
                          AND o.status = 'ACTIVE'
                          AND (:globalAccess OR o.id IN (:officeIds))
                        ORDER BY CASE WHEN o.code = 'DXB' THEN 0 ELSE 1 END, o.code
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new OfficeView(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name_zh"),
                        rs.getString("name_en"),
                        rs.getString("country_code"),
                        rs.getString("city_zh"),
                        rs.getString("city_en"),
                        rs.getString("timezone"),
                        rs.getString("default_currency"),
                        rs.getString("address_zh"),
                        rs.getString("address_en"),
                        rs.getString("status"),
                        rs.getLong("member_count")
                ))
                .list();
    }

    @GetMapping("/{officeId}/members")
    List<OfficeMemberView> members(@PathVariable UUID officeId) {
        return membershipService.list(officeId);
    }

    @PutMapping("/{officeId}/members/{userId}")
    OfficeMemberView upsertMember(
            @PathVariable UUID officeId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpsertOfficeMemberRequest request
    ) {
        return membershipService.upsert(officeId, userId, request);
    }

    @DeleteMapping("/{officeId}/members/{userId}")
    void revokeMember(@PathVariable UUID officeId, @PathVariable UUID userId) {
        membershipService.revoke(officeId, userId);
    }

    public record OfficeView(
            UUID id,
            String code,
            String nameZh,
            String nameEn,
            String countryCode,
            String cityZh,
            String cityEn,
            String timezone,
            String defaultCurrency,
            String addressZh,
            String addressEn,
            String status,
            long memberCount
    ) {}

    public record UpsertOfficeMemberRequest(
            @NotBlank String accessLevel,
            boolean primary,
            Instant validUntil
    ) {}

    public record OfficeMemberView(
            UUID userId,
            String username,
            String displayName,
            String email,
            String accessLevel,
            boolean primary,
            Instant validUntil,
            Instant createdAt,
            Instant updatedAt,
            String assignedByName
    ) {}
}
