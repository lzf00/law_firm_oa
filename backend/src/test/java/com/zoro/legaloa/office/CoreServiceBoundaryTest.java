package com.zoro.legaloa.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.AuthorizationService;
import com.zoro.legaloa.common.BusinessException;
import com.zoro.legaloa.document.DocumentAccessService;
import com.zoro.legaloa.document.AntivirusScanner;
import com.zoro.legaloa.document.DocumentController.InitiateUploadRequest;
import com.zoro.legaloa.document.DocumentService;
import com.zoro.legaloa.document.DocumentSecurityService;
import com.zoro.legaloa.document.ObjectStorageService;
import com.zoro.legaloa.identity.RequestActor;
import com.zoro.legaloa.identity.RequestActorProvider;
import com.zoro.legaloa.identity.OfficeAccessService;
import com.zoro.legaloa.notification.OutboxService;
import com.zoro.legaloa.office.AnnouncementController.CreateAnnouncementRequest;
import com.zoro.legaloa.office.LeaveController.CreateLeaveRequest;
import com.zoro.legaloa.office.MeetingController.CreateBookingRequest;
import com.zoro.legaloa.office.WorkTaskController.CreateWorkTaskRequest;
import com.zoro.legaloa.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class CoreServiceBoundaryTest {
    @Mock JdbcClient jdbcClient;
    @Mock RequestActorProvider actorProvider;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock OutboxService outboxService;
    @Mock WorkflowService workflowService;
    @Mock DocumentAccessService documentAccessService;
    @Mock ObjectStorageService objectStorageService;
    @Mock DocumentSecurityService documentSecurityService;
    @Mock AntivirusScanner antivirusScanner;
    @Mock PlatformTransactionManager transactionManager;
    @Mock OfficeAccessService officeAccessService;

    private RequestActor actor;

    @BeforeEach
    void setUp() {
        actor = new RequestActor(
                UUID.randomUUID(), UUID.randomUUID(), "tester", "Test User"
        );
    }

    @Test
    void meetingRejectsEndTimeBeforeStartWithoutTouchingDatabase() {
        givenActor();
        MeetingService service = new MeetingService(
                jdbcClient, actorProvider, authorizationService, auditService,
                outboxService, new ObjectMapper(), officeAccessService
        );
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);

        assertThatThrownBy(() -> service.createBooking(new CreateBookingRequest(
                UUID.randomUUID(), "Planning", start, start.minusSeconds(1),
                2, List.of(), null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MEETING_TIME_INVALID");
                    assertThat(exception.status().value()).isEqualTo(400);
                });
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void leaveRejectsUnknownTypeWithoutTouchingDatabase() {
        givenActor();
        LeaveService service = new LeaveService(
                jdbcClient, actorProvider, authorizationService, auditService,
                workflowService, officeAccessService
        );
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);

        assertThatThrownBy(() -> service.create(new CreateLeaveRequest(
                "SABBATICAL", start, start.plus(8, ChronoUnit.HOURS),
                BigDecimal.valueOf(8), "Test", null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("LEAVE_TYPE_INVALID"));
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void leaveRejectsNonIncreasingTimeRangeWithoutTouchingDatabase() {
        givenActor();
        LeaveService service = new LeaveService(
                jdbcClient, actorProvider, authorizationService, auditService,
                workflowService, officeAccessService
        );
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);

        assertThatThrownBy(() -> service.create(new CreateLeaveRequest(
                "ANNUAL", start, start, BigDecimal.ONE, "Test", null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("LEAVE_TIME_INVALID"));
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void announcementRejectsUnsupportedAudienceWithoutTouchingDatabase() {
        givenActor();
        AnnouncementService service = new AnnouncementService(
                jdbcClient, actorProvider, authorizationService, auditService,
                outboxService, officeAccessService
        );

        assertThatThrownBy(() -> service.create(new CreateAnnouncementRequest(
                "Title", null, "Body", "NOTICE", "NORMAL",
                "OFFICE_ONLY", List.of(), null, null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ENUM_INVALID"));
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void targetedAnnouncementRequiresRecipientsWithoutTouchingDatabase() {
        givenActor();
        AnnouncementService service = new AnnouncementService(
                jdbcClient, actorProvider, authorizationService, auditService,
                outboxService, officeAccessService
        );

        assertThatThrownBy(() -> service.create(new CreateAnnouncementRequest(
                "Title", null, "Body", "NOTICE", "NORMAL",
                "USERS", List.of(), null, null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ANNOUNCEMENT_TARGET_REQUIRED"));
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void workTaskRejectsUnsupportedPriorityWithoutTouchingDatabase() {
        givenActor();
        WorkTaskService service = new WorkTaskService(
                jdbcClient, actorProvider, authorizationService, auditService,
                outboxService, officeAccessService
        );

        assertThatThrownBy(() -> service.create(new CreateWorkTaskRequest(
                "Task", null, UUID.randomUUID(), List.of(), null,
                "CRITICAL", null, null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ENUM_INVALID"));
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void documentRequiresExactlyOneBusinessContext() {
        DocumentService service = new DocumentService(
                jdbcClient, actorProvider, documentAccessService, objectStorageService,
                auditService, documentSecurityService, antivirusScanner, transactionManager
        );

        assertThatThrownBy(() -> service.initiate(uploadRequest(null, null, "application/pdf")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("DOCUMENT_CONTEXT_INVALID"));
        verifyNoInteractions(jdbcClient, documentAccessService, objectStorageService);
    }

    @Test
    void documentRejectsDisallowedContentTypeBeforeAuthorizationOrStorage() {
        DocumentService service = new DocumentService(
                jdbcClient, actorProvider, documentAccessService, objectStorageService,
                auditService, documentSecurityService, antivirusScanner, transactionManager
        );

        assertThatThrownBy(() -> service.initiate(uploadRequest(
                UUID.randomUUID(), null, "application/x-msdownload"
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED"));
        verifyNoInteractions(jdbcClient, documentAccessService, objectStorageService);
    }

    private static InitiateUploadRequest uploadRequest(
            UUID matterId,
            UUID contractId,
            String contentType
    ) {
        return new InitiateUploadRequest(
                null, matterId, contractId, "Evidence", "CASE_FILE",
                "evidence.pdf", contentType, 10, "a".repeat(64)
        );
    }

    private void givenActor() {
        when(actorProvider.current()).thenReturn(actor);
    }
}
