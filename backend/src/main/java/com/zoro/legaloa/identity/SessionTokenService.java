package com.zoro.legaloa.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionTokenService {
    static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String KEY_PREFIX = "lawoa:session:";
    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionTokenService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String issue(RequestActor actor) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redisTemplate.opsForValue().set(
                    redisKey(token),
                    objectMapper.writeValueAsString(SessionSubject.from(actor)),
                    SESSION_TTL
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize session subject", exception);
        }
        return token;
    }

    public Optional<SessionSubject> resolve(String token) {
        if (token == null || token.length() < 32 || token.length() > 128) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(redisKey(token));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, SessionSubject.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            revoke(token);
            return Optional.empty();
        }
    }

    public void revoke(String token) {
        if (token != null) {
            redisTemplate.delete(redisKey(token));
        }
    }

    static String redisKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
