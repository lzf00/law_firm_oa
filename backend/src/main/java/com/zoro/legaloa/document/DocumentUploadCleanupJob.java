package com.zoro.legaloa.document;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.zoro.legaloa.document.DocumentCleanupRepository.CleanupCandidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentUploadCleanupJob {
    private final DocumentCleanupRepository cleanupRepository;
    private final ObjectStorageService storageService;
    private final Counter deletedCounter;
    private final Counter failedCounter;
    private final int batchSize;

    public DocumentUploadCleanupJob(
            DocumentCleanupRepository cleanupRepository,
            ObjectStorageService storageService,
            MeterRegistry meterRegistry,
            @Value("${app.document.cleanup.batch-size:100}") int batchSize
    ) {
        this.cleanupRepository = cleanupRepository;
        this.storageService = storageService;
        this.deletedCounter = meterRegistry.counter("law_oa.document.cleanup.deleted");
        this.failedCounter = meterRegistry.counter("law_oa.document.cleanup.failed");
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(fixedDelayString = "${app.document.cleanup.interval:PT1H}")
    public void cleanup() {
        cleanupRepository.expireIncompleteUploads(batchSize);
        cleanupCandidates(cleanupRepository.findUnreferencedObjects(batchSize));
    }

    void cleanupCandidates(Iterable<CleanupCandidate> candidates) {
        for (CleanupCandidate candidate : candidates) {
            try {
                storageService.delete(candidate.objectKey());
                cleanupRepository.markCleaned(candidate.uploadId());
                deletedCounter.increment();
            } catch (RuntimeException exception) {
                failedCounter.increment();
            }
        }
    }
}
