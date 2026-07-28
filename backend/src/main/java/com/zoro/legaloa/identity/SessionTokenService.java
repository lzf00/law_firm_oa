package com.zoro.legaloa.identity;

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
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String KEY_PREFIX = "lawoa:session:";
    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;

    public SessionTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issue(String username) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(redisKey(token), username, SESSION_TTL);
        return token;
    }

    public Optional<String> resolve(String token) {
        if (token == null || token.length() < 32 || token.length() > 128) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisTemplate.opsForValue().get(redisKey(token)));
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
