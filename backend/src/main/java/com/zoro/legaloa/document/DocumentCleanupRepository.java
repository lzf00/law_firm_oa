package com.zoro.legaloa.document;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class DocumentCleanupRepository {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public DocumentCleanupRepository(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void expireIncompleteUploads(int batchSize) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        UPDATE document_upload_sessions
                        SET status = 'EXPIRED', completed_at = COALESCE(completed_at, now())
                        WHERE id IN (
                            SELECT id
                            FROM document_upload_sessions
                            WHERE status IN ('PENDING', 'SCANNING')
                              AND expires_at <= now()
                            ORDER BY expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        """)
                .param("limit", batchSize)
                .update());
    }

    public List<CleanupCandidate> findUnreferencedObjects(int batchSize) {
        return jdbcClient.sql("""
                        SELECT us.id, us.object_key
                        FROM document_upload_sessions us
                        WHERE us.status IN ('EXPIRED', 'FAILED', 'REJECTED')
                          AND us.cleaned_at IS NULL
                          AND us.completed_at < now() - interval '24 hours'
                          AND NOT EXISTS (
                              SELECT 1 FROM document_versions dv
                              WHERE dv.object_key = us.object_key
                          )
                        ORDER BY us.completed_at
                        LIMIT :limit
                        """)
                .param("limit", batchSize)
                .query((rs, rowNum) -> new CleanupCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("object_key")
                ))
                .list();
    }

    public void markCleaned(UUID uploadId) {
        jdbcClient.sql("""
                        UPDATE document_upload_sessions
                        SET cleaned_at = now()
                        WHERE id = :id AND cleaned_at IS NULL
                        """)
                .param("id", uploadId)
                .update();
    }

    public record CleanupCandidate(UUID uploadId, String objectKey) {}
}
