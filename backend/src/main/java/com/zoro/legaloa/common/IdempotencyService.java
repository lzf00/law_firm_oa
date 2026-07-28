package com.zoro.legaloa.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.identity.RequestActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public Optional<JsonNode> begin(
            RequestActor actor,
            String operation,
            String suppliedKey,
            Object request
    ) {
        if (suppliedKey == null || suppliedKey.isBlank()) {
            return Optional.empty();
        }
        String key = scopedKey(operation, suppliedKey);
        String requestHash = hash(request);
        int inserted = jdbcClient.sql("""
                        INSERT INTO idempotency_keys
                            (organization_id, idempotency_key, request_hash, expires_at)
                        VALUES (:organizationId, :key, :requestHash, now() + interval '24 hours')
                        ON CONFLICT (organization_id, idempotency_key) DO NOTHING
                        """)
                .param("organizationId", actor.organizationId())
                .param("key", key)
                .param("requestHash", requestHash)
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        IdempotencyRow existing = jdbcClient.sql("""
                        SELECT request_hash, response_body::text
                        FROM idempotency_keys
                        WHERE organization_id = :organizationId
                          AND idempotency_key = :key
                        """)
                .param("organizationId", actor.organizationId())
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRow(
                        rs.getString("request_hash"),
                        rs.getString("response_body")
                ))
                .single();
        if (!existing.requestHash().equals(requestHash)) {
            throw new BusinessException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "同一个幂等键不能用于不同请求",
                    HttpStatus.CONFLICT
            );
        }
        if (existing.responseBody() == null) {
            throw new BusinessException(
                    "IDEMPOTENCY_IN_PROGRESS",
                    "相同请求正在处理中，请稍后重试",
                    HttpStatus.CONFLICT
            );
        }
        try {
            return Optional.of(objectMapper.readTree(existing.responseBody()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is invalid", exception);
        }
    }

    public void complete(
            RequestActor actor,
            String operation,
            String suppliedKey,
            int responseStatus,
            Object response
    ) {
        if (suppliedKey == null || suppliedKey.isBlank()) {
            return;
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(response == null ? objectMapper.createObjectNode() : response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize idempotency response", exception);
        }
        jdbcClient.sql("""
                        UPDATE idempotency_keys
                        SET response_status = :status, response_body = CAST(:body AS jsonb)
                        WHERE organization_id = :organizationId
                          AND idempotency_key = :key
                        """)
                .param("status", responseStatus)
                .param("body", body)
                .param("organizationId", actor.organizationId())
                .param("key", scopedKey(operation, suppliedKey))
                .update();
    }

    public <T> T deserialize(JsonNode node, Class<T> type) {
        return objectMapper.convertValue(node, type);
    }

    private String hash(Object request) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash idempotent request", exception);
        }
    }

    private String scopedKey(String operation, String suppliedKey) {
        String normalized = suppliedKey.trim();
        if (normalized.length() > 100) {
            normalized = hash(normalized.getBytes(StandardCharsets.UTF_8));
        }
        return operation + ":" + normalized;
    }

    private record IdempotencyRow(String requestHash, String responseBody) {}
}
