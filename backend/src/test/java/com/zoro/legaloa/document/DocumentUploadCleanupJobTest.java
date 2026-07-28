package com.zoro.legaloa.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.zoro.legaloa.document.DocumentCleanupRepository.CleanupCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentUploadCleanupJobTest {
    @Mock DocumentCleanupRepository cleanupRepository;
    @Mock ObjectStorageService storageService;

    @Test
    void expiresIncompleteUploadsAndOnlyMarksSuccessfullyDeletedObjects() {
        UUID deletedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        var candidates = List.of(
                new CleanupCandidate(deletedId, "quarantine/deleted"),
                new CleanupCandidate(failedId, "quarantine/retry")
        );
        var registry = new SimpleMeterRegistry();
        var job = new DocumentUploadCleanupJob(
                cleanupRepository, storageService, registry, 25
        );

        job.cleanup();
        job.cleanupCandidates(List.of(candidates.getFirst()));

        ObjectStorageService failingStorageService = mock(ObjectStorageService.class);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(failingStorageService).delete("quarantine/retry");
        var failingJob = new DocumentUploadCleanupJob(
                cleanupRepository, failingStorageService, registry, 25
        );
        failingJob.cleanupCandidates(List.of(candidates.getLast()));

        verify(cleanupRepository).expireIncompleteUploads(25);
        verify(cleanupRepository).findUnreferencedObjects(25);
        verify(storageService).delete("quarantine/deleted");
        verify(failingStorageService).delete("quarantine/retry");
        verify(cleanupRepository).markCleaned(deletedId);
        assertThat(registry.counter("law_oa.document.cleanup.deleted").count()).isEqualTo(1);
        assertThat(registry.counter("law_oa.document.cleanup.failed").count()).isEqualTo(1);
    }
}
