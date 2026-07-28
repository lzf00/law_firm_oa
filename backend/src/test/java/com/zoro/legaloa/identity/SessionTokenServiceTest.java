package com.zoro.legaloa.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SessionTokenServiceTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> values;
    private SessionTokenService service;

    @BeforeEach
    void setUp() {
        service = new SessionTokenService(redisTemplate, new ObjectMapper());
    }

    @Test
    void issuesOpaque256BitTokenWithEightHourTtl() {
        when(redisTemplate.opsForValue()).thenReturn(values);

        String token = service.issue(actor());

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(SessionTokenService.redisKey(token))
                .startsWith("lawoa:session:")
                .doesNotContain(token)
                .hasSize("lawoa:session:".length() + 64);
        verify(values).set(
                SessionTokenService.redisKey(token),
                "{\"version\":1,\"userId\":\"00000000-0000-0000-0000-000000000101\","
                        + "\"organizationId\":\"00000000-0000-0000-0000-000000000201\","
                        + "\"username\":\"admin\"}",
                Duration.ofHours(8)
        );
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
        when(values.get(SessionTokenService.redisKey(token))).thenReturn(
                "{\"version\":1,\"userId\":\"00000000-0000-0000-0000-000000000101\","
                        + "\"organizationId\":\"00000000-0000-0000-0000-000000000201\","
                        + "\"username\":\"admin\"}"
        );

        assertThat(service.resolve(token)).contains(
                new SessionSubject(1, USER_ID, ORGANIZATION_ID, "admin")
        );
    }

    @Test
    void rejectsAndRevokesLegacyUsernameOnlySession() {
        String token = "b".repeat(43);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(SessionTokenService.redisKey(token))).thenReturn("admin");

        assertThat(service.resolve(token)).isEmpty();
        verify(redisTemplate).delete(SessionTokenService.redisKey(token));
    }

    @Test
    void revokesOnlyNonNullToken() {
        service.revoke(null);
        verify(redisTemplate, never()).delete(anyString());

        service.revoke("token");
        verify(redisTemplate).delete(eq(SessionTokenService.redisKey("token")));
    }

    private RequestActor actor() {
        return new RequestActor(USER_ID, ORGANIZATION_ID, "admin", "Administrator");
    }
}
