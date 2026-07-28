package com.zoro.legaloa.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zoro.legaloa.archive.ArchiveController.AddArchiveItemRequest;
import com.zoro.legaloa.archive.ArchiveController.CreateArchiveVolumeRequest;
import com.zoro.legaloa.document.DocumentController.InitiateUploadRequest;
import com.zoro.legaloa.identity.OrganizationSyncController.DepartmentSnapshot;
import com.zoro.legaloa.identity.OrganizationSyncController.OrganizationSyncRequest;
import com.zoro.legaloa.matter.ContractController.CreateContractRequest;
import com.zoro.legaloa.matter.DeadlineController.CreateDeadlineRequest;
import com.zoro.legaloa.matter.MatterController.CreateMatterRequest;
import com.zoro.legaloa.office.AnnouncementController.CreateAnnouncementRequest;
import com.zoro.legaloa.office.ExpenseController.CreateExpenseRequest;
import com.zoro.legaloa.office.ExpenseController.ExpenseItemRequest;
import com.zoro.legaloa.office.LeaveController.CreateLeaveRequest;
import com.zoro.legaloa.office.MeetingController.CreateBookingRequest;
import com.zoro.legaloa.office.MeetingController.CreateMeetingRoomRequest;
import com.zoro.legaloa.office.WorkTaskController.AddTaskCommentRequest;
import com.zoro.legaloa.office.WorkTaskController.ChangeTaskStatusRequest;
import com.zoro.legaloa.office.WorkTaskController.CreateWorkTaskRequest;
import com.zoro.legaloa.party.ClientController.CreateClientRequest;
import com.zoro.legaloa.party.PartyController.CreatePartyRequest;
import com.zoro.legaloa.party.PartyController.PartyType;
import com.zoro.legaloa.seal.SealController.CreateSealRequest;
import com.zoro.legaloa.workflow.WorkflowController.CompleteTaskRequest;
import com.zoro.legaloa.workflow.WorkflowController.StartWorkflowRequest;
import com.zoro.legaloa.workflow.WorkflowController.TransferTaskRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class RequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant FUTURE = Instant.now().plus(10, ChronoUnit.DAYS);

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void validatesPartyAndClientRequests() {
        assertInvalid(new CreatePartyRequest(
                PartyType.ORGANIZATION, " ", null, null, List.of()
        ), "displayName");
        assertInvalid(new CreateClientRequest(null, "C-001", ID, "WEB"), "partyId");
        assertValid(new CreateClientRequest(ID, "C-001", ID, "WEB"));
    }

    @Test
    void validatesInternationalMatterFields() {
        CreateMatterRequest invalid = new CreateMatterRequest(
                "M-001", "Matter", "LITIGATION", ID, LocalDate.now(),
                null, null, null, ID, "cn", "China",
                "fr-FR", "rmb", List.of(), List.of()
        );
        assertInvalid(invalid, "countryCode");
        assertInvalid(invalid, "workingLanguage");
        assertInvalid(invalid, "billingCurrency");

        assertValid(new CreateMatterRequest(
                "M-001", "Matter", "LITIGATION", ID, LocalDate.now(),
                null, null, null, ID, "CN", "China",
                "en-US", "CNY", List.of(), List.of()
        ));
    }

    @Test
    void validatesContractAndDeadlineRequests() {
        assertInvalid(new CreateContractRequest(
                "C-001", "Contract", null, null, null,
                null, null, "CNY", List.of()
        ), "responsibleUserId");
        assertInvalid(new CreateDeadlineRequest(
                ID, "Deadline", Instant.now().minusSeconds(1),
                "COURT", ID, "HIGH", List.of(7, 3, 1)
        ), "dueAt");
    }

    @Test
    void validatesDocumentUploadSecurityBounds() {
        assertInvalid(new InitiateUploadRequest(
                null, ID, null, "Evidence", "CASE_FILE", "a.pdf",
                "application/pdf", 0, "xyz"
        ), "sizeBytes");
        assertInvalid(new InitiateUploadRequest(
                null, ID, null, "Evidence", "CASE_FILE", "a.pdf",
                "application/pdf", 1, "xyz"
        ), "sha256");
        assertValid(new InitiateUploadRequest(
                null, ID, null, "Evidence", "CASE_FILE", "a.pdf",
                "application/pdf", 1, "a".repeat(64)
        ));
    }

    @Test
    void validatesArchiveAndSealRequests() {
        assertInvalid(new CreateArchiveVolumeRequest(" ", "Title", "10Y"), "archiveNumber");
        assertInvalid(new AddArchiveItemRequest(null), "documentId");
        assertInvalid(new CreateSealRequest(ID, null, null, "Purpose", 0), "copies");
    }

    @Test
    void validatesWorkflowCommands() {
        assertInvalid(new StartWorkflowRequest(" ", ID, Map.of()), "businessType");
        assertInvalid(new CompleteTaskRequest("SKIP", "x".repeat(501), Map.of()), "comment");
        assertInvalid(new TransferTaskRequest(null, "Transfer"), "targetUserId");
    }

    @Test
    void validatesAnnouncementExpiryAndContent() {
        assertInvalid(new CreateAnnouncementRequest(
                " ", null, "Body", "NOTICE", "NORMAL", "ALL",
                List.of(), null, null
        ), "title");
        assertInvalid(new CreateAnnouncementRequest(
                "Title", null, "Body", "NOTICE", "NORMAL", "ALL",
                List.of(), Instant.now().minusSeconds(1), null
        ), "expiresAt");
    }

    @Test
    void validatesWorkTaskCommands() {
        assertInvalid(new CreateWorkTaskRequest(
                "Task", null, null, List.of(), FUTURE,
                "HIGH", null, null
        ), "ownerUserId");
        assertInvalid(new ChangeTaskStatusRequest(" "), "status");
        assertInvalid(new AddTaskCommentRequest(" "), "content");
    }

    @Test
    void validatesLeaveRequestMinimumDuration() {
        assertInvalid(new CreateLeaveRequest(
                "ANNUAL", FUTURE, FUTURE.plus(1, ChronoUnit.HOURS),
                BigDecimal.ZERO, "Reason", null
        ), "durationHours");
    }

    @Test
    void validatesExpenseHeaderAndNestedItems() {
        ExpenseItemRequest invalidItem = new ExpenseItemRequest(
                "Travel", LocalDate.now(), "Taxi", BigDecimal.ZERO, null
        );
        Set<ConstraintViolation<CreateExpenseRequest>> violations = VALIDATOR.validate(
                new CreateExpenseRequest("Claim", "Purpose", null, List.of(invalidItem))
        );
        assertThat(violations).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("items[0].amount"));
        assertInvalid(new CreateExpenseRequest("Claim", "Purpose", null, List.of()), "items");
    }

    @Test
    void validatesMeetingRoomAndBookingLimits() {
        assertInvalid(new CreateMeetingRoomRequest("Room", "Dubai", 0, List.of(), null), "capacity");
        assertInvalid(new CreateBookingRequest(
                ID, "Meeting", FUTURE, FUTURE.plus(1, ChronoUnit.HOURS),
                0, List.of(), null
        ), "attendeeCount");
        assertInvalid(new CreateBookingRequest(
                ID, "Meeting", Instant.now().minusSeconds(10), FUTURE,
                1, List.of(), null
        ), "startAt");
    }

    @Test
    void validatesOrganizationSnapshotAndCopiesMutableCollections() {
        OrganizationSyncRequest request = new OrganizationSyncRequest(
                "MANUAL", "SNAPSHOT", false, null, null
        );
        assertInvalid(request, "departments");
        assertThat(request.departments()).isEmpty();
        assertThat(request.users()).isEmpty();

        assertInvalid(new OrganizationSyncRequest(
                "MANUAL", "SNAPSHOT", false,
                List.of(new DepartmentSnapshot(" ", null, "Team", 1)), List.of()
        ), "departments[0].externalDepartmentId");
    }

    private static <T> void assertValid(T value) {
        assertThat(VALIDATOR.validate(value)).isEmpty();
    }

    private static <T> void assertInvalid(T value, String property) {
        assertThat(VALIDATOR.validate(value))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(property);
    }
}
