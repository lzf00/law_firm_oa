package com.zoro.legaloa.common;

import java.util.Locale;
import java.util.Map;

/**
 * Keeps API error codes stable while localizing their human-readable message.
 * Clients should branch on the code, never on the translated text.
 */
final class ApiMessageCatalog {
    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("VALIDATION_FAILED", "Request validation failed"),
            Map.entry("PARAMETER_INVALID", "The request parameter format is invalid"),
            Map.entry("RESOURCE_CONFLICT", "The resource already exists or its state has changed"),
            Map.entry("INTERNAL_ERROR", "The system could not process the request"),
            Map.entry("PERMISSION_DENIED", "The current account lacks the required permission"),
            Map.entry("MATTER_NOT_FOUND", "The matter does not exist or is not accessible"),
            Map.entry("RESPONSIBLE_USER_INVALID", "The responsible counsel is inactive or outside this organization"),
            Map.entry("OFFICE_INVALID", "The responsible office does not exist or is inactive"),
            Map.entry("OFFICE_ACCESS_DENIED", "You do not have access to the selected office"),
            Map.entry("OFFICE_MANAGE_DENIED", "You cannot manage the selected office"),
            Map.entry("USER_OFFICE_MISMATCH", "The selected user is not assigned to this office"),
            Map.entry("PARTY_INVALID", "The party does not exist or is outside this organization"),
            Map.entry("DOCUMENT_CONTEXT_INVALID", "Select exactly one matter or contract context"),
            Map.entry("DOCUMENT_ACCESS_DENIED", "You do not have access to this document"),
            Map.entry("DOCUMENT_DOWNLOAD_RATE_LIMITED", "Too many downloads in a short period; retry later"),
            Map.entry("DOCUMENT_SECURITY_STATUS_INVALID", "The document security event status is invalid"),
            Map.entry("OFFICE_ACCESS_LEVEL_INVALID", "The office access level is invalid"),
            Map.entry("OFFICE_GRANT_DENIED", "You cannot grant or revoke this office access"),
            Map.entry("OFFICE_PRIMARY_EXPIRY_INVALID", "Primary office access cannot expire"),
            Map.entry("OFFICE_GRANT_EXPIRED", "The access expiry must be in the future"),
            Map.entry("OFFICE_MEMBER_INVALID", "The user is inactive or outside this organization"),
            Map.entry("OFFICE_MEMBERSHIP_NOT_FOUND", "The office membership does not exist"),
            Map.entry("OFFICE_PRIMARY_REVOKE_DENIED", "Assign another primary office before revoking this access"),
            Map.entry("FILE_TYPE_NOT_ALLOWED", "This file type is not allowed"),
            Map.entry("UPLOAD_SESSION_INVALID", "The upload session is missing, completed, or expired"),
            Map.entry("UPLOAD_SIZE_MISMATCH", "The uploaded file size does not match the registered size"),
            Map.entry("UPLOAD_HASH_MISMATCH", "The uploaded file integrity check failed"),
            Map.entry("LEAVE_TYPE_INVALID", "The leave type is invalid"),
            Map.entry("LEAVE_TIME_INVALID", "The end time must be later than the start time"),
            Map.entry("LEAVE_TIME_CONFLICT", "An effective leave request already overlaps this period"),
            Map.entry("EXPENSE_MATTER_INVALID", "The related matter is invalid"),
            Map.entry("EXPENSE_SUBMIT_INVALID", "Only the applicant may submit a draft expense claim"),
            Map.entry("MEETING_TIME_INVALID", "The end time must be later than the start time"),
            Map.entry("MEETING_ROOM_INVALID", "The meeting room does not exist or is inactive"),
            Map.entry("MEETING_CAPACITY_EXCEEDED", "The attendee count exceeds the room capacity"),
            Map.entry("MEETING_TIME_CONFLICT", "The room is already booked for the selected period"),
            Map.entry("MEETING_CANCEL_DENIED", "Only the organizer or a room administrator may cancel this booking"),
            Map.entry("ANNOUNCEMENT_TARGET_REQUIRED", "A targeted announcement requires at least one recipient"),
            Map.entry("ANNOUNCEMENT_NOT_DRAFT", "The announcement does not exist or has already been published"),
            Map.entry("WORK_TASK_OWNER_INVALID", "The task owner is inactive or outside this organization"),
            Map.entry("WORK_TASK_BUSINESS_INVALID", "The related matter is invalid or inaccessible"),
            Map.entry("WORK_TASK_UPDATE_DENIED", "Only the owner or creator may update this task"),
            Map.entry("WORKFLOW_TYPE_INVALID", "This approval business type is not supported"),
            Map.entry("WORKFLOW_ALREADY_RUNNING", "An approval workflow is already running for this business record"),
            Map.entry("WORKFLOW_TASK_DENIED", "The current account cannot operate this approval task"),
            Map.entry("TRANSFER_TARGET_SCOPE_DENIED", "The target user cannot access this business scope"),
            Map.entry("IDEMPOTENCY_KEY_REUSED", "The idempotency key was reused with different request data")
    );

    private ApiMessageCatalog() {}

    static String message(String code, String fallback, Locale locale) {
        if (locale != null && "en".equalsIgnoreCase(locale.getLanguage())) {
            return ENGLISH.getOrDefault(code, fallback);
        }
        return fallback;
    }
}
