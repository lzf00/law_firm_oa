package com.zoro.legaloa.office;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.office.AnnouncementController.AnnouncementView;
import com.zoro.legaloa.office.AnnouncementController.CreateAnnouncementRequest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementService {
    private static final Set<String> AUDIENCES = Set.of("ALL", "DEPARTMENT", "USERS");
    private static final Set<String> PRIORITIES = Set.of("NORMAL", "IMPORTANT", "URGENT");
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final OfficeAccessService officeAccessService;

    public AnnouncementService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            AuditService auditService,
            OutboxService outboxService,
            OfficeAccessService officeAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.officeAccessService = officeAccessService;
    }

    @Transactional(readOnly = true)
    public List<AnnouncementView> list() {
        RequestActor actor = actorProvider.current();
        boolean manage = authorizationService.hasPermission(actor, "ANNOUNCEMENT_MANAGE");
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT a.id, a.title, a.summary, a.content, a.category, a.priority,
                               a.status, a.audience_type, u.display_name AS publisher_name,
                               a.published_at, a.expires_at,
                               a.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en,
                               (SELECT COUNT(*) FROM announcement_reads ar
                                WHERE ar.announcement_id = a.id) AS read_count,
                               EXISTS (
                                   SELECT 1 FROM announcement_reads ar
                                   WHERE ar.announcement_id = a.id AND ar.user_id = :userId
                               ) AS is_read
                        FROM announcements a
                        JOIN users u ON u.id = a.publisher_user_id
                        LEFT JOIN offices o ON o.id = a.office_id
                        WHERE a.organization_id = :organizationId
                          AND (a.office_id IS NULL OR :globalAccess
                               OR a.office_id IN (:officeIds))
                          AND (
                            (
                              :manage
                              AND (
                                (:globalAccess AND a.office_id IS NULL)
                                OR a.office_id IN (:managedOfficeIds)
                                OR (:globalAccess AND a.office_id IS NOT NULL)
                              )
                            )
                            OR (
                              a.status = 'PUBLISHED'
                              AND (a.expires_at IS NULL OR a.expires_at > now())
                              AND (
                                a.audience_type = 'ALL'
                                OR (a.audience_type = 'USERS' AND EXISTS (
                                  SELECT 1 FROM announcement_targets at
                                  WHERE at.announcement_id = a.id
                                    AND at.target_type = 'USER'
                                    AND at.target_id = :userId
                                ))
                                OR (a.audience_type = 'DEPARTMENT' AND EXISTS (
                                  SELECT 1 FROM announcement_targets at
                                  JOIN department_members dm
                                    ON dm.department_id = at.target_id
                                  WHERE at.announcement_id = a.id
                                    AND at.target_type = 'DEPARTMENT'
                                    AND dm.user_id = :userId
                                ))
                              )
                            )
                          )
                        ORDER BY
                          CASE a.priority WHEN 'URGENT' THEN 0 WHEN 'IMPORTANT' THEN 1 ELSE 2 END,
                          COALESCE(a.published_at, a.created_at) DESC
                        LIMIT 200
                        """)
                .param("organizationId", actor.organizationId())
                .param("userId", actor.userId())
                .param("manage", manage)
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .param("managedOfficeIds", scope.sqlManagedOfficeIds())
                .query((rs, rowNum) -> new AnnouncementView(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("summary"),
                        rs.getString("content"),
                        rs.getString("category"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        rs.getString("audience_type"),
                        rs.getString("publisher_name"),
                        rs.getTimestamp("published_at") == null
                                ? null : rs.getTimestamp("published_at").toInstant(),
                        rs.getTimestamp("expires_at") == null
                                ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getLong("read_count"),
                        rs.getBoolean("is_read"),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en")
                ))
                .list();
    }

    @Transactional
    public AnnouncementView create(CreateAnnouncementRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ANNOUNCEMENT_MANAGE");
        String audience = normalize(request.audienceType(), "ALL", AUDIENCES, "公告范围无效");
        String priority = normalize(request.priority(), "NORMAL", PRIORITIES, "公告优先级无效");
        List<UUID> targetIds = request.targetIds() == null ? List.of() : request.targetIds();
        if (!"ALL".equals(audience) && targetIds.isEmpty()) {
            throw new BusinessException(
                    "ANNOUNCEMENT_TARGET_REQUIRED", "定向公告必须选择接收范围", HttpStatus.BAD_REQUEST
            );
        }
        OfficeAccessScope scope = officeAccessService.scope(actor);
        UUID officeId = request.officeId() == null && scope.globalAccess()
                ? null
                : officeAccessService.resolveManagedOffice(actor, request.officeId());
        UUID id = jdbcClient.sql("""
                        INSERT INTO announcements
                            (organization_id, title, summary, content, category, priority,
                             audience_type, publisher_user_id, expires_at, office_id)
                        VALUES
                            (:organizationId, :title, :summary, :content, :category, :priority,
                             :audienceType, :publisherId, :expiresAt, :officeId)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("title", request.title().trim())
                .param("summary", trim(request.summary()))
                .param("content", request.content().trim())
                .param("category", request.category() == null ? "NOTICE" : request.category().trim())
                .param("priority", priority)
                .param("audienceType", audience)
                .param("publisherId", actor.userId())
                .param("expiresAt", request.expiresAt() == null
                        ? null : java.sql.Timestamp.from(request.expiresAt()))
                .param("officeId", officeId)
                .query(UUID.class)
                .single();
        if (!"ALL".equals(audience)) {
            String targetType = "USERS".equals(audience) ? "USER" : "DEPARTMENT";
            for (UUID targetId : targetIds.stream().distinct().toList()) {
                validateTarget(actor, targetType, targetId, officeId);
                jdbcClient.sql("""
                                INSERT INTO announcement_targets
                                    (announcement_id, target_type, target_id)
                                VALUES (:announcementId, :targetType, :targetId)
                                """)
                        .param("announcementId", id)
                        .param("targetType", targetType)
                        .param("targetId", targetId)
                        .update();
            }
        }
        auditService.success(actor, "ANNOUNCEMENT_CREATE", "ANNOUNCEMENT", id);
        return findVisible(id);
    }

    @Transactional
    public AnnouncementView publish(UUID id) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "ANNOUNCEMENT_MANAGE");
        findVisible(id);
        int updated = jdbcClient.sql("""
                        UPDATE announcements
                        SET status = 'PUBLISHED', published_at = COALESCE(published_at, now()),
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND status = 'DRAFT'
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        if (updated == 0) {
            throw new BusinessException(
                    "ANNOUNCEMENT_NOT_DRAFT", "公告不存在或已经发布", HttpStatus.CONFLICT
            );
        }
        AnnouncementView announcement = findVisible(id);
        for (UUID recipient : recipients(actor, id, announcement.audienceType())) {
            outboxService.enqueueNotification(
                    actor.organizationId(), recipient, "ANNOUNCEMENT_PUBLISHED",
                    announcement.title(), announcement.summary() == null
                            ? "有一条新公告待查看" : announcement.summary(),
                    "ANNOUNCEMENT", id, "/announcements?id=" + id,
                    "URGENT".equals(announcement.priority()) ? "URGENT" : "NORMAL",
                    "announcement:" + id
            );
        }
        auditService.success(actor, "ANNOUNCEMENT_PUBLISH", "ANNOUNCEMENT", id);
        return announcement;
    }

    @Transactional
    public AnnouncementView markRead(UUID id) {
        RequestActor actor = actorProvider.current();
        findVisible(id);
        jdbcClient.sql("""
                        INSERT INTO announcement_reads (announcement_id, user_id)
                        VALUES (:announcementId, :userId)
                        ON CONFLICT (announcement_id, user_id) DO NOTHING
                        """)
                .param("announcementId", id)
                .param("userId", actor.userId())
                .update();
        auditService.success(actor, "ANNOUNCEMENT_READ", "ANNOUNCEMENT", id);
        return findVisible(id);
    }

    private AnnouncementView findVisible(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "ANNOUNCEMENT_NOT_FOUND", "公告不存在或不可见", HttpStatus.NOT_FOUND
                ));
    }

    private void validateTarget(
            RequestActor actor,
            String targetType,
            UUID targetId,
            UUID officeId
    ) {
        Boolean exists;
        if ("USER".equals(targetType)) {
            exists = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM users u
                                WHERE u.id = :id
                                  AND u.organization_id = :organizationId
                                  AND u.deleted_at IS NULL
                                  AND (
                                    :firmWide
                                    OR u.primary_office_id = :officeId
                                    OR EXISTS (
                                      SELECT 1 FROM user_offices uo
                                      WHERE uo.user_id = u.id AND uo.office_id = :officeId
                                        AND (uo.valid_until IS NULL OR uo.valid_until > now())
                                    )
                                  )
                            )
                            """)
                    .param("id", targetId)
                    .param("organizationId", actor.organizationId())
                    .param("firmWide", officeId == null)
                    .param("officeId", officeId)
                    .query(Boolean.class)
                    .single();
        } else {
            exists = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM departments
                                WHERE id = :id AND organization_id = :organizationId
                                  AND deleted_at IS NULL
                                  AND (:firmWide OR office_id = :officeId)
                            )
                            """)
                    .param("id", targetId)
                    .param("organizationId", actor.organizationId())
                    .param("firmWide", officeId == null)
                    .param("officeId", officeId)
                    .query(Boolean.class)
                    .single();
        }
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException(
                    "ANNOUNCEMENT_TARGET_INVALID", "公告接收范围无效", HttpStatus.BAD_REQUEST
            );
        }
    }

    private List<UUID> recipients(RequestActor actor, UUID id, String audience) {
        if ("ALL".equals(audience)) {
            return jdbcClient.sql("""
                            SELECT u.id FROM users u
                            JOIN announcements a ON a.id = :id
                            WHERE u.organization_id = :organizationId
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                              AND (
                                a.office_id IS NULL
                                OR u.primary_office_id = a.office_id
                                OR EXISTS (
                                  SELECT 1 FROM user_offices uo
                                  WHERE uo.user_id = u.id
                                    AND uo.office_id = a.office_id
                                    AND (uo.valid_until IS NULL OR uo.valid_until > now())
                                )
                              )
                            """)
                    .param("id", id)
                    .param("organizationId", actor.organizationId())
                    .query(UUID.class).list();
        }
        if ("USERS".equals(audience)) {
            return jdbcClient.sql("""
                            SELECT u.id FROM announcement_targets at
                            JOIN users u ON u.id = at.target_id
                            WHERE at.announcement_id = :id AND at.target_type = 'USER'
                              AND u.organization_id = :organizationId
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                            """)
                    .param("id", id).param("organizationId", actor.organizationId())
                    .query(UUID.class).list();
        }
        return jdbcClient.sql("""
                        SELECT DISTINCT dm.user_id FROM announcement_targets at
                        JOIN department_members dm ON dm.department_id = at.target_id
                        JOIN users u ON u.id = dm.user_id
                        WHERE at.announcement_id = :id AND at.target_type = 'DEPARTMENT'
                          AND u.organization_id = :organizationId
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                        """)
                .param("id", id).param("organizationId", actor.organizationId())
                .query(UUID.class).list();
    }

    private static String normalize(
            String value, String fallback, Set<String> accepted, String message
    ) {
        String normalized = value == null ? fallback : value.toUpperCase(Locale.ROOT);
        if (!accepted.contains(normalized)) {
            throw new BusinessException("ENUM_INVALID", message, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
