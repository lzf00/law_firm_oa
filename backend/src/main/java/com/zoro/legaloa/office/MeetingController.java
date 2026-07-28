package com.zoro.legaloa.office;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {
    private final MeetingService service;

    public MeetingController(MeetingService service) {
        this.service = service;
    }

    @GetMapping("/rooms")
    List<MeetingRoomView> rooms() {
        return service.rooms();
    }

    @PostMapping("/rooms")
    MeetingRoomView createRoom(@Valid @RequestBody CreateMeetingRoomRequest request) {
        return service.createRoom(request);
    }

    @GetMapping("/bookings")
    List<MeetingBookingView> bookings() {
        return service.bookings();
    }

    @PostMapping("/bookings")
    MeetingBookingView createBooking(@Valid @RequestBody CreateBookingRequest request) {
        return service.createBooking(request);
    }

    @PostMapping("/bookings/{id}/cancel")
    MeetingBookingView cancelBooking(@PathVariable UUID id) {
        return service.cancelBooking(id);
    }

    public record CreateMeetingRoomRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 300) String location,
            @Min(1) int capacity,
            List<@Size(max = 80) String> facilities,
            UUID officeId
    ) {}

    public record CreateBookingRequest(
            @NotNull UUID roomId,
            @NotBlank @Size(max = 300) String title,
            @NotNull @Future Instant startAt,
            @NotNull @Future Instant endAt,
            @Min(1) int attendeeCount,
            List<UUID> attendeeUserIds,
            @Size(max = 1000) String notes
    ) {}

    public record MeetingRoomView(
            UUID id,
            String name,
            String location,
            int capacity,
            String facilities,
            String status,
            UUID officeId,
            String officeNameZh,
            String officeNameEn
    ) {}

    public record MeetingBookingView(
            UUID id,
            UUID roomId,
            String roomName,
            String roomLocation,
            String title,
            UUID organizerUserId,
            String organizerName,
            Instant startAt,
            Instant endAt,
            int attendeeCount,
            String notes,
            String status,
            UUID officeId,
            String officeNameZh,
            String officeNameEn
    ) {}
}
