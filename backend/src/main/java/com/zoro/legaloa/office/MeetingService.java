package com.zoro.legaloa.office;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessScope;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.office.MeetingController.CreateBookingRequest;
import com.zoro.legaloa.office.MeetingController.CreateMeetingRoomRequest;
import com.zoro.legaloa.office.MeetingController.MeetingBookingView;
import com.zoro.legaloa.office.MeetingController.MeetingRoomView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {
    private final JdbcClient jdbcClient;
    private final RequestActorProvider actorProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final OfficeAccessService officeAccessService;

    public MeetingService(
            JdbcClient jdbcClient,
            RequestActorProvider actorProvider,
            AuthorizationService authorizationService,
            AuditService auditService,
            OutboxService outboxService,
            ObjectMapper objectMapper,
            OfficeAccessService officeAccessService
    ) {
        this.jdbcClient = jdbcClient;
        this.actorProvider = actorProvider;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.officeAccessService = officeAccessService;
    }

    @Transactional(readOnly = true)
    public List<MeetingRoomView> rooms() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT r.id, r.name, r.location, r.capacity,
                               COALESCE((
                                 SELECT string_agg(value, '、')
                                 FROM jsonb_array_elements_text(r.facilities) value
                               ), '') AS facilities_text,
                               r.status, r.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en
                        FROM meeting_rooms r
                        LEFT JOIN offices o ON o.id = r.office_id
                        WHERE r.organization_id = :organizationId
                          AND (:globalAccess OR r.office_id IN (:officeIds))
                        ORDER BY r.status, o.code, r.name
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new MeetingRoomView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("location"),
                        rs.getInt("capacity"),
                        rs.getString("facilities_text"),
                        rs.getString("status"),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en")
                )).list();
    }

    @Transactional
    public MeetingRoomView createRoom(CreateMeetingRoomRequest request) {
        RequestActor actor = actorProvider.current();
        authorizationService.requirePermission(actor, "MEETING_ROOM_MANAGE");
        UUID officeId = officeAccessService.resolveManagedOffice(actor, request.officeId());
        String facilities;
        try {
            facilities = objectMapper.writeValueAsString(
                    request.facilities() == null ? List.of() : request.facilities()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize facilities", exception);
        }
        UUID id = jdbcClient.sql("""
                        INSERT INTO meeting_rooms
                            (organization_id, name, location, capacity, facilities, office_id)
                        VALUES
                            (:organizationId, :name, :location, :capacity,
                             CAST(:facilities AS jsonb), :officeId)
                        RETURNING id
                        """)
                .param("organizationId", actor.organizationId())
                .param("name", request.name().trim())
                .param("location", request.location().trim())
                .param("capacity", request.capacity())
                .param("facilities", facilities)
                .param("officeId", officeId)
                .query(UUID.class).single();
        auditService.success(actor, "MEETING_ROOM_CREATE", "MEETING_ROOM", id);
        return rooms().stream().filter(room -> room.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<MeetingBookingView> bookings() {
        RequestActor actor = actorProvider.current();
        OfficeAccessScope scope = officeAccessService.scope(actor);
        return jdbcClient.sql("""
                        SELECT b.id, b.meeting_room_id, r.name AS room_name,
                               r.location AS room_location, b.title,
                               b.organizer_user_id, u.display_name AS organizer_name,
                               b.start_at, b.end_at, b.attendee_count, b.notes, b.status,
                               r.office_id, o.name_zh AS office_name_zh,
                               o.name_en AS office_name_en
                        FROM meeting_bookings b
                        JOIN meeting_rooms r ON r.id = b.meeting_room_id
                        LEFT JOIN offices o ON o.id = r.office_id
                        JOIN users u ON u.id = b.organizer_user_id
                        WHERE b.organization_id = :organizationId
                          AND b.end_at >= now() - interval '1 day'
                          AND b.start_at <= now() + interval '90 days'
                          AND (:globalAccess OR r.office_id IN (:officeIds))
                        ORDER BY b.start_at
                        LIMIT 500
                        """)
                .param("organizationId", actor.organizationId())
                .param("globalAccess", scope.globalAccess())
                .param("officeIds", scope.sqlOfficeIds())
                .query((rs, rowNum) -> new MeetingBookingView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("meeting_room_id", UUID.class),
                        rs.getString("room_name"),
                        rs.getString("room_location"),
                        rs.getString("title"),
                        rs.getObject("organizer_user_id", UUID.class),
                        rs.getString("organizer_name"),
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant(),
                        rs.getInt("attendee_count"),
                        rs.getString("notes"),
                        rs.getString("status"),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("office_name_zh"),
                        rs.getString("office_name_en")
                )).list();
    }

    @Transactional
    public MeetingBookingView createBooking(CreateBookingRequest request) {
        RequestActor actor = actorProvider.current();
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BusinessException(
                    "MEETING_TIME_INVALID", "结束时间必须晚于开始时间", HttpStatus.BAD_REQUEST
            );
        }
        OfficeRoom room = jdbcClient.sql("""
                        SELECT capacity, office_id FROM meeting_rooms
                        WHERE id = :roomId AND organization_id = :organizationId
                          AND status = 'ACTIVE'
                        """)
                .param("roomId", request.roomId())
                .param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new OfficeRoom(
                        rs.getInt("capacity"),
                        rs.getObject("office_id", UUID.class)
                )).optional()
                .orElseThrow(() -> new BusinessException(
                        "MEETING_ROOM_INVALID", "会议室不存在或已停用", HttpStatus.BAD_REQUEST
                ));
        OfficeAccessScope scope = officeAccessService.scope(actor);
        if (!scope.canAccess(room.officeId())) {
            throw new BusinessException(
                    "OFFICE_ACCESS_DENIED", "无权预约该办公室的会议室",
                    HttpStatus.FORBIDDEN
            );
        }
        if (request.attendeeCount() > room.capacity()) {
            throw new BusinessException(
                    "MEETING_CAPACITY_EXCEEDED",
                    "参会人数超过会议室容量 " + room.capacity(),
                    HttpStatus.BAD_REQUEST
            );
        }
        UUID[] attendeeIds = (request.attendeeUserIds() == null
                ? List.<UUID>of() : request.attendeeUserIds()).stream().distinct().toArray(UUID[]::new);
        if (!scope.globalAccess()) {
            for (UUID attendeeId : attendeeIds) {
                officeAccessService.requireUserInOffice(actor, attendeeId, room.officeId());
            }
        }
        UUID id;
        try {
            id = jdbcClient.sql("""
                            INSERT INTO meeting_bookings
                                (organization_id, meeting_room_id, organizer_user_id,
                                 title, start_at, end_at, attendee_count,
                                 attendee_user_ids, notes)
                            VALUES
                                (:organizationId, :roomId, :organizerId,
                                 :title, :startAt, :endAt, :attendeeCount,
                                 :attendeeUserIds, :notes)
                            RETURNING id
                            """)
                    .param("organizationId", actor.organizationId())
                    .param("roomId", request.roomId())
                    .param("organizerId", actor.userId())
                    .param("title", request.title().trim())
                    .param("startAt", java.sql.Timestamp.from(request.startAt()))
                    .param("endAt", java.sql.Timestamp.from(request.endAt()))
                    .param("attendeeCount", request.attendeeCount())
                    .param("attendeeUserIds", attendeeIds)
                    .param("notes", trim(request.notes()))
                    .query(UUID.class).single();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "MEETING_TIME_CONFLICT",
                    "该会议室在所选时间段已被预约",
                    HttpStatus.CONFLICT
            );
        }
        for (UUID attendee : attendeeIds) {
            if (!attendee.equals(actor.userId())) {
                outboxService.enqueueNotification(
                        actor.organizationId(), attendee, "MEETING_INVITATION",
                        "会议邀请", request.title().trim(),
                        "MEETING_BOOKING", id, "/meetings?id=" + id,
                        "NORMAL", "meeting-invite:" + id + ":" + attendee
                );
            }
        }
        auditService.success(actor, "MEETING_BOOKING_CREATE", "MEETING_BOOKING", id);
        return find(id);
    }

    @Transactional
    public MeetingBookingView cancelBooking(UUID id) {
        RequestActor actor = actorProvider.current();
        MeetingBookingView booking = find(id);
        if (!booking.organizerUserId().equals(actor.userId())
                && (!authorizationService.hasPermission(actor, "MEETING_ROOM_MANAGE")
                || !officeAccessService.scope(actor).canManage(booking.officeId()))) {
            throw new BusinessException(
                    "MEETING_CANCEL_DENIED", "只有组织者或会议室管理员可以取消预约",
                    HttpStatus.FORBIDDEN
            );
        }
        jdbcClient.sql("""
                        UPDATE meeting_bookings
                        SET status = 'CANCELLED', updated_at = now(), version = version + 1
                        WHERE id = :id AND organization_id = :organizationId
                          AND status = 'CONFIRMED'
                        """)
                .param("id", id)
                .param("organizationId", actor.organizationId())
                .update();
        auditService.success(actor, "MEETING_BOOKING_CANCEL", "MEETING_BOOKING", id);
        return find(id);
    }

    private MeetingBookingView find(UUID id) {
        return bookings().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(
                        "MEETING_BOOKING_NOT_FOUND", "会议预约不存在", HttpStatus.NOT_FOUND
                ));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record OfficeRoom(int capacity, UUID officeId) {}
}
