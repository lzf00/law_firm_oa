package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SessionTokenServiceTest {
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> values;
    private SessionTokenService service;

    @BeforeEach
    void setUp() {
        service = new SessionTokenService(redisTemplate);
    }

    @Test
    void issuesOpaque256BitTokenWithEightHourTtl() {
        when(redisTemplate.opsForValue()).thenReturn(values);

        String token = service.issue("admin");

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(SessionTokenService.redisKey(token))
                .startsWith("lawoa:session:")
                .doesNotContain(token)
                .hasSize("lawoa:session:".length() + 64);
        verify(values).set(SessionTokenService.redisKey(token), "admin", Duration.ofHours(8));
    }

    @Test
    void rejectsMalformedTokenWithoutRedisLookup() {
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("short")).isEmpty();
        assertThat(service.resolve("x".repeat(129))).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void resolvesWellFormedTokenFromRedis() {
        String token = "a".repeat(43);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(SessionTokenService.redisKey(token))).thenReturn("admin");

        assertThat(service.resolve(token)).contains("admin");
    }

    @Test
    void revokesOnlyNonNullToken() {
        service.revoke(null);
        verify(redisTemplate, never()).delete(anyString());

        service.revoke("token");
        verify(redisTemplate).delete(eq(SessionTokenService.redisKey("token")));
    }
}
