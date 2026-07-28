package com.zoro.legaloa.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    WorkflowInstanceView start(
            @Valid @RequestBody StartWorkflowRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return workflowService.start(request, idempotencyKey);
    }

    @GetMapping("/tasks")
    List<WorkflowTaskView> tasks() {
        return workflowService.tasks();
    }

    @GetMapping("/inbox")
    List<WorkflowTaskView> inbox(
            @RequestParam(defaultValue = "PENDING") String status
    ) {
        return workflowService.inbox(status);
    }

    @PostMapping("/tasks/{taskId}/complete")
    WorkflowActionResult complete(
            @PathVariable String taskId,
            @Valid @RequestBody CompleteTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return workflowService.complete(taskId, request, idempotencyKey);
    }

    @PostMapping("/tasks/{taskId}/transfer")
    WorkflowActionResult transfer(
            @PathVariable String taskId,
            @Valid @RequestBody TransferTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return workflowService.transfer(taskId, request, idempotencyKey);
    }

    @PostMapping("/tasks/{taskId}/remind")
    void remind(@PathVariable String taskId) {
        workflowService.remind(taskId);
    }

    public record StartWorkflowRequest(
            @NotBlank @Size(max = 80) String businessType,
            @NotNull UUID businessId,
            Map<String, Object> variables
    ) {}

    public record CompleteTaskRequest(
            @Size(max = 20) String decision,
            @Size(max = 500) String comment,
            Map<String, Object> variables
    ) {}

    public record TransferTaskRequest(
            @NotNull UUID targetUserId,
            @Size(max = 500) String comment
    ) {}

    public record WorkflowInstanceView(
            UUID id,
            String businessType,
            UUID businessId,
            String processDefinitionKey,
            String processInstanceId,
            String status,
            String decision,
            Instant startedAt
    ) {}

    public record WorkflowTaskView(
            String id,
            String name,
            String processInstanceId,
            String businessKey,
            String businessType,
            UUID businessId,
            String assignee,
            String candidateGroup,
            String status,
            Instant createdAt,
            Instant completedAt,
            String decision
    ) {}

    public record WorkflowActionResult(
            UUID workflowId,
            String taskId,
            String workflowStatus,
            String decision,
            boolean hasNextTask,
            String nextTaskName
    ) {}
}
